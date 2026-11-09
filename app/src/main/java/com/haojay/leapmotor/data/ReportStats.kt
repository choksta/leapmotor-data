package com.haojay.leapmotor.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 报告用的统计聚合。
 *
 * 只做"能被数据支撑"的统计：里程、能耗、充电次数、用车日、时段分布、连续/间断节奏、与上一周期对比。
 * 刻意不产出"心情""感受"这类判断 —— 那是大模型根据这些数字去写的，不是我们编的。
 *
 * 2026-11-09 扩充：为了让报告能写出"具体哪天发生了什么"，补了
 * 工作日/周末拆分、最长连续用车与最长闲置区间、出行时段分布、按周聚合、前后半段对比，
 * 并把逐日里程原样交给模型，让它自己找规律而不是只拿到几个总数。
 */
object ReportStats {

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    enum class Period(val label: String, val days: Int) {
        WEEK("本周", 7),
        MONTH("本月", 30),
        QUARTER("近 90 天", 90),
        YEAR("今年", 365),
    }

    data class Stats(
        val periodLabel: String,
        val startDay: String,
        val endDay: String,
        val totalKm: Double,
        val totalKwh: Double,
        val ecPer100: Double?,
        val driveDays: Int,
        val totalDays: Int,
        val restDays: Int,
        val chargeCount: Int,
        val longestDayKm: Double,
        val longestDayDate: String,
        val avgDailyKm: Double,
        val busiestStartHour: Int,
        val busiestEndHour: Int,
        val prevKm: Double?,
        val prevChargeCount: Int?,
        val topDays: List<Triple<String, Double, Int>>,

        // ---- 让模型能落到"具体哪天"的维度 ----
        /** 工作日与周末的里程拆分。 */
        val weekdayKm: Double = 0.0,
        val weekendKm: Double = 0.0,
        /** 里程最多的星期几，如「周三」。 */
        val busiestWeekday: String = "",
        /** 最长连续用车天数及其起始日。 */
        val driveStreak: Int = 0,
        val driveStreakStart: String = "",
        /** 最长连续没动车天数及其起始日——最有画面感的细节。 */
        val idleStreak: Int = 0,
        val idleStreakStart: String = "",
        /** 首次出行的时段分布。 */
        val hourBuckets: List<Pair<String, Int>> = emptyList(),
        /** 按周聚合：起点日、里程、充电次数。 */
        val weekly: List<Triple<String, Double, Int>> = emptyList(),
        /** 区间前半段 / 后半段里程，用来判断"越跑越多还是越来越少"。 */
        val firstHalfKm: Double = 0.0,
        val secondHalfKm: Double = 0.0,
        /** 逐日里程原样交给模型。 */
        val dailyKm: List<Pair<String, Double>> = emptyList(),

        /** 本地数据实际能覆盖到的最早一天。 */
        val coverageStartDay: String,
        /** 本地数据覆盖的跨度天数。 */
        val coverageDays: Int,
        /** 请求区间是否超出已有数据。 */
        val partial: Boolean,
    )

    fun forDays(days: List<DailyStore.Day>, period: Period, now: Long = System.currentTimeMillis()): Stats =
        forRange(days, period, now)

