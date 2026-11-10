package com.haojay.leapmotor.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 空调模式兜底：0（空调关闭）和 null（信号缺失）都不能映射成 wind（换气）。
 * 这是「调风速却变成通风」那个 bug 的回归测试。
 */
class ClimateModeTest {

    @Test
    fun `关闭状态用普通模式`() {
        assertEquals("nohotcold", resolveAcMode(0))
    }

    @Test
    fun `读不到也用普通模式`() {
        assertEquals("nohotcold", resolveAcMode(null))
    }

    @Test
    fun `未知编码不落到换气`() {
        assertEquals("nohotcold", resolveAcMode(2))
        assertEquals("nohotcold", resolveAcMode(99))
    }

    @Test
    fun `有效模式按车端值映射`() {
        assertEquals("cold", resolveAcMode(1))
        assertEquals("hot", resolveAcMode(3))
        assertEquals("wind", resolveAcMode(4))
    }
}
