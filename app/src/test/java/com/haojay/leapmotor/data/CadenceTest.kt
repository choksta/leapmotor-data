package com.haojay.leapmotor.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 轮询节奏的边界：80% 是分界点，插枪但没在充电（charge_connection=2/3/4）不算充电。
 */
class CadenceTest {

    private fun snap(connection: Int, soc: Double) = CarSnapshot(
        signals = mapOf("charge_connection" to connection.toDouble(), "soc_precise" to soc),
        signalTime = 0L,
        collectTime = 0L,
    )

    @Test
    fun `没数据时用保守间隔`() {
        assertEquals(Cadence.IDLE_MS, Cadence.forSnapshot(null))
    }

    @Test
    fun `充电且低于80用15秒`() {
        assertEquals(Cadence.CHARGE_FAST_MS, Cadence.forSnapshot(snap(1, 42.0)))
        assertEquals(Cadence.CHARGE_FAST_MS, Cadence.forSnapshot(snap(1, 79.9)))
    }

    @Test
    fun `充电到80及以上回到30秒`() {
        assertEquals(Cadence.IDLE_MS, Cadence.forSnapshot(snap(1, 80.0)))
        assertEquals(Cadence.IDLE_MS, Cadence.forSnapshot(snap(1, 96.0)))
    }

    @Test
    fun `插枪未充电按驻车处理`() {
        assertEquals(Cadence.IDLE_MS, Cadence.forSnapshot(snap(2, 30.0)))
        assertEquals(Cadence.IDLE_MS, Cadence.forSnapshot(snap(4, 30.0)))
    }

    @Test
    fun `没插枪按驻车处理`() {
        assertEquals(Cadence.IDLE_MS, Cadence.forSnapshot(snap(0, 55.0)))
    }
}
