package com.haojay.leapmotor.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地按日积累的用车数据。
 *
 * 为什么必须自己存：云端 `getMileageRange` 只保证近 30 天的逐日明细，
 * 要出「本月 / 今年 / 自定义区间」的报告就得自己攒。云端给的 30 天会用来回填，
 * 之后每天由前台服务在轮询时顺手记一笔。
 *
 * 存储形态刻意用 JSON 文件而不是数据库：一天一条，一年 365 条，量级小到
 * 读写整文件都在几毫秒内，省掉 Room 的依赖和迁移成本。
 */
object DailyStore {

    private const val FILE = "usage_daily.json"
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    /** 一天里我们关心的全部信息。porcelain：写盘时保持字段名稳定。 */
    data class Day(
        val day: String,
        val odoMin: Double = 0.0,
        val odoMax: Double = 0.0,
        val socMin: Double = Double.NaN,
        val socMax: Double = Double.NaN,
        /** 云端回填的里程/能耗，回填过就不再被本地推算覆盖。 */
        val cloudKm: Double = Double.NaN,
        val cloudKwh: Double = Double.NaN,
        /** 当天第一次/最后一次移动的小时（0~23），没动车就是 -1。 */
        val firstMoveHour: Int = -1,
        val lastMoveHour: Int = -1,
        /** 当天发生过几次「拔枪→插枪」以外的充电开始。 */
        val chargeStarts: Int = 0,
    ) {
        /** 本地推算里程：同一天里里程表最大值减最小值。 */
        val localKm: Double get() = if (odoMax > odoMin) odoMax - odoMin else 0.0

        /** 优先用云端回填值，没有再用本地推算。 */
        val km: Double get() = if (!cloudKm.isNaN()) cloudKm else localKm

        /** 只有云端能给当天能耗；没有就是 NaN。 */
        val kwh: Double get() = cloudKwh

        val moved: Boolean get() = km > 0.5
    }

    fun load(ctx: Context): List<Day> = runCatching {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText(Charsets.UTF_8))
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(
                    Day(
                        day = o.optString("day"),
                        odoMin = o.optDouble("odoMin", 0.0),
                        odoMax = o.optDouble("odoMax", 0.0),
                        socMin = o.optDouble("socMin", Double.NaN),
                        socMax = o.optDouble("socMax", Double.NaN),
                        cloudKm = o.optDouble("cloudKm", Double.NaN),
                        cloudKwh = o.optDouble("cloudKwh", Double.NaN),
                        firstMoveHour = o.optInt("firstMoveHour", -1),
                        lastMoveHour = o.optInt("lastMoveHour", -1),
                        chargeStarts = o.optInt("chargeStarts", 0),
                    )
                )
            }
        }.sortedBy { it.day }
    }.getOrDefault(emptyList())

    private fun save(ctx: Context, days: List<Day>) {
        runCatching {
            val arr = JSONArray()
            days.forEach { d ->
                arr.put(
                    JSONObject()
                        .put("day", d.day)
                        .put("odoMin", d.odoMin)
                        .put("odoMax", d.odoMax)
                        // org.json 遇到 NaN 会直接抛异常（"Forbidden numeric value"），
                        // 而 soc/cloud 字段天然可能是 NaN —— 必须写成 null，读回来再还原成 NaN。
                        .put("socMin", d.socMin.jsonSafe())
                        .put("socMax", d.socMax.jsonSafe())
                        .put("cloudKm", d.cloudKm.jsonSafe())
                        .put("cloudKwh", d.cloudKwh.jsonSafe())
                        .put("firstMoveHour", d.firstMoveHour)
                        .put("lastMoveHour", d.lastMoveHour)
                        .put("chargeStarts", d.chargeStarts)
                )
            }
            file(ctx).writeText(arr.toString(), Charsets.UTF_8)
        }
    }

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    /** NaN / Infinity 不能进 JSON，统一转成 null。 */
    private fun Double.jsonSafe(): Any = if (isNaN() || isInfinite()) JSONObject.NULL else this

    /**
     * 记一笔当前车况。由前台服务每轮调用，成本是一次很小的文件读改写，
     * 所以内部做了节流：同一天同一分钟内重复调用只有第一次真正落盘。
     */
    private var lastWriteAt = 0L

    fun record(ctx: Context, snap: CarSnapshot, atMs: Long = System.currentTimeMillis()) {
        val odo = snap.odometer ?: return
        if (odo <= 0) return
        if (atMs - lastWriteAt < 55_000L) return
        lastWriteAt = atMs

        val day = dayFormat.format(Date(atMs))
        val days = load(ctx).toMutableList()
        val idx = days.indexOfFirst { it.day == day }
        val old = if (idx >= 0) days[idx] else Day(day = day, odoMin = odo, odoMax = odo)

        val soc = snap.socPrecise ?: Double.NaN
        val hour = ((atMs / 3_600_000L) % 24).toInt()
        val moving = (snap.d("speed") ?: 0.0) > 0.0

        val updated = old.copy(
            odoMin = if (old.odoMin <= 0) odo else minOf(old.odoMin, odo),
            odoMax = maxOf(old.odoMax, odo),
            socMin = if (soc.isNaN()) old.socMin else if (old.socMin.isNaN()) soc else minOf(old.socMin, soc),
            socMax = if (soc.isNaN()) old.socMax else if (old.socMax.isNaN()) soc else maxOf(old.socMax, soc),
            firstMoveHour = if (moving && old.firstMoveHour < 0) hour else old.firstMoveHour,
            lastMoveHour = if (moving) hour else old.lastMoveHour,
        )
        if (idx >= 0) days[idx] = updated else days += updated
        save(ctx, days)
    }

    /** 充电开始：由服务判定插枪上升沿后调用。 */
    fun recordChargeStart(ctx: Context, atMs: Long = System.currentTimeMillis()) {
        lastWriteAt = 0L // 充电是重要事件，绕过节流
        val day = dayFormat.format(Date(atMs))
        val days = load(ctx).toMutableList()
        val idx = days.indexOfFirst { it.day == day }
        if (idx >= 0) days[idx] = days[idx].copy(chargeStarts = days[idx].chargeStarts + 1)
        else days += Day(day = day, chargeStarts = 1)
        save(ctx, days)
    }

    /**
     * 用云端的 30 天明细回填。
     *
     * 云端是权威值（它是车端统计出来的），本地推算只是兜底，所以云端有数据就覆盖。
     * 已经回填过的日期不会重复写，避免每次刷新都动文件。
     */
    fun backfill(ctx: Context, trend: TrendData) {
        if (trend.daily.isEmpty()) return
        val days = load(ctx).toMutableList()
        var changed = false
        trend.daily.forEach { p ->
            if (p.mileage <= 0 && p.energy <= 0) return@forEach
            val idx = days.indexOfFirst { it.day == p.day }
            if (idx >= 0) {
                val old = days[idx]
                if (old.cloudKm == p.mileage && old.cloudKwh == p.energy) return@forEach
                days[idx] = old.copy(cloudKm = p.mileage, cloudKwh = p.energy)
                changed = true
            } else {
                days += Day(day = p.day, cloudKm = p.mileage, cloudKwh = p.energy)
                changed = true
            }
        }
        if (changed) save(ctx, days.sortedBy { it.day })
    }
}
