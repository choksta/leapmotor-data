package com.haojay.leapmotor.data

/**
 * 轮询节奏：一处定义，前台界面和后台服务共用，避免两边跑偏。
 *
 * 分档依据是"数据变化的实际速度"，不是"想多快就多快"：
 *
 * - 行驶中 6 秒：里程/能耗变化快，官方 App 也在这个量级
 * - 充电前段 15 秒：SOC 每分钟约涨 0.5~1%，15 秒能看出数字在动
 * - 充电后段 / 驻车 30 秒：恒压段每分钟只涨 0.1~0.2%，再快是纯浪费请求
 */
object Cadence {

    const val DRIVING_MS = 6_000L

    /** 充电前段。 */
    const val CHARGE_FAST_MS = 15_000L

    /** 充电后段与驻车。 */
    const val IDLE_MS = 30_000L

    /**
     * 进入恒压段的分界。80% 之后大部分车会主动降功率，
     * SOC 曲线明显变平，这时候 15 秒一次只是在空转。
     */
    const val CHARGE_FAST_UNTIL_SOC = 80.0

    /** 按当前快照决定下一次拉取的间隔。null（没拿到数据）时按保守值。 */
    fun forSnapshot(snap: CarSnapshot?): Long {
        if (snap == null) return IDLE_MS
        if (snap.isCharging) {
            val soc = snap.socPrecise ?: 0.0
            return if (soc < CHARGE_FAST_UNTIL_SOC) CHARGE_FAST_MS else IDLE_MS
        }
        return IDLE_MS
    }
}
