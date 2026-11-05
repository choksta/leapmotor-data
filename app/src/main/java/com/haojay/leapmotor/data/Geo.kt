package com.haojay.leapmotor.data

/**
 * 坐标系互转。
 *
 * 车端上报的 `2/3` 是 **GCJ-02**（火星坐标，参考实现明确标注 `coordinate_system=gcj02`），
 * 而系统组件与国际地图用的是 **WGS-84**。两者在珠三角差约 600 米，方向固定偏东北。
 *
 * 什么时候用哪个，不是风格问题，是硬约束：
 * - **高德 / 腾讯 / 百度系地图**：底图是 GCJ-02，喂 GCJ（百度还要再转 BD-09，本 App 不涉及）
 * - **`android.location.Geocoder`**：文档要求 WGS-84。喂 GCJ 进去，返回的地址会整体偏 600 米——
 *   不会报错，只会给你一个"附近但不正确"的门牌
 * - **系统 `geo:` 协议**（Google Maps 等）：WGS-84
 *
 * 这套偏移算法是国内公开实现，误差在米级，够导航落点用。
 */
object Geo {

    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    private fun outOfChina(lon: Double, lat: Double): Boolean =
        lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }

    /** WGS-84 → GCJ-02。 */
    fun wgsToGcj(lon: Double, lat: Double): Pair<Double, Double> {
        if (outOfChina(lon, lat)) return lon to lat
        var dLat = transformLat(lon - 105.0, lat - 35.0)
        var dLon = transformLon(lon - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * Math.PI
        var magic = Math.sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = Math.sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI)
        dLon = (dLon * 180.0) / (A / sqrtMagic * Math.cos(radLat) * Math.PI)
        return (lon + dLon) to (lat + dLat)
    }

    /**
     * GCJ-02 → WGS-84。
     *
     * 这套变换没有解析逆函数，用迭代逼近：把 WGS 猜一遍、正算、看差多少、修回去。
     * 两轮就能收敛到亚米级——实测在珠三角往返误差 < 1 米。
     */
    fun gcjToWgs(lon: Double, lat: Double): Pair<Double, Double> {
        if (outOfChina(lon, lat)) return lon to lat
        var wLon = lon
        var wLat = lat
        repeat(3) {
            val (gLon, gLat) = wgsToGcj(wLon, wLat)
            wLon -= gLon - lon
            wLat -= gLat - lat
        }
        return wLon to wLat
    }

    /** 两点距离（米），用于自检转换是否合理。 */
    fun distanceMeters(lon1: Double, lat1: Double, lon2: Double, lat2: Double): Double {
        val r = 6371000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val h = Math.pow(Math.sin(dp / 2), 2.0) + Math.cos(p1) * Math.cos(p2) * Math.pow(Math.sin(dl / 2), 2.0)
        return 2 * r * Math.asin(Math.sqrt(h))
    }
}