    private fun forRange(days: List<DailyStore.Day>, period: Period, now: Long): Stats {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val endDay = dayFormat.format(cal.time)

        // 「今年」用自然年起点，其他用滚动窗口
        val startCal = Calendar.getInstance().apply { timeInMillis = now }
        when (period) {
            Period.YEAR -> {
                startCal.set(Calendar.DAY_OF_YEAR, 1)
                startCal.set(Calendar.HOUR_OF_DAY, 0)
                startCal.set(Calendar.MINUTE, 0)
                startCal.set(Calendar.SECOND, 0)
            }
            else -> startCal.add(Calendar.DAY_OF_MONTH, -(period.days - 1))
        }
        val startDay = dayFormat.format(startCal.time)

        // 本地只有多云端的近 30 天，请求更长区间时把窗口裁到真实覆盖范围内，
        // 否则「今年」和「近 90 天」会算出同一段数据却像两个独立结果。
        val coverageStart = days.firstOrNull()?.day ?: endDay
        val effectiveStart = if (coverageStart > startDay) coverageStart else startDay
        val partial = coverageStart > startDay
        val coverageDays = if (days.isEmpty()) 0 else {
            calendarSpan(dayFormat.parse(coverageStart)?.time ?: now, now) + 1
        }

        val inRange = days.filter { it.day >= effectiveStart && it.day <= endDay }
        val prevStartCal = Calendar.getInstance().apply {
            timeInMillis = startCal.timeInMillis
            add(Calendar.DAY_OF_MONTH, -period.days)
        }
        val prevStartDay = dayFormat.format(prevStartCal.time)
        val prevEndCal = Calendar.getInstance().apply { timeInMillis = startCal.timeInMillis; add(Calendar.DAY_OF_MONTH, -1) }
        val prevEndDay = dayFormat.format(prevEndCal.time)
        // 环比只有在"上一周期被数据完整覆盖"时才有意义。
        // 否则拿全年和只有 2 个月的去年比，会算出 +756% 这种荒谬数字。
        val prevCovered = days.firstOrNull()?.day?.let { it <= prevStartDay } ?: false
        val prev = if (prevCovered) {
            days.filter { it.day >= prevStartDay && it.day <= prevEndDay }
        } else {
            emptyList()
        }

        val totalKm = inRange.sumOf { it.km }
        val kwhDays = inRange.filter { !it.kwh.isNaN() }
        val totalKwh = kwhDays.sumOf { it.kwh }
        val ec = if (kwhDays.isNotEmpty() && totalKm > 1.0 && totalKwh > 0) totalKwh / totalKm * 100 else null

        val driveDays = inRange.count { it.moved }
        // 覆盖不足时分母只算有数据的天数，避免出现"用车 3/313 天"这种误导
        val totalDays = if (partial) {
            calendarSpan(dayFormat.parse(effectiveStart)?.time ?: startCal.timeInMillis, now) + 1
        } else {
            calendarSpan(startCal.timeInMillis, now) + 1
        }
        val top = inRange.sortedByDescending { it.km }.take(5)
            .map { Triple(it.day, it.km, it.chargeStarts) }

        val firstHours = inRange.map { it.firstMoveHour }.filter { it in 0..23 }
        val lastHours = inRange.map { it.lastMoveHour }.filter { it in 0..23 }

        val streak = streaks(inRange)
        val halves = halfSplit(inRange)

        return Stats(
            periodLabel = period.label,
            startDay = effectiveStart,
            endDay = endDay,
            totalKm = totalKm.round1(),
            totalKwh = totalKwh.round1(),
            ecPer100 = ec?.round1(),
            driveDays = driveDays,
            totalDays = totalDays,
            restDays = (totalDays - driveDays).coerceAtLeast(0),
            chargeCount = inRange.sumOf { it.chargeStarts },
            longestDayKm = top.firstOrNull()?.second?.round1() ?: 0.0,
            longestDayDate = top.firstOrNull()?.first ?: "",
            avgDailyKm = if (totalDays > 0) (totalKm / totalDays).round1() else 0.0,
            busiestStartHour = firstHours.takeIf { it.isNotEmpty() }?.average()?.roundToInt() ?: -1,
            busiestEndHour = lastHours.takeIf { it.isNotEmpty() }?.average()?.roundToInt() ?: -1,
            prevKm = prev.takeIf { it.isNotEmpty() }?.sumOf { it.km }?.round1(),
            prevChargeCount = prev.takeIf { it.isNotEmpty() }?.sumOf { it.chargeStarts },
            topDays = top,
            weekdayKm = inRange.filter { !isWeekend(it.day) }.sumOf { it.km }.round1(),
            weekendKm = inRange.filter { isWeekend(it.day) }.sumOf { it.km }.round1(),
            busiestWeekday = busiestWeekday(inRange),
            driveStreak = streak.drive,
            driveStreakStart = streak.driveStart,
            idleStreak = streak.idle,
            idleStreakStart = streak.idleStart,
            hourBuckets = hourBuckets(inRange),
            weekly = weeklySums(inRange),
            firstHalfKm = halves.first,
            secondHalfKm = halves.second,
            // 补齐成连续的日期序列：没记录的那天也要画成 0，
            // 否则图上"空档"直接消失，而空档恰恰是最有信息量的部分
            dailyKm = continuousSeries(inRange, effectiveStart, endDay),
            coverageStartDay = coverageStart,
            coverageDays = coverageDays,
            partial = partial,
        )
    }

