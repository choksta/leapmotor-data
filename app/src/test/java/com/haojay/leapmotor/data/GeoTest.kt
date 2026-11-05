package com.haojay.leapmotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 坐标系转换的单元测试。
 *
 * 用的是 **eviltransform 的公开黄金向量**（上海某点），不是任何真实车辆位置：
 *
 *     WGS-84  31.1774276, 121.5272106
 *     GCJ-02  31.17530398364597, 121.531541859215
 *
 * 这组值的价值在于能同时抓住两类最难自查的错：
 * 1. **经度用了纬度的多项式** —— 偏移方向错，但"转换前后不一样"看不出来，
 *    要拿外部参考向量比才暴露；
 * 2. **反算迭代方向写反** —— 单看正算结果正常，一往返就漂。
 *
 * 注意本类方法签名是 `(lon, lat)`，与上游 Python 的 `(lat, lon)` 相反，
 * 抄测试向量时顺序不能照搬。
 */
class GeoTest {

    // eviltransform 公开样例（上海），与上游项目的单测用同一组值
    private val wgsLon = 121.5272106
    private val wgsLat = 31.1774276
    private val gcjLon = 121.531541859215
    private val gcjLat = 31.17530398364597

    @Test
    fun `WGS 转 GCJ 与公开参考向量逐位一致`() {
        val (lon, lat) = Geo.wgsToGcj(wgsLon, wgsLat)
        // 1e-9 是在"逐位吻合"的量级；放宽到 1e-6 就抓不到多项式用错的偏差了
        assertEquals(gcjLon, lon, 1e-9)
        assertEquals(gcjLat, lat, 1e-9)
    }

    @Test
    fun `GCJ 转 WGS 能还原到参考向量`() {
        val (lon, lat) = Geo.gcjToWgs(gcjLon, gcjLat)
        // 反算是迭代逼近，给 1e-7（约 1 厘米）足矣
        assertEquals(wgsLon, lon, 1e-7)
        assertEquals(wgsLat, lat, 1e-7)
    }

    @Test
    fun `往返一次误差在亚米级`() {
        val (l1, a1) = Geo.gcjToWgs(gcjLon, gcjLat)
        val (l2, a2) = Geo.wgsToGcj(l1, a1)
        val err = Geo.distanceMeters(l2, a2, gcjLon, gcjLat)
        assertTrue("往返误差 ${err} 米", err < 1.0)
    }

    @Test
    fun `偏移量级在数百米`() {
        // 这条防的是"算法写了但没生效"：若 outOfChina 判断写错、或早退分支被误触，
        // 转换会退化成恒等映射，上面的断言就成了摆设
        val d = Geo.distanceMeters(gcjLon, gcjLat, wgsLon, wgsLat)
        assertTrue("参考向量两点相距 ${d} 米，应为数百米量级", d > 300 && d < 900)
    }

    @Test
    fun `国外坐标不做偏移`() {
        // 阿姆斯特丹：不在国内偏移范围内，必须原样返回
        val (lon, lat) = Geo.wgsToGcj(4.8903, 52.3731)
        assertEquals(4.8903, lon, 1e-9)
        assertEquals(52.3731, lat, 1e-9)
    }
}
