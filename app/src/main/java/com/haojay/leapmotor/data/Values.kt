package com.haojay.leapmotor.data

/**
 * 信号值 → 中文语义。
 *
 * 映射依据均来自 `api_client.py` 的实测注释，**不臆测**：
 * - 1298 lock_status: 1=已锁 0=未锁
 * - 1149 charge_connection: 1=充电中，{1,2,3,4}=已插枪，0/5=未插枪
 * - 1010 gear: 1/3=行驶，0/2=驻车（原注释只给出粗分类，未区分具体挡位）
 * - 3713 climate_mode: 1=制冷 3=制热 4=换气
 */
object Values {

    fun num(v: Double?): String = when {
        v == null || v.isNaN() -> "--"
        v == v.toLong().toDouble() -> v.toLong().toString()
        else -> "%.1f".format(v)
    }

    fun km(v: Double?): String = if (v == null || v.isNaN()) "--" else "${v.toInt()} km"

    fun pct(v: Double?): String = if (v == null || v.isNaN()) "--" else "%.1f%%".format(v)

    fun onOff(v: Double?): String = when (v?.toInt()) {
        1 -> "开"
        0 -> "关"
        else -> num(v)
    }

    fun gear(v: Double?): String = when (v?.toInt()) {
        1, 3 -> "行驶挡"
        0, 2 -> "驻车挡"
        else -> num(v)
    }

    fun lock(v: Double?): String = when (v?.toInt()) {
        1 -> "已锁车"
        0 -> "未锁车"
        else -> num(v)
    }

    /** 1149 充电状态（白名单语义）。 */
    fun chargeState(v: Double?): String = when (v?.toInt()) {
        1 -> "充电中"
        2 -> "已插枪待机"
        3, 4 -> "已插枪"
        0 -> "未插枪"
        5 -> "未插枪"
        else -> num(v)
    }

    /** 3713 空调模式。 */
    fun climateMode(v: Double?): String = when (v?.toInt()) {
        1 -> "制冷"
        3 -> "制热"
        4 -> "换气"
        else -> num(v)
    }

    /** 车辆状态码：仅 0 可确认为驻车静止，其余保留原值。 */
    fun vehicleState(v: Double?): String = when (v?.toInt()) {
        0 -> "驻车"
        else -> num(v)
    }

    fun kpa(v: Double?): String = if (v == null || v.isNaN()) "--" else "${v.toInt()} kPa"

    fun celsius(v: Double?): String = if (v == null || v.isNaN()) "--" else "%.0f°C".format(v)

    fun volt(v: Double?): String = if (v == null || v.isNaN()) "--" else "%.1f V".format(v)

    fun amp(v: Double?): String = if (v == null || v.isNaN()) "--" else "%.1f A".format(v)

    fun coord(lat: Double?, lon: Double?): String =
        if (lat == null || lon == null || lat.isNaN() || lon.isNaN()) "--"
        else "%.5f, %.5f".format(lat, lon)
}
