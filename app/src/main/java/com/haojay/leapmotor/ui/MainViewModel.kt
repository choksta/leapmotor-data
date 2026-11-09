package com.haojay.leapmotor.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.haojay.leapmotor.data.AppRelease
import com.haojay.leapmotor.data.AuthManager
import com.haojay.leapmotor.data.CarSnapshot
import com.haojay.leapmotor.data.VehicleWatchService
import com.haojay.leapmotor.data.CmdLog
import com.haojay.leapmotor.data.DailyStore
import com.haojay.leapmotor.data.LpSession
import com.haojay.leapmotor.data.Poller
import com.haojay.leapmotor.data.Repo
import com.haojay.leapmotor.data.RemoteImage
import com.haojay.leapmotor.data.SessionStore
import com.haojay.leapmotor.data.SettingsStore
import com.haojay.leapmotor.data.TrendData
import com.haojay.leapmotor.data.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {

    /** 构造参数只在属性初始化时可见，方法内需要访问 context 的地方统一用这个。 */
    private val ctx: Application = app

    private val repo = Repo(app)
    private val auth = AuthManager(app)

    var session by mutableStateOf<LpSession?>(null)
        private set

    /** 是否已有可用会话；false 时展示登录页。 */
    var loggedIn by mutableStateOf(false)
        private set

    var snapshot by mutableStateOf<CarSnapshot?>(null)
        private set

    /**
     * 最近一次数据到达时间。刻意和 [snapshot] 分开存：
     * CarSnapshot 带 signalTime / collectTime，每次轮询都变，如果只靠它驱动状态，
     * equals 永远不成立 → 整屏重组（实测单帧 38~55ms）。拆开之后，信号值没变的轮询
     * 只更新这里，界面上只有"更新于"那行小字重组。
     */
    private val _lastUpdateAt = mutableStateOf(0L)

    /**
     * 用 State 暴露而不是 Long：只有真正读 .value 的那几个文本会重组。
     * 如果按值传进 CarScreen，时间戳一变整页都会重组（实测仍有 24ms）。
     */
    val lastUpdateAt: androidx.compose.runtime.State<Long> get() = _lastUpdateAt

    /** 统一的快照落地口：信号值没变就沿用旧对象，让 Compose 跳过卡片重组。 */
    private fun publishSnapshot(s: CarSnapshot) {
        // 显示的是"车端最后一次上报的时间"，不是我们拉取的时刻。
        // collectTime 每次请求都是新的，用它会让界面永远显示"刚刚"（与官方 App 不一致）。
        _lastUpdateAt.value = if (s.signalTime > 0) s.signalTime else s.collectTime
        val old = snapshot
        if (old == null || old.signals != s.signals) snapshot = s
    }

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

    /** 指令账本（最新在前）。车控留痕，供事后回溯。 */
    var cmdLog by mutableStateOf<List<CmdLog.Entry>>(emptyList())
        private set

    /** 账本文件路径，方便 adb 直接读取核对。 */
    val cmdLogPath: String get() = CmdLog.path(ctx)

    // ---------------- 检查更新 ----------------
    // 这段必须放在 init 之前：Kotlin 按声明顺序初始化属性，
    // 放后面的话 init 里调用 maybeCheckUpdate 会读到默认值。

    /** 当前安装的版本号。 */
    val currentVersion: String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    var updateChecking by mutableStateOf(false)
        private set

    /** 最新 release；null 表示还没查过。 */
    var latestRelease by mutableStateOf<AppRelease?>(null)
        private set

    var updateError by mutableStateOf<String?>(null)
        private set

    var updateIntervalH by mutableStateOf(SettingsStore.getIntervalHours(app))
        private set

    var lastCheckAt by mutableStateOf(SettingsStore.getLastCheckAt(app))
        private set

    val updateAvailable: Boolean
        get() = latestRelease?.let { UpdateChecker.compare(it.version, currentVersion) > 0 } == true

    /**
     * 到点才查：距上次**成功**检查不足间隔就跳过。
     *
     * 没有令牌时直接返回——私有仓库匿名请求必然 404，每次启动刷一遍错误提示只会让人困惑。
     */
    fun maybeCheckUpdate() {
        val last = SettingsStore.getLastCheckAt(ctx)
        if (last > 0 && System.currentTimeMillis() - last < updateIntervalH * 3_600_000L) return
        checkUpdate()
    }

    /** 立即检查，忽略间隔。 */
    fun checkUpdate() {
        if (updateChecking) return
        updateChecking = true
        updateError = null
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { UpdateChecker.fetchLatest() }
            r.onSuccess {
                latestRelease = it
                SettingsStore.setLastCheckAt(ctx, System.currentTimeMillis())
                lastCheckAt = SettingsStore.getLastCheckAt(ctx)
            }.onFailure {
                updateError = it.message ?: "检查失败"
            }
            updateChecking = false
        }
    }

    fun setUpdateInterval(hours: Long) {
        SettingsStore.setIntervalHours(ctx, hours)
        updateIntervalH = hours
        // 改间隔后重新算一次是否到期，否则要等下次启动才生效
        maybeCheckUpdate()
    }

    // ---- 登录态 ----
    var loginBusy by mutableStateOf(false)
        private set
    var codeSent by mutableStateOf(false)
        private set
    var loginError by mutableStateOf<String?>(null)
        private set

    init {
        // 只有"是否已登录"必须同步拿到：它决定首屏是登录页还是主页。
        val s = SessionStore.load(app)
        session = s?.also { it.operatePwd = SettingsStore.getPin(app) }
        loggedIn = s != null
        // 指令日志要读文件 + 解析 JSON，和首屏无关，别卡在启动的主线程上。
        viewModelScope.launch {
            val log = withContext(kotlinx.coroutines.Dispatchers.IO) { CmdLog.recent(ctx, 10) }
            cmdLog = log
        }
        if (s != null) refreshSnapshot()
        maybeCheckUpdate()
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
            publishSnapshot(tick.snapshot)
            error = null
            syncChargeLive(tick.snapshot)
        }
        polling = true
    }

    // ---------------- 充电实时通知 ----------------

    private var chargeLiveOn = false

    /** 后台监控开关（离车后是否继续盯着门锁/车窗/胎压）。 */
    var monitorOn by mutableStateOf(SettingsStore.getMonitorOn(app))
        private set

    fun applyMonitor(on: Boolean) {
        SettingsStore.setMonitorOn(ctx, on)
        monitorOn = on
        // 立刻生效，不等下一轮判定
        VehicleWatchService.sync(ctx, snapshot)
        chargeLiveOn = VehicleWatchService.shouldRun(ctx, snapshot)
    }

    /**
     * 插枪/拔枪、或监控开关变化时，启停前台服务。
     *
     * 判断口径统一在 [VehicleWatchService.shouldRun]：插枪中，或用户开了后台监控。
     * 只在翻转那一刻调一次，服务自己负责后续轮询，App 退到后台也不受影响。
     * App 重启后 [chargeLiveOn] 归 false，会重复调一次 start —— 服务是幂等的，无害。
     */
    private fun syncChargeLive(s: CarSnapshot?) {
        val run = VehicleWatchService.shouldRun(ctx, s)
        if (run == chargeLiveOn) return
        chargeLiveOn = run
        VehicleWatchService.sync(ctx, s)
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
        _lastUpdateAt.value = 0L
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
            r.onSuccess { publishSnapshot(it); error = null; syncChargeLive(it) }
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

    // ---------------- 驻车照片 ----------------
    //
    // 懒加载：只有用户点「查看」才去取地址、才下载。
    // 这图只在哨兵抓拍后才有，每次进页面都拉一遍既慢又白费流量。

    var parkingPhoto by mutableStateOf<android.graphics.Bitmap?>(null)
        private set
    var parkingPhotoAt by mutableStateOf(0L)
        private set
    var parkingPhotoLoading by mutableStateOf(false)
        private set
    var parkingPhotoError by mutableStateOf<String?>(null)
        private set

    fun loadParkingPhoto() {
        val sess = session
        if (sess == null) {
            parkingPhotoError = "未登录"
            return
        }
        if (parkingPhotoLoading) return
        parkingPhotoLoading = true
        parkingPhotoError = null
        viewModelScope.launch {
            val photo = withContext(Dispatchers.IO) {
                runCatching { withAuthRetry(sess) { repo.fetchParkingPhoto(it) } }.getOrNull()
            }
            if (photo == null) {
                parkingPhotoError = "这辆车当前没有驻车照片"
                parkingPhotoLoading = false
                return@launch
            }
            val bmp = withContext(Dispatchers.IO) {
                runCatching { RemoteImage.load(photo.url) }.getOrNull()
            }
            if (bmp == null) {
                parkingPhotoError = "照片下载失败，可能是链接已过期"
            } else {
                parkingPhoto = bmp
                parkingPhotoAt = photo.uploadTimeMs
            }
            parkingPhotoLoading = false
        }
    }

    // ---------------- 坐标系校正 ----------------

    /**
     * 高德是否按 WGS-84 取值。
     *
     * 车端 `2/3` 标称 GCJ-02，高德底图也是 GCJ-02，理论上直接喂就行。
     * 但实测有人在某些机型上看到落点偏约 600 米——正好是一个坐标系偏移的量级。
     * 与其猜，不如留一个开关：偏了就切一下，立刻能对上。
     */
    var mapUseWgs by mutableStateOf(SettingsStore.getMapUseWgs(app))
        private set

    fun toggleMapDatum() {
        val next = !mapUseWgs
        SettingsStore.setMapUseWgs(ctx, next)
        mapUseWgs = next
    }

    /**
     * 探测云端是否支持长区间历史（一次最多一年）。
     *
     * 云端 getMileageRange 带 days 参数，30 天只是我们一直以来的用法。
     * 能返回更多就回填，拿不到也不影响主流程。每天最多试一次。
     */
    private fun maybeBackfillLongRange(sess: LpSession) {
        val prefs = ctx.getSharedPreferences("leapmotor_prefs", android.content.Context.MODE_PRIVATE)
        val last = prefs.getLong("longrange_probe_at", 0L)
        val now = System.currentTimeMillis()
        if (now - last < 20 * 3600_000L) return
        prefs.edit().putLong("longrange_probe_at", now).apply()

        viewModelScope.launch {
            val long = withContext(Dispatchers.IO) {
                runCatching { withAuthRetry(sess) { repo.fetchTrend(it, 365) } }.getOrNull()
            }
            if (long == null || long.daily.isEmpty()) {
                android.util.Log.i("LongRange", "云端未返回长区间明细")
                return@launch
            }
            android.util.Log.i(
                "LongRange",
                "长区间返回 " + long.daily.size + " 天：" + long.daily.first().day + " ~ " + long.daily.last().day
            )
            withContext(Dispatchers.IO) { runCatching { DailyStore.backfill(ctx, long) } }

            // 顺带探测插枪电耗接口：确认它的返回结构，判断值不值得做成功能
            runCatching {
                withContext(Dispatchers.IO) { withAuthRetry(sess) { repo.fetchPlugEnergy(it) } }
            }.onSuccess { resp ->
                val data = resp.optJSONObject("data")
                val keys = data?.keys()?.let { k -> buildList { while (k.hasNext()) add(k.next()) } }
                android.util.Log.i("PlugEnergy", "keys=" + keys + " raw=" + resp.toString().take(400))
            }.onFailure {
                android.util.Log.i("PlugEnergy", "fail: " + it.message)
            }
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
            r.onSuccess {
                trend = it
                // 云端 getMileageRange 带 days 参数，30 天只是我们一直以来的用法。
                // 试一次长区间：能返回更多就回填，拿不到也不影响主流程。每天最多试一次。
                maybeBackfillLongRange(sess)
                // 云端 30 天明细是权威值，回填进本地日表，
                // 这样即使以后换手机，也能拿到至少一个月的真实基线
                withContext(Dispatchers.IO) { runCatching { DailyStore.backfill(ctx, it) } }
            }.onFailure { message = "趋势加载失败：${it.message}" }
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
                publishSnapshot(s)
                error = null
                if (targetReached(s, cmdId, state)) break
            }
            clearOptimistic(cmdId)
            withContext(Dispatchers.IO) { CmdLog.recent(ctx, 10) }.let { cmdLog = it }
        }
    }

    fun reloadCmdLog() {
        cmdLog = CmdLog.recent(ctx, 10)
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
