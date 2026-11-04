package com.haojay.leapmotor.data

/** 一帧车况。signals 已按 SignalIds 翻译成语义名。 */
data class CarSnapshot(
    val signals: Map<String, Double>,
    val signalTime: Long,
    val collectTime: Long,
) {
    fun d(key: String): Double? = signals[key]?.takeIf { !it.isNaN() }
    fun i(key: String): Int? = d(key)?.toInt()

    val socPrecise: Double? get() = d("soc_precise") ?: d("soc")
    val rangeLive: Double? get() = d("range_live")
    val odometer: Double? get() = d("odometer")
    val isCharging: Boolean get() = i("charge_connection") == 1
    val isDriving: Boolean get() = (d("speed") ?: 0.0) > 0
    val plugged: Boolean get() = i("charge_connection") in listOf(1, 2, 3, 4)
    val locked: Boolean? get() = i("lock_status")?.let { it == 1 }
    val windowsOpen: Boolean
        get() = listOf("window_percent_left_front", "window_percent_right_front",
            "window_percent_left_rear", "window_percent_right_rear")
            .any { (d(it) ?: 0.0) > 0 }
}

/** 逐日里程/能耗。 */
data class DailyPoint(val day: String, val mileage: Double, val energy: Double)

/** 某周百公里能耗。 */
data class WeeklyEc(val start: String, val end: String, val ec: Double)

/** 上周能耗拆分（kWh）。 */
data class WeekBreakdown(val driver: Double, val ac: Double, val other: Double) {
    val total: Double get() = driver + ac + other
}

/** 趋势页所需的全部数据。 */
data class TrendData(
    val totalMileage: Int = 0,
    val deliveryDays: Int = 0,
    /** 车辆累计总能耗（全生命周期，来自接口 totalEnergy）。 */
    val totalEnergy: Double = 0.0,
    val daily: List<DailyPoint> = emptyList(),
    val weekly: List<WeeklyEc> = emptyList(),
    val lastWeek: WeekBreakdown? = null,
    val rankPercent: String? = null,
    val hundredKmEc: Double? = null,
) {
    /**
     * 区间平均百公里电耗。
     *
     * ⚠️ 不能用 `totalEnergy / totalAccumulatedMileage`：接口返回的 `totalEnergy`
     * 是**车辆累计总能耗**（全生命周期），而 `totalAccumulatedMileage` 只是区间里程。
     * 实测反例：869 km 区间配 1778 kWh，算出来 204 kWh/100km —— 荒谬。
     * （1778 ≈ 12961 km × 13.7，正好是提车至今的总电耗。）
     * 正确做法：用逐日明细各自累加后再相除。
     */
    val avgEc: Double?
        get() {
            val m = daily.sumOf { it.mileage }
            val e = daily.sumOf { it.energy }
            return if (m > 1.0) e / m * 100 else null
        }

    /** 区间内逐日累加的里程（比 totalAccumulatedMileage 更贴近明细）。 */
    val dailyMileageSum: Double get() = daily.sumOf { it.mileage }

    /** 区间内逐日累加的能耗。 */
    val dailyEnergySum: Double get() = daily.sumOf { it.energy }

    /** 终身平均电耗（总能耗 / 总里程）。 */
    val lifetimeEc: Double?
        get() = if (totalMileage > 1 && totalEnergy > 0) totalEnergy / totalMileage * 100 else null

    val activeDays: Int get() = daily.count { it.mileage > 0 }
    val maxDailyMileage: Double get() = daily.maxOfOrNull { it.mileage } ?: 0.0
}
