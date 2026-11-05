package com.haojay.leapmotor.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.haojay.leapmotor.MainActivity
import com.haojay.leapmotor.R
import kotlin.math.roundToInt

/**
 * 车辆提醒中心：判定"该不该提醒"，并构建对应通知。
 *
 * 分三层，**刻意不混用**：
 *
 * 1. **实时活动**（Live Updates，会钉在状态栏）——只给充电进度。
 *    它是"用户正在进行中的任务"，系统会强制展示、用户没法忽略；
 *    拿它当告警通道既会被判定滥用（提升被拒），也会让人直接关掉这个 App 的实时活动。
 *
 * 2. **状态异常**（常驻通知，不提升）——未锁车 / 车窗 / 后备箱 / 胎压。
 *    合并成一条，避免三四条通知刷屏；状态消失后自动撤掉。
 *
 * 3. **一次性告警**——低电量分档、充满。可以响、可以划掉，不占用状态栏。
 */
object AlertCenter {

    const val CH_LIVE = "charge_live"
    const val CH_STATE = "vehicle_state"
    const val CH_ALERT = "vehicle_alert"

    const val ID_LIVE = 1001
    const val ID_STATE = 1002
    const val ID_ALERT = 1003

    private const val K_SOC_BAND = "alert_soc_band"
    private const val K_TIRE_AT = "alert_tire_at"
    private const val TIRE_THROTTLE_MS = 6 * 3600_000L

    // ---------------- 通道 ----------------

    fun ensureChannels(ctx: Context) {
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return

        // 实时活动：importance 不能是 MIN，否则失去提升资格
        if (mgr.getNotificationChannel(CH_LIVE) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CH_LIVE, "充电实时状态", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "充电时在状态栏显示电量与预计充满时间"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }

        // 状态异常：要能被看见，但不该响
        if (mgr.getNotificationChannel(CH_STATE) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CH_STATE, "车辆状态异常", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "车门未锁、车窗或后备箱未关、胎压报警"
                    setShowBadge(true)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }

        // 一次性告警：允许响铃。低电量和充满都属于"需要你知道"的事件
        if (mgr.getNotificationChannel(CH_ALERT) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CH_ALERT, "电量与充电提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "电量低于 30% / 20% / 10%，以及充电完成"
                    setShowBadge(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }

    // ---------------- 实时活动：充电 ----------------

    fun chargeNotification(ctx: Context, snap: CarSnapshot?): Notification {
        val soc = ((snap?.socPrecise ?: 0.0).roundToInt()).coerceIn(0, 100)
        val remain = snap?.d("remaining_charge_minutes")?.toInt()?.takeIf { it in 1..6000 }
        val range = snap?.rangeLive?.roundToInt()
        val volt = snap?.d("charging_voltage")?.takeIf { it > 100 }

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
            if (chargingNow && volt != null) append(" · ").append(volt.roundToInt()).append(" V")
        }

        return NotificationCompat.Builder(ctx, CH_LIVE)
            .setSmallIcon(R.drawable.ic_stat_charge)
            .setContentTitle(title)
            .setContentText(detail)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(ctx, 1))
            .setStyle(NotificationCompat.ProgressStyle().setProgress(soc))
            .setShortCriticalText(if (remain != null) shortDuration(remain) else "$soc%")
            .setRequestPromotedOngoing(true)
            .build()
    }

    // ---------------- 监控占位通知 ----------------

