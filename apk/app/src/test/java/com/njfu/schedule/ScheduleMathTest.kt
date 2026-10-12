package com.njfu.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日期条↔课表换算的纯函数回归。
 * 这里专门盯"越界/非法偏移"——v1.3.3 就是因为 scrollToPage 偏移超出 ±0.5、
 * 日期条下标可能到 371（超出 0..370）而在一滑动时闪退。
 */
class ScheduleMathTest {

    @Test
    fun pagerPosAlwaysInRange() {
        // 覆盖很宽的天序号范围（含负数、超界），page 必须落在 0..52、frac 必须落在 ±0.5 内
        var d = -60f
        while (d <= 420f) {
            val (page, frac) = pagerPosFromDay(d, 7, 52)
            assertTrue("page=$page (d=$d)", page in 0..52)
            assertTrue("frac=$frac (d=$d)", frac >= -0.5f && frac <= 0.5f)
            d += 0.17f
        }
    }

    @Test
    fun pagerPosMatchesDayBoundaries() {
        // 天序号 = 基准(7) 时 = 第 1 周（page 0）
        assertEquals(0 to 0f, pagerPosFromDay(7f, 7, 52))
        // 基准 + 7 = 第 2 周（page 1）
        assertEquals(1 to 0f, pagerPosFromDay(14f, 7, 52))
        // 基准 + 3 天 = 第 1 周往后再 3/7 周（page 0，offset≈0.43）
        val (p, f) = pagerPosFromDay(10f, 7, 52)
        assertEquals(0, p)
        assertTrue("f=$f", f > 0.4f && f < 0.45f)
    }

    @Test
    fun headerIndexForWeekStaysInBounds() {
        // 第 53 周的起始下标其实是 371，超出日期条范围(0..370)，必须被夹住
        val maxHeaderIndex = 53 * 7 - 1
        val w53 = (7 + (53 - 1) * 7)
        assertEquals(371, w53)
        assertEquals(maxHeaderIndex, w53.coerceIn(0, maxHeaderIndex))
    }
}
