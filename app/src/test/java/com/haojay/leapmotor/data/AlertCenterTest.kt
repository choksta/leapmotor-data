package com.haojay.leapmotor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 告警判定的单元测试。
 *
 * 只测纯函数部分（[AlertCenter.decideLowSoc] / [AlertCenter.stateIssue] /
 * [AlertCenter.lowSocBand]），不碰通知构建——那部分依赖 Context，
 * 用 Robolectric 换来的是"能跑"而不是"判对了"，不划算。
 *
 * 重点是**去重逻辑**：驻车 30 秒一轮，判错的代价是半夜被响。
 */
class AlertCenterTest {

    // ---------------- 分档 ----------------

    @Test
    fun `分档只看是否落在区间内`() {
        assertEquals(30, AlertCenter.lowSocBand(30))
        assertEquals(30, AlertCenter.lowSocBand(21))
        assertEquals(20, AlertCenter.lowSocBand(20))
        assertEquals(20, AlertCenter.lowSocBand(11))
        assertEquals(10, AlertCenter.lowSocBand(10))
        assertEquals(10, AlertCenter.lowSocBand(0))
        assertEquals(0, AlertCenter.lowSocBand(31))
        assertEquals(0, AlertCenter.lowSocBand(100))
    }

    @Test
    fun `掉到更低一档才提醒`() {
        // 30% 首次进入 30 档：提醒
        val a = AlertCenter.decideLowSoc(28, 0)
        assertTrue(a.alert)
        assertEquals(30, a.storeBand)

        // 同一档里继续掉：不提醒
        assertFalse(AlertCenter.decideLowSoc(27, 30).alert)
        assertFalse(AlertCenter.decideLowSoc(23, 30).alert)

        // 掉到 20 档：再提醒一次
        val b = AlertCenter.decideLowSoc(19, 30)
        assertTrue(b.alert)
        assertEquals(20, b.storeBand)

        // 掉到 10 档：再提醒
        val c = AlertCenter.decideLowSoc(8, 20)
        assertTrue(c.alert)
        assertEquals(10, c.storeBand)
    }

    @Test
    fun `充过电再掉回来要能重新提醒`() {
        // 曾经提醒到 30 档
        val reset = AlertCenter.decideLowSoc(45, 30)
        assertFalse(reset.alert)
        assertEquals(0, reset.storeBand)

        // 归零后再掉到 30 档，应当重新提醒
        val again = AlertCenter.decideLowSoc(29, reset.storeBand)
        assertTrue(again.alert)
        assertEquals(30, again.storeBand)
    }

    @Test
    fun `回升幅度不足 5% 不算充过电`() {
        // 28% 提醒过 30 档，回到 32% 只涨 4 个点 —— 可能只是测量抖动，不该重置
        // 否则反复漂移就会反复提醒
        val d = AlertCenter.decideLowSoc(32, 30)
        assertFalse(d.alert)
        assertEquals(30, d.storeBand)
    }

    @Test
    fun `满电不提醒，也不写坏档位`() {
        val d = AlertCenter.decideLowSoc(100, 0)
        assertFalse(d.alert)
        assertEquals(0, d.storeBand)
    }

    // ---------------- 充电结束 ----------------

    @Test
    fun `充满才报充满，中途掉回报中断`() {
        // 上一帧在充、这一帧不在充、还插着枪
        assertEquals(
            AlertCenter.ChargeEvent.DONE,
            AlertCenter.decideChargeEvent(true, false, true, 96)
        )
        assertEquals(
            AlertCenter.ChargeEvent.STOPPED,
            AlertCenter.decideChargeEvent(true, false, true, 62)
        )
    }

    @Test
    fun `拔枪不算中断`() {
        // 人就在车边拔的枪，再弹一条"充电已停止"是纯噪音
        assertEquals(
            AlertCenter.ChargeEvent.NONE,
            AlertCenter.decideChargeEvent(true, false, false, 62)
        )
    }

    @Test
    fun `没有上一帧或仍在充电时不报`() {
        assertEquals(
            AlertCenter.ChargeEvent.NONE,
            AlertCenter.decideChargeEvent(null, false, true, 62)
        )
        assertEquals(
            AlertCenter.ChargeEvent.NONE,
            AlertCenter.decideChargeEvent(true, true, true, 62)
        )
        // 一直没在充的车：不能因为"95% 但没充电"就报充满
        assertEquals(
            AlertCenter.ChargeEvent.NONE,
            AlertCenter.decideChargeEvent(false, false, true, 98)
        )
    }

    // ---------------- 状态异常合并 ----------------

    private fun snap(vararg kv: Pair<String, Double>) = CarSnapshot(
        signals = kv.toMap(),
        signalTime = 0L,
        collectTime = 0L,
    )

    @Test
    fun `全部正常时不产生提醒`() {
        val s = snap(
            "lock_status" to 1.0,
            "window_percent_left_front" to 0.0,
            "window_percent_right_front" to 0.0,
            "window_percent_left_rear" to 0.0,
            "window_percent_right_rear" to 0.0,
            "trunk_open" to 0.0,
        )
        assertNull(AlertCenter.stateIssue(s))
    }

    @Test
    fun `未锁车与车窗合并成一条`() {
        val s = snap(
            "lock_status" to 0.0,
            "window_percent_left_front" to 40.0,
            "trunk_open" to 0.0,
        )
        val issue = AlertCenter.stateIssue(s)
        assertTrue(issue!!.contains("车门未锁"))
        assertTrue(issue.contains("车窗未关"))
        // 车端没标状态位的门序，所以不按门归因，只报聚合结论
        assertFalse(issue.contains("左前"))
    }

    @Test
    fun `只给状态位不给开度时也要报车窗`() {
        // 参考实现实测：有的车型 644/645/865/866 恒为 0，只有 1693~1696 会变。
        // 只认开度的话，这类车的"车窗未关"永远不触发。
        val s = snap(
            "lock_status" to 1.0,
            "window_state_1" to 2.0,
            "window_percent_left_front" to 0.0,
        )
        assertEquals("车窗未关", AlertCenter.stateIssue(s))
    }

    @Test
    fun `状态位全为 0 时不报车窗`() {
        val s = snap(
            "lock_status" to 1.0,
            "window_state_1" to 0.0,
            "window_state_2" to 0.0,
            "window_state_3" to 0.0,
            "window_state_4" to 0.0,
        )
        assertNull(AlertCenter.stateIssue(s))
    }

    @Test
    fun `后备箱单独提示`() {
        val s = snap("lock_status" to 1.0, "trunk_open" to 1.0)
        assertEquals("后备箱未关", AlertCenter.stateIssue(s))
    }

    @Test
    fun `锁车状态未知时不报未锁车`() {
        // lock_status 缺字段时 locked == null，不能当成"未锁"去吓人
        val s = snap("window_percent_left_front" to 0.0)
        assertNull(AlertCenter.stateIssue(s))
    }
}
