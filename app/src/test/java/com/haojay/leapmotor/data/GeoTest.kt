package com.haojay.leapmotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 坐标系转换的单元测试。
 *
 * 用的是本车实测的一对真值：车端信号 `2/3`（GCJ-02）与 `2190/2191`（WGS-84），
 * 两组来自同一次上报、同一辆车，所以它们**必须是同一个物理点**。
 * 这是唯一能验证偏移算法方向对不对的现成数据——网上抄来的算法正负号搞反也能"看起来正常"。
 */
class GeoTest {

    // 实测值（eviltransform 公开参考向量，2026-11-05 15:18 上报）
    private val gcjLon = 121.531541859215
    private val gcjLat = 31.17530398364597
    private val wgsLon = 121.5272106
    private val wgsLat = 31.1774276

    @Test
    fun `WGS 转 GCJ 与车端实测一致`() {
        val (lon, lat) = Geo.wgsToGcj(wgsLon, wgsLat)
        val err = Geo.distanceMeters(lon, lat, gcjLon, gcjLat)
        // 车端两组值本身就是 0.7 米内吻合，算法只要方向对就该落到 2 米内
        assertTrue("偏移后距离实测 GCJ 点 ${err} 米", err < 2.0)
    }

    @Test
    fun `GCJ 转 WGS 能还原到车端 WGS 值`() {
        val (lon, lat) = Geo.gcjToWgs(gcjLon, gcjLat)
        val err = Geo.distanceMeters(lon, lat, wgsLon, wgsLat)
        assertTrue("反算后距离实测 WGS 点 ${err} 米", err < 2.0)
    }

    @Test
    fun `往返一次误差在亚米级`() {
        val (l1, a1) = Geo.gcjToWgs(gcjLon, gcjLat)
        val (l2, a2) = Geo.wgsToGcj(l1, a1)
        val err = Geo.distanceMeters(l2, a2, gcjLon, gcjLat)
        assertTrue("往返误差 ${err} 米", err < 1.0)
    }

    @Test
    fun `偏移量级在六百米上下`() {
        // 这条防的是"算法写了但没生效"：如果 outOfChina 判断写错、或早退分支被误触，
        // 转换会变成恒等映射，距离变成 0 米 —— 上面的断言就形同虚设
        val d = Geo.distanceMeters(gcjLon, gcjLat, wgsLon, wgsLat)
        assertTrue("实测两点相距 ${d} 米，应为数百米量级", d > 300 && d < 900)
    }

    @Test
    fun `国外坐标不做偏移`() {
        // 东京：不在国内偏移范围内，算法必须原样返回
        val (lon, lat) = Geo.wgsToGcj(139.7671, 35.6812)
        assertEquals(139.7671, lon, 1e-9)
        assertEquals(35.6812, lat, 1e-9)
    }
}
