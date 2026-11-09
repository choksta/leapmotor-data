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
 * 只做"能被数据支撑"的统计：里程、能耗、充电次数、用车日、时段分布、与上一周期对比。
 * 刻意不产出"心情""感受"这类判断 —— 那是大模型根据这些数字去写的，不是我们编的。
 */
object ReportStats {

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    enum class Period(val label: String, val days: Int) {
        WEEK("本周", 7),
        MONTH("本月", 30),
        QUARTER("近 90 天", 90),
        YEAR("今年", 365),
        /** 今年实际天数，运行时算，不用固定值。 */
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
        /** 前 5 名里程日，喂给模型写细节用。 */
        val topDays: List<Triple<String, Double, Int>>,
        /** 本地数据实际能覆盖到的最早一天。 */
        val coverageStartDay: String,
        /** 本地数据覆盖的天数。 */
        val coverageDays: Int,
        /**
         * 请求的区间是否超出了已有数据。
         *
         * 云端只保证近 30 天明细，所以「今年」「近 90 天」在刚装上时取到的是同一段数据。
         * 这种情况必须显式标出来，否则用户会以为两个数是重复统计。
         */
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

        // 本地只有多云端的近 30 天，请求更长区间时要把窗口裁到真实覆盖范围内，
        // 否则「今年」和「近 90 天」会算出同一段数据却像两个独立结果。
        val coverageStart = days.firstOrNull()?.day ?: endDay
        val effectiveStart = if (coverageStart > startDay) coverageStart else startDay
        val partial = coverageStart > startDay
        // 跨度天数，不是"有数据的条数"：否则提示写 18 天、下面却显示 17/31 天，自相矛盾
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
        // 否则拿全年（366 天）和只有 2 个月的去年比，会算出 +756% 这种荒谬数字。
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
}
