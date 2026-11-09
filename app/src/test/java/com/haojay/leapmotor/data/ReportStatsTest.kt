package com.haojay.leapmotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 报告统计的边界：区间筛选、云端优先、环比计算。 */
class ReportStatsTest {

    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    private fun now(): Long = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 9, 12, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun day(offset: Int): String {
        val c = Calendar.getInstance().apply { timeInMillis = now(); add(Calendar.DAY_OF_MONTH, offset) }
        return fmt.format(c.time)
    }

    private fun d(offset: Int, km: Double, kwh: Double = Double.NaN, charges: Int = 0) =
        DailyStore.Day(
            day = day(offset),
            cloudKm = km,
            cloudKwh = kwh,
            chargeStarts = charges,
        )

    @Test
    fun `本周只统计最近7天`() {
        val days = listOf(d(-10, 100.0), d(-3, 40.0), d(-1, 60.0))
        val s = ReportStats.forDays(days, ReportStats.Period.WEEK, now())
        assertEquals(100.0, s.totalKm, 0.01)
        assertEquals(2, s.driveDays)
    }

    @Test
    fun `云端里程优先于本地推算`() {
        val local = DailyStore.Day(day = day(-1), odoMin = 1000.0, odoMax = 1050.0)
        val withCloud = local.copy(cloudKm = 12.0)
        assertEquals(50.0, local.km, 0.01)
        assertEquals(12.0, withCloud.km, 0.01)
    }

    @Test
    fun `充电次数与最长一天`() {
        val days = listOf(d(-2, 30.0, charges = 1), d(-1, 120.0, charges = 2))
        val s = ReportStats.forDays(days, ReportStats.Period.WEEK, now())
        assertEquals(3, s.chargeCount)
        assertEquals(120.0, s.longestDayKm, 0.01)
        assertEquals(day(-1), s.longestDayDate)
    }

    @Test
    fun `百公里电耗按区间总量算`() {
        // 100 km 用 15 kWh → 15 kWh/100km
        val days = listOf(d(-2, 50.0, 7.5), d(-1, 50.0, 7.5))
        val s = ReportStats.forDays(days, ReportStats.Period.WEEK, now())
        assertEquals(15.0, s.ecPer100!!, 0.01)
    }

    @Test
    fun `环比百分比`() {
        assertEquals(50, ReportStats.changePercent(150.0, 100.0))
        assertEquals(-50, ReportStats.changePercent(50.0, 100.0))
        assertEquals(null, ReportStats.changePercent(50.0, null))
        assertEquals(null, ReportStats.changePercent(50.0, 0.0))
    }

    @Test
    fun `没有数据时不崩`() {
        val s = ReportStats.forDays(emptyList(), ReportStats.Period.YEAR, now())
        assertEquals(0.0, s.totalKm, 0.01)
        assertTrue(s.topDays.isEmpty())
    }
}
