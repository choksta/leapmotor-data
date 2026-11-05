package com.haojay.leapmotor.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.haojay.leapmotor.data.AuthManager
import com.haojay.leapmotor.data.CarSnapshot
import com.haojay.leapmotor.data.LpSession
import com.haojay.leapmotor.data.Poller
import com.haojay.leapmotor.data.Repo
import com.haojay.leapmotor.data.SessionStore
import com.haojay.leapmotor.data.SettingsStore
import com.haojay.leapmotor.data.TrendData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = Repo(app)
    private val auth = AuthManager(app)

    var session by mutableStateOf<LpSession?>(null)
        private set

    /** 是否已有可用会话；false 时展示登录页。 */
    var loggedIn by mutableStateOf(false)
        private set

    var snapshot by mutableStateOf<CarSnapshot?>(null)
        private set

    var trend by mutableStateOf<TrendData?>(null)
        private set

    var loadingSnapshot by mutableStateOf(false)
        private set

    var loadingTrend by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var message by mutableStateOf<String?>(null)

    var busyCommand by mutableStateOf<Int?>(null)
        private set

    /**
     * 乐观状态：点击后先把界面改掉，等车端真实状态回来再接管。
     * 官方 App 的"秒响应"就是这么做的——它并不等车端执行完。
     */
    var pendingAcOn by mutableStateOf<Boolean?>(null)
        private set

    /** 车门锁的乐观状态：点完立刻反映，观察期结束交还真实值。 */
    var pendingLock by mutableStateOf<Boolean?>(null)
        private set

    /** 后备箱的乐观状态。 */
    var pendingTrunk by mutableStateOf<Boolean?>(null)
        private set

    // ---- 登录态 ----
    var loginBusy by mutableStateOf(false)
        private set
    var codeSent by mutableStateOf(false)
        private set
    var loginError by mutableStateOf<String?>(null)
        private set

    init {
        val s = SessionStore.load(app)
        session = s?.also { it.operatePwd = SettingsStore.getPin(app) }
        loggedIn = s != null
        if (s != null) refreshSnapshot()
    }

    val hasPin: Boolean get() = !session?.operatePwd.isNullOrEmpty()

    // ---------------- 前台自适应轮询 ----------------

    /** 当前正在跑的观察期协程；新指令会取消它，保证同一时刻只有一条。 */
    private var observeJob: Job? = null

    private val poller = Poller {
        val sess = session ?: error("未登录")
        withAuthRetry(sess) { repo.fetchSnapshot(it) }
    }

    var polling by mutableStateOf(false)
        private set

    var lastTick by mutableStateOf<Poller.Tick?>(null)
        private set

    /** 前台：开始按状态自适应刷新。 */
    fun startPolling() {
        if (!loggedIn || poller.running) return
        poller.start(viewModelScope) { tick ->
            lastTick = tick
            // 每一轮都把最新车况推给界面：这才是"实时"的来源，
            // 否则下完指令界面要等下一轮手动刷新才变。
            snapshot = tick.snapshot
            error = null
        }
        polling = true
    }

    /** 后台：停止刷新。 */
    fun stopPolling() {
        poller.stop()
        polling = false
    }

    // ---------------- 登录 ----------------

    fun sendCode(phone: String) {
        if (loginBusy) return
        loginBusy = true
        loginError = null
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { auth.sendCode(phone) }
            r.onSuccess {
                codeSent = true
                message = "验证码已发送"
            }.onFailure { loginError = it.message }
            loginBusy = false
        }
    }

    fun login(phone: String, code: String) {
        if (loginBusy) return
        loginBusy = true
        loginError = null
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { auth.login(phone, code) }
            r.onSuccess { s ->
                s.operatePwd = SettingsStore.getPin(getApplication())
                session = s
                loggedIn = true
                error = null
                refreshSnapshot()
            }.onFailure { loginError = it.message }
            loginBusy = false
        }
    }

    fun logout() {
        SessionStore.clear(getApplication())
        session = null
        loggedIn = false
        snapshot = null
        trend = null
        codeSent = false
        loginError = null
    }

    // ---------------- 数据 ----------------

    /**
     * 带「token 失效重试」的执行包装。
     *
     * 原来只在调用前按 10 分钟余量预续期（ensureFresh），一旦服务端仍判定 TOKEN 过期
     * （时钟偏差、token 被吊销、预检与调用之间的时间窗），就直接失败——刷新车况时
     * 甚至会直接把用户踢回登录页。这里补上反应式重试：
     * 失效 → 强制续期一次 → 重试一次，仍失败才认为登录真的失效。
     */
    private suspend fun <T> withAuthRetry(sess: LpSession, block: (LpSession) -> T): T {
        auth.ensureFresh(sess)
        return try {
            block(sess)
        } catch (e: Exception) {
            if (!isTokenExpired(e.message)) throw e
            val renewed = kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching { auth.ensureFresh(sess, skewSec = Long.MAX_VALUE) }.getOrDefault(false)
            }
            if (!renewed) throw e
            block(sess)
        }
    }

    /** 服务端「token 失效」的几种表达（302002004 是实测返回码）。 */
    private fun isTokenExpired(msg: String?): Boolean {
        val m = msg ?: return false
        return m.contains("302002004") || m.contains("TOKEN", true) || m.contains("过期")
    }

    fun refreshSnapshot() {
        val sess = session ?: return
        if (loadingSnapshot) return
        loadingSnapshot = true
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { withAuthRetry(sess) { repo.fetchSnapshot(it) } }
            }
            r.onSuccess { snapshot = it; error = null }
                .onFailure {
                    val msg = it.message ?: "刷新失败"
                    error = msg
                    if (msg.contains("302") || msg.contains("TOKEN", true) || msg.contains("过期")) {
                        loginError = "登录已失效，请重新登录"
                        loggedIn = false
                    }
                }
            loadingSnapshot = false
        }
    }

    fun refreshTrend() {
        val sess = session ?: return
        if (loadingTrend) return
        loadingTrend = true
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { withAuthRetry(sess) { repo.fetchTrend(it, 30) } }
            }
            r.onSuccess { trend = it }
                .onFailure { message = "趋势加载失败：${it.message}" }
            loadingTrend = false
        }
    }

    /**
     * 下发车控指令。三层结构：
     *
     *   1. 乐观更新——点完立即改界面，不等车端
     *   2. 观察期——拉车况直到「目标字段达成」，达成即停，30 秒兜底
     *   3. 交还真实状态——观察结束把乐观值清掉
     *
     * ⚠️ 观察期必须单例。`busyCommand` 在下发返回时就释放了，用户可以在观察期内再点一次；
     * 若不取消上一条观察协程，两条循环会同时写 snapshot，而且**先结束的那条会把后一条
     * 指令的乐观状态清掉**，表现是界面闪一下又跳回旧值。
     */
    fun sendCommand(cmdId: Int, state: Map<String, Any?>) {
        val sess = session ?: run { message = "未登录"; return }
        if (busyCommand != null) return
        busyCommand = cmdId

        applyOptimistic(cmdId, state)

        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            val (ok, msg) = withContext(Dispatchers.IO) {
                runCatching {
                    withAuthRetry(sess) { repo.sendCommand(it, cmdId, state) }
                }.getOrElse { false to (it.message ?: "网络异常") }
            }
            message = msg
            busyCommand = null
            if (!ok) {
                clearOptimistic(cmdId)   // 失败立即回滚
                return@launch
            }

            // 前 8 秒 1.5 秒一次（指令刚下去，车端变化最快），之后 3 秒一次，30 秒封顶。
            // 一旦目标字段达成即退出，不再空跑剩下的时间。
            var waited = 0L
            while (waited < 30_000L && isActive) {
                val step = if (waited < 8_000L) 1_500L else 3_000L
                delay(step)
                waited += step
                val s = withContext(Dispatchers.IO) {
                    runCatching { repo.fetchSnapshot(sess) }.getOrNull()
                } ?: continue
                snapshot = s
                error = null
                if (targetReached(s, cmdId, state)) break
            }
            clearOptimistic(cmdId)
        }
    }

    private fun applyOptimistic(cmdId: Int, state: Map<String, Any?>) {
        when (cmdId) {
            110 -> pendingLock = when (state["value"]) {
                "lock" -> true
                "unlock" -> false
                else -> null
            }
            130 -> pendingTrunk = when (state["value"]) {
                "true" -> true
                "false" -> false
                else -> null
            }
            170 -> when (state["operate"] as? String) {
                "off" -> pendingAcOn = false
                "manual" -> pendingAcOn = true
                "auto" -> pendingAcOn = true
            }
        }
    }

    private fun clearOptimistic(cmdId: Int) {
        when (cmdId) {
            110 -> pendingLock = null
            130 -> pendingTrunk = null
            170 -> pendingAcOn = null
        }
    }

    /**
     * 目标字段是否已达成指令期望值。
     *
     * 只对信号语义已确认的指令做判断，其余一律返回 false —— 继续跑到 30 秒兜底。
     * **宁可多等，不可提前判定成功**：提前判定会让界面显示一个并未真正生效的状态。
     */
    private fun targetReached(s: CarSnapshot, cmdId: Int, state: Map<String, Any?>): Boolean {
        return when (cmdId) {
            110 -> s.locked == (state["value"] == "lock")
            130 -> (s.i("trunk_open") == 1) == (state["value"] == "true")
            170 -> (s.i("climate_on") == 1) == (state["operate"] != "off")
            400 -> (s.i("sentry_mode") == 1) == (state["value"] == "on")
            301, 370 -> {
                val level = (state["level"] as? String)?.toIntOrNull() ?: return false
                val key = when (state["position"]) {
                    "driver" -> if (cmdId == 301) "seat_heat_driver" else "seat_vent_driver"
                    "passenger" -> if (cmdId == 301) "seat_heat_passenger" else "seat_vent_passenger"
                    else -> return false
                }
                s.i(key) == level
            }
            else -> false
        }
    }

    // ---- 充电上限 ----

    var chargeSoc by mutableStateOf<Int?>(null)
        private set

    var chargeBusy by mutableStateOf(false)
        private set

    fun loadChargePlan() {
        val sess = session ?: return
        viewModelScope.launch {
            val p = withContext(Dispatchers.IO) {
                runCatching { repo.fetchChargePlan(sess) }.getOrNull()
            }
            chargeSoc = p?.optInt("percent")?.takeIf { it in 50..100 }
        }
    }

    fun applyChargeSoc(soc: Int) {
        val sess = session ?: return
        if (chargeBusy) return
        chargeBusy = true
        viewModelScope.launch {
            val (ok, msg) = withContext(Dispatchers.IO) { repo.setChargeSoc(sess, soc) }
            message = msg
            if (ok) chargeSoc = soc
            chargeBusy = false
        }
    }

    /** 保存车控操作密码（4 位 PIN）。 */
    fun savePin(pin: String) {        if (pin.length < 4) {
            message = "请输入 4 位操作密码"
            return
        }
        SettingsStore.setPin(getApplication(), pin)
        session?.operatePwd = pin
        message = "操作密码已保存"
    }
}
