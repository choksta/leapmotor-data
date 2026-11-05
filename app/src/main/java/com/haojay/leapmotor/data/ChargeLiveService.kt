package com.haojay.leapmotor.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.haojay.leapmotor.MainActivity
import com.haojay.leapmotor.R
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
 * 充电期间的实时活动通知。
 *
 * 用的是 Android 16 的原生 Live Updates：`ProgressStyle` + `setRequestPromotedOngoing`。
 * 系统接不接受由它判断——ColorOS 16+ 会渲染成流体云，HyperOS 会渲染成超级岛，
 * 什么都不认的系统就退化成一条普通常驻通知。**不需要也不存在厂商白名单**。
 *
 * 三条硬约束（不满足会被系统拒绝提升，而且不报错，只是静默退回普通通知）：
 * 1. 样式必须是 Standard / BigText / CallStyle / ProgressStyle / MetricStyle 之一
 * 2. 通知渠道 importance 不能是 MIN
 * 3. 必须 ongoing + 显式请求提升，且不能自定义 RemoteViews
 *
 * 只在充电时跑，拔枪即 stopSelf。前台服务类型用 dataSync ——
 * Android 15+ 对它有 6 小时上限，慢充过夜可能会被系统掐掉，这是已知取舍。
 */
class ChargeLiveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须立刻 startForeground：Android 14+ 超过 5 秒会抛
        // ForegroundServiceDidNotStartInTimeException
        startForeground(NOTIF_ID, buildNotification(null))
        if (pollJob?.isActive != true) pollJob = scope.launch { poll() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun poll() {
        val auth = AuthManager(applicationContext)
        val repo = Repo(applicationContext)

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
                }.getOrNull()
            }

            if (snap != null) {
                // 拔枪就收工。留一条"充电中"的常驻通知挂在状态栏上比没有更糟。
                if (!snap.plugged) {
                    stopSelf()
                    return
                }
                val mgr = getSystemService(NotificationManager::class.java)
                mgr?.notify(NOTIF_ID, buildNotification(snap))
            }

            delay(30_000L)
        }
    }

    private fun buildNotification(snap: CarSnapshot?): Notification {
        ensureChannel()

        val soc = (snap?.socPrecise ?: 0.0).roundToInt().coerceIn(0, 100)
        val remain = snap?.d("remaining_charge_minutes")?.toInt()?.takeIf { it in 1..6000 }
        val range = snap?.rangeLive?.roundToInt()

        val chargingNow = snap?.isCharging == true
        val title = when {
            snap == null -> "正在读取充电状态"
            chargingNow -> "充电中 · $soc%"
            soc >= 100 -> "已充满 · $soc%"
            else -> "已插枪 · $soc%"
        }
        val detail = buildString {
            when {
                snap == null -> append("连接车辆…")
                chargingNow && remain != null -> append("预计 ").append(duration(remain)).append(" 充满")
                chargingNow -> append("正在充电")
                soc >= 100 -> append("可以拔枪了")
                else -> append("等待开始充电")
            }
            if (range != null && range > 0) append(" · 续航 ").append(range).append(" km")
        }

        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_charge)
            .setContentTitle(title)
            .setContentText(detail)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openIntent())
            // 进度条直接表达 SOC（0~100 的百分比），实时活动最合适的形态
            .setStyle(NotificationCompat.ProgressStyle().setProgress(soc))

        // 状态栏胶囊旁的短文本：系统只给很窄的位置，放数值最合适
        b.setShortCriticalText(if (remain != null) shortDuration(remain) else "$soc%")

        // 请求提升为实时活动。低于 Android 16 会自动忽略，不用自己判断版本。
        b.setRequestPromotedOngoing(true)

        return b.build()
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        // 不能是 IMPORTANCE_MIN —— 那会让通知失去被提升的资格
        val ch = NotificationChannel(
            CHANNEL_ID,
            "充电实时状态",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "充电时在状态栏显示电量与预计充满时间"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        mgr.createNotificationChannel(ch)
    }

    private fun openIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 完整时长：通知正文用。 */
    private fun duration(min: Int): String =
        if (min >= 60) "${min / 60} 小时 ${min % 60} 分" else "$min 分钟"

    /** 极短时长：胶囊旁边最多容得下 5~6 个字符。 */
    private fun shortDuration(min: Int): String =
        if (min >= 60) "${min / 60}时${min % 60}分" else "$min 分"

    companion object {
        private const val CHANNEL_ID = "charge_live"
        private const val NOTIF_ID = 1001

        fun start(ctx: Context) {
            val i = Intent(ctx, ChargeLiveService::class.java)
            // 通知权限没给时 startForegroundService 会失败，这里吞掉：
            // 没有通知不该让整个 App 崩
            runCatching { ContextCompat.startForegroundService(ctx, i) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, ChargeLiveService::class.java)) }
        }
    }
}