    /**
     * 没插枪但在做后台监控时，前台服务需要一条通知占位。
     *
     * 故意做成最安静的形态：不提升、不响、不可划掉。它存在的唯一理由是
     * Android 要求前台服务必须有通知，而不是让用户盯着看。
     */
    fun monitorNotification(ctx: Context, snap: CarSnapshot?): Notification {
        val soc = (snap?.socPrecise ?: 0.0).roundToInt()
        val text = if (snap == null) {
            "正在读取车辆状态…"
        } else {
            val locked = snap.locked?.let { if (it) "已锁车" else "未锁车" } ?: "锁车状态未知"
            "电量 $soc% · $locked · 状态变化会提醒你"
        }

        return NotificationCompat.Builder(ctx, CH_LIVE)
            .setSmallIcon(R.drawable.ic_stat_charge)
            .setContentTitle("车辆监控中")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(ctx, 5))
            .build()
    }

    // ---------------- 状态异常：合并成一条 ----------------

    /**
     * 车门 / 车窗 / 后备箱 / 胎压的合并提醒。
     *
     * 返回 null 表示一切正常，调用方应当把这条通知撤掉。
     * 注意这一条**不**请求提升：它需要长时间挂着，但不需要占据实时活动的位置。
     */
    fun stateIssue(snap: CarSnapshot): String? {
        val parts = mutableListOf<String>()

        if (snap.locked == false) parts += "车门未锁"

        // 车窗判断走聚合：状态位 1693~1696 与开度 644/645/865/866 在两套车型上各有缺失，
        // 只认其中一套会导致"有的车永远不报、有的车一直报"。
        // 因为车端没标状态位的门序，这里不按门归因。
        if (snap.windowsAjar) parts += "车窗未关"

        if (snap.i("trunk_open") == 1) parts += "后备箱未关"

        // 胎压报警单独一条，混进状态里会被忽略
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }

    fun stateNotification(ctx: Context, issue: String): Notification =
        NotificationCompat.Builder(ctx, CH_STATE)
            .setSmallIcon(R.drawable.ic_stat_charge)
            .setContentTitle(issue)
            .setContentText("状态变化后这条会自动消失")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(openApp(ctx, 2))
            .setStyle(NotificationCompat.BigTextStyle().bigText("$issue\n\n状态变化后这条会自动消失"))
            .build()

    // ---------------- 一次性告警：低电量 ----------------

    /**
     * 电量分档提醒。只在**跨档**时触发一次，回升 5% 以上才重置。
     *
     * 不这么做的话，驻车时每 30 秒轮询一次，等于每 30 秒响一声。
     */
    fun lowSocBand(soc: Int): Int = when {
        soc <= 10 -> 10
        soc <= 20 -> 20
        soc <= 30 -> 30
        else -> 0
    }

    /**
     * 低电量判定（纯函数，便于单测）。
     *
     * @param lastBand 上次提醒过的档位，0 表示没提醒过
     * @return 应该写回的档位 + 这次要不要提醒
     *
     * 三条规则，顺序不能换：
     * 1. 回升到**上次提醒档位 +5** 以上 → 认为真充过电了，档位归零
     *    （不能拿当前档位去比：`soc > band + 5` 永远不成立，band 本身就是从 soc 推出来的）
     * 2. 已经在健康区间（band == 0）但还没升够 → **保留**旧档位，否则在 30% 附近抖动会反复提醒
     * 3. 掉到更低一档（band 更小），或者从未提醒过 → 提醒
     */
    fun decideLowSoc(soc: Int, lastBand: Int): SocDecision {
        val band = lowSocBand(soc)
        if (lastBand > 0 && soc >= lastBand + 5) return SocDecision(0, false)
        if (band == 0) return SocDecision(lastBand, false)
        if (lastBand == 0 || band < lastBand) return SocDecision(band, true)
        return SocDecision(lastBand, false)
    }

    data class SocDecision(val storeBand: Int, val alert: Boolean)

    fun lowSocNotification(ctx: Context, snap: CarSnapshot): Notification? {
        // 正在充电时不该报"该找桩了"：插枪那一刻电量本来就低，报了是噪音
        if (snap.isCharging) return null
        val soc = (snap.socPrecise ?: return null).roundToInt()
        val last = prefs(ctx).getInt(K_SOC_BAND, 0)
        val d = decideLowSoc(soc, last)
        if (d.storeBand != last) prefs(ctx).edit().putInt(K_SOC_BAND, d.storeBand).apply()
        if (!d.alert) return null
        val band = lowSocBand(soc)

        val range = snap.rangeLive?.roundToInt()
        val head = when (band) {
            10 -> "电量仅 $soc%"
            20 -> "电量 $soc%"
            else -> "电量 $soc%，该找桩了"
        }
        val body = buildString {
            append("建议尽快充电")
            if (range != null && range > 0) append(" · 剩余续航 $range km")
        }

        return NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_charge)
            .setContentTitle(head)
            .setContentText(body)
            .setAutoCancel(true)
            .setShowWhen(false)
            .setContentIntent(openApp(ctx, 3))
            .setPriority(if (band <= 20) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    // ---------------- 一次性告警：充电结束 ----------------

    enum class ChargeEvent { NONE, DONE, STOPPED }

    /** 充满判定线。不写 100：涓流最后几个点可能长时间不动，等到 100 才提醒等于不提醒。 */
    private const val FULL_SOC = 95

    /**
     * 充电状态翻转判定（纯函数，便于单测）。
     *
     * 只在"上一帧在充、这一帧不在充"时给结论，其它一律 NONE：
     * - 拔枪了 → 不用提醒，人就在旁边
     * - 没插枪/没充过 → 没有可报的事件
     * - 停在 95% 以上 → 充满；否则是中途停止（跳枪、被别人拔枪、桩故障）
     */
    fun decideChargeEvent(
        prevCharging: Boolean?,
        chargingNow: Boolean,
        plugged: Boolean,
        soc: Int,
    ): ChargeEvent {
        if (prevCharging != true) return ChargeEvent.NONE
        if (chargingNow) return ChargeEvent.NONE
        if (!plugged) return ChargeEvent.NONE
        return if (soc >= FULL_SOC) ChargeEvent.DONE else ChargeEvent.STOPPED
    }

    fun chargeEventNotification(
        ctx: Context,
        ev: ChargeEvent,
        soc: Int,
        rangeKm: Int?,
    ): Notification? {
        if (ev == ChargeEvent.NONE) return null
        val done = ev == ChargeEvent.DONE
        val body = buildString {
            if (done) append("现在可以拔枪了") else append("充电已停止，车还插着枪")
            if (rangeKm != null && rangeKm > 0) append(" · 续航 ").append(rangeKm).append(" km")
        }
        return NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_charge)
            .setContentTitle(if (done) "已充满 · $soc%" else "充电中断 · $soc%")
            .setContentText(body)
            .setAutoCancel(true)
            .setShowWhen(false)
            .setContentIntent(openApp(ctx, 6))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    // ---------------- 一次性告警：胎压 ----------------

    private val TIRE_LABELS = listOf(
        "tire_alarm_fl" to "左前",
        "tire_alarm_fr" to "右前",
        "tire_alarm_rl" to "左后",
        "tire_alarm_rr" to "右后",
    )

    fun tireNotification(ctx: Context, snap: CarSnapshot): Notification? {
        val bad = TIRE_LABELS.mapNotNull { (key, label) ->
            if (snap.i(key) == 1) label else null
        }
        if (bad.isEmpty()) return null

        val now = System.currentTimeMillis()
        if (now - prefs(ctx).getLong(K_TIRE_AT, 0L) < TIRE_THROTTLE_MS) return null
        prefs(ctx).edit().putLong(K_TIRE_AT, now).apply()

        return NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_charge)
            .setContentTitle("胎压报警 · " + bad.joinToString("、"))
            .setContentText("靠边停车检查轮胎")
            .setAutoCancel(true)
            .setShowWhen(false)
            .setContentIntent(openApp(ctx, 4))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    // ---------------- 工具 ----------------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("leapmotor_prefs", Context.MODE_PRIVATE)

    private fun openApp(ctx: Context, req: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            ctx, req, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun duration(min: Int): String =
        if (min >= 60) "${min / 60} 小时 ${min % 60} 分" else "$min 分钟"

    private fun shortDuration(min: Int): String =
        if (min >= 60) "${min / 60}时${min % 60}分" else "$min 分"
}