    private fun calendarSpan(from: Long, to: Long): Int {
        val a = Calendar.getInstance().apply { timeInMillis = from; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
        val b = Calendar.getInstance().apply { timeInMillis = to; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
        return ((b.timeInMillis - a.timeInMillis) / 86_400_000L).toInt()
    }

    private fun Double.round1() = (this * 10).roundToInt() / 10.0

    /** 变化幅度（%），负增长也如实返回。数据不足时返回 null。 */
    fun changePercent(now: Double, prev: Double?): Int? {
        if (prev == null || prev <= 0.0) return null
        return (((now - prev) / prev) * 100).roundToInt()
    }

    fun absChangePercent(now: Double, prev: Double?): Int? =
        changePercent(now, prev)?.let { abs(it) }

    // ---------------- 派生统计 ----------------

    private fun dayOfWeek(day: String): Int? = runCatching {
        val d = dayFormat.parse(day) ?: return null
        Calendar.getInstance().apply { time = d }.get(Calendar.DAY_OF_WEEK)
    }.getOrNull()

    private fun isWeekend(day: String): Boolean =
        dayOfWeek(day) in listOf(Calendar.SATURDAY, Calendar.SUNDAY)

    private fun busiestWeekday(days: List<DailyStore.Day>): String {
        val names = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        val sums = mutableMapOf<Int, Double>()
        days.forEach { d ->
            val w = dayOfWeek(d.day) ?: return@forEach
            sums[w] = (sums[w] ?: 0.0) + d.km
        }
        val best = sums.maxByOrNull { it.value } ?: return ""
        return if (best.value <= 0.0) "" else names[best.key - 1]
    }

    private data class Streaks(val drive: Int, val driveStart: String, val idle: Int, val idleStart: String)

    private fun streaks(days: List<DailyStore.Day>): Streaks {
        var bestDrive = 0; var bestDriveAt = ""
        var curDrive = 0; var curDriveAt = ""
        var bestIdle = 0; var bestIdleAt = ""
        var curIdle = 0; var curIdleAt = ""

        days.sortedBy { it.day }.forEach { d ->
            if (d.moved) {
                if (curDrive == 0) curDriveAt = d.day
                curDrive++
                if (curDrive > bestDrive) { bestDrive = curDrive; bestDriveAt = curDriveAt }
                curIdle = 0
            } else {
                if (curIdle == 0) curIdleAt = d.day
                curIdle++
                if (curIdle > bestIdle) { bestIdle = curIdle; bestIdleAt = curIdleAt }
                curDrive = 0
            }
        }
        return Streaks(bestDrive, bestDriveAt, bestIdle, bestIdleAt)
    }

    private fun hourBuckets(days: List<DailyStore.Day>): List<Pair<String, Int>> {
        val labels = listOf("6-9 点", "9-12 点", "12-15 点", "15-18 点", "18-21 点", "21-24 点")
        val counts = IntArray(6)
        days.forEach { d ->
            val h = d.firstMoveHour
            if (h in 0..23) {
                val idx = when {
                    h < 6 -> 5
                    h < 9 -> 0
                    h < 12 -> 1
                    h < 15 -> 2
                    h < 18 -> 3
                    h < 21 -> 4
                    else -> 5
                }
                counts[idx]++
            }
        }
        return labels.mapIndexed { i, l -> l to counts[i] }
    }

    private fun weeklySums(days: List<DailyStore.Day>): List<Triple<String, Double, Int>> {
        val cal = Calendar.getInstance().apply { firstDayOfWeek = Calendar.MONDAY }
        return days.sortedBy { it.day }
            .groupBy { d ->
                val t = dayFormat.parse(d.day)?.time ?: 0L
                cal.timeInMillis = t
                "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.WEEK_OF_YEAR)}"
            }
            .map { (_, list) ->
                Triple(list.first().day, list.sumOf { it.km }.round1(), list.sumOf { it.chargeStarts })
            }
            .sortedBy { it.first }
    }

    /** 从起始日到结束日逐日铺开，缺的填 0（最多 366 天）。 */
    private fun continuousSeries(
        days: List<DailyStore.Day>,
        start: String,
        end: String,
    ): List<Pair<String, Double>> {
        val byDay = days.associateBy { it.day }
        val out = mutableListOf<Pair<String, Double>>()
        val cal = Calendar.getInstance()
        val s = dayFormat.parse(start)?.time ?: return days.map { it.day to it.km.round1() }
        val e = dayFormat.parse(end)?.time ?: return days.map { it.day to it.km.round1() }
        cal.timeInMillis = s
        var guard = 0
        while (cal.timeInMillis <= e && guard < 400) {
            val key = dayFormat.format(cal.time)
            out += key to (byDay[key]?.km?.round1() ?: 0.0)
            cal.add(Calendar.DAY_OF_MONTH, 1)
            guard++
        }
        return out
    }

    private fun halfSplit(days: List<DailyStore.Day>): Pair<Double, Double> {
        val sorted = days.sortedBy { it.day }
        if (sorted.size < 4) return sorted.sumOf { it.km }.round1() to 0.0
        val mid = sorted.size / 2
        return sorted.take(mid).sumOf { it.km }.round1() to sorted.drop(mid).sumOf { it.km }.round1()
    }
}
