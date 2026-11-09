package com.haojay.leapmotor.data

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 车辆监控前台服务。
 *
 * 两件事一起做：
 * 1. 插枪时把充电进度推成**实时活动**（Android 16 Live Updates）
 * 2. 每 30 秒判定一次告警条件：低电量分档、未锁车、车窗/后备箱、胎压
 *
 * **为什么需要后台运行**：车门没锁这类提醒，价值恰恰在"人已经离开车"的时候。
 * 只在 App 前台检测等于没做。代价是常驻通知栏，所以做成可关的开关
 * （[SettingsStore.getMonitorOn]），不想开就不开。
 *
 * 前台服务类型用 dataSync，Android 15+ 有 6 小时上限。监控场景下这会到期，
 * 到期后系统停止服务，用户下次打开 App 会重新拉起 —— 已知取舍。
 */
class VehicleWatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    /** 上一帧是否在充电，用于判定"充满/中断"。落盘，服务重启后不重复提醒。 */
    private val prefs by lazy { getSharedPreferences("leapmotor_prefs", Context.MODE_PRIVATE) }

    /** 车门未锁的起始时刻；用于"持续 N 分钟才提醒"，避免人还在车里就被催。 */
    private var unlockedSince: Long? = null

    /**
     * 上一次发出去的实时活动内容。用来做两件事：
     * 1. 内容没变就不重发 —— 重新 startForeground 会让气泡重播一次动画；
     * 2. 内容变了才重发 —— 这是 HyperOS 刷新流体云卡片的前提。
     */
    private var lastLiveKey: String? = null

    /** 上一轮是否插枪，用来捕捉"开始充电"这个上升沿。 */
    private var wasPlugged: Boolean? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AlertCenter.ensureChannels(this)

        // 必须立刻 startForeground（5 秒内），先用占位内容
        if (!promoteToForeground(AlertCenter.monitorNotification(this, null))) return START_NOT_STICKY

        if (pollJob?.isActive != true) pollJob = scope.launch { poll() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun promoteToForeground(n: android.app.Notification): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(AlertCenter.ID_LIVE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(AlertCenter.ID_LIVE, n)
        }
    }.isSuccess

    private suspend fun poll() {
        // 下一次间隔跟着上一帧的状态走：充电前段 15 秒，其余 30 秒。
        // 拿不到数据时保持上一次的节奏，不要因为一次失败就降频。
        var nextMs = Cadence.IDLE_MS
        val auth = AuthManager(applicationContext)
        val repo = Repo(applicationContext)
        val mgr = getSystemService(NotificationManager::class.java)

        while (currentCoroutineContext().isActive) {
            val sess = SessionStore.load(applicationContext)
            if (sess == null) {
                stopSelf()
                return
            }

            val snap = withContext(Dispatchers.IO) {
                runCatching {
                    auth.ensureFresh(sess)
                    repo.fetchSnapshot(sess)
                }.onFailure {
                    android.util.Log.w("Watch", "poll failed: " + it.message)
                }.getOrNull()
            }

            if (snap != null) {
                nextMs = Cadence.forSnapshot(snap)
                // 后台刷新出问题时，这行是唯一能确认"到底有没有拿到新数据"的依据
                android.util.Log.i(
                    "Watch",
                    "poll ok soc=" + (snap.socPrecise ?: -1.0) + " plugged=" + snap.plugged +
                        " charging=" + snap.isCharging
                )
                // 顺手记一笔日表：报告功能的历史数据全靠它攒
                runCatching { DailyStore.record(applicationContext, snap) }
                if (wasPlugged == false && snap.plugged) {
                    runCatching { DailyStore.recordChargeStart(applicationContext) }
                }
                wasPlugged = snap.plugged

                evaluate(snap, mgr)

                // 拔枪且用户没开后台监控 → 收工
                if (!snap.plugged && !SettingsStore.getMonitorOn(applicationContext)) {
                    stopSelf()
                    return
                }
            }

            delay(nextMs)
        }
    }

    /**
     * 更新实时活动（流体云）。
     *
     * 为什么不用 `notify()`：HyperOS 的流体云卡片只在"前台服务通知被重新注册"时重绘，
     * 单纯的 notify 只更新通知栏，气泡会停在首帧 —— 这就是"后台不刷新、回前台才对"的原因。
     * 所以这里改走 `startForeground`，并且只在内容变化时触发，避免气泡反复重播。
     */
    private fun pushLive(mgr: NotificationManager, key: String, n: android.app.Notification) {
        if (key == lastLiveKey) return
        val ok = promoteToForeground(n)
        if (!ok) {
            // 极端情况（系统拒绝重新注册）退回普通更新，至少通知栏是对的
            runCatching { mgr.notify(AlertCenter.ID_LIVE, n) }
        }
        lastLiveKey = key
        android.util.Log.i("Watch", "live update: " + key)
    }

    /** 一轮判定：更新前台通知、状态异常通知、一次性告警。 */
    private fun evaluate(snap: CarSnapshot, mgr: NotificationManager?) {
        val ctx = applicationContext
        mgr ?: return

        // 1) 前台通知：插枪时切成充电实时活动（会请求提升），否则退回监控占位
        // key 直接用快照字段算，比从通知 extras 里反读标题可靠
        val liveKey = listOf(
            snap.plugged, snap.isCharging,
            (snap.socPrecise ?: 0.0).roundToInt(),
            snap.d("remaining_charge_minutes")?.toInt() ?: -1,
            snap.rangeLive?.roundToInt() ?: -1,
            // 监控通知的文案里有锁车状态，漏了它就会出现"锁了车但文案不变"
            snap.locked ?: "unknown"
        ).joinToString("/")
        pushLive(
            mgr,
            liveKey,
            if (snap.plugged) AlertCenter.chargeNotification(ctx, snap)
            else AlertCenter.monitorNotification(ctx, snap)
        )

        // 2) 状态异常：合并一条，正常就撤掉
        val issue = AlertCenter.stateIssue(snap)
        if (issue != null) {
            mgr.notify(AlertCenter.ID_STATE, AlertCenter.stateNotification(ctx, issue))
        } else {
            mgr.cancel(AlertCenter.ID_STATE)
        }

        // 3) 一次性告警
        AlertCenter.lowSocNotification(ctx, snap)?.let { mgr.notify(AlertCenter.ID_ALERT, it) }
        AlertCenter.tireNotification(ctx, snap)?.let { mgr.notify(AlertCenter.ID_ALERT + 1, it) }

        // 4) 充电结束：要和上一帧比，所以状态落盘——服务被系统杀掉重启后
        //    不写盘就会把"充满"重复提醒一次
        val prev = prefs.getInt(K_CHARGING, -1).let { if (it < 0) null else it == 1 }
        AlertCenter.decideChargeEvent(prev, snap.isCharging, snap.plugged, snap.socPrecise?.roundToInt() ?: 0)
            .let { ev ->
                AlertCenter.chargeEventNotification(ctx, ev, snap.socPrecise?.roundToInt() ?: 0, snap.rangeLive?.roundToInt())
                    ?.let { mgr.notify(AlertCenter.ID_ALERT + 2, it) }
            }
        prefs.edit().putInt(K_CHARGING, if (snap.isCharging) 1 else 0).apply()
    }

    companion object {
        /** 落盘的"上一帧在充电"标志：-1 未知、1 在充、0 不在充。 */
        private const val K_CHARGING = "watch_last_charging"
        /**
         * 需要后台监控吗：插枪中，或者用户明确开了监控开关。
         *
         * 这条判断同时被 App 前台轮询和设置界面使用，放这里保证口径一致。
         */
        fun shouldRun(ctx: Context, snap: CarSnapshot?): Boolean {
            if (snap?.plugged == true) return true
            return SettingsStore.getMonitorOn(ctx)
        }

        fun sync(ctx: Context, snap: CarSnapshot?) {
            if (shouldRun(ctx, snap)) start(ctx) else stop(ctx)
        }

        fun start(ctx: Context) {
            val i = Intent(ctx, VehicleWatchService::class.java)
            // 通知权限没给时 startForegroundService 会失败；没有通知不该让 App 崩
            runCatching { ContextCompat.startForegroundService(ctx, i) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, VehicleWatchService::class.java)) }
        }
    }
}
