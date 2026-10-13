package com.njfu.schedule

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 日期条↔课表换算的纯函数回归：一周起点（周一/周日）的日期排列。
 * （旧的 pagerPosFromDay 已随日期条改 requestScrollToItem 删除。）
 */
class ScheduleMathTest {

    @Test
    fun headerIndexForWeekStaysInBounds() {
        // 第 53 周的起始下标其实是 371，超出日期条范围(0..370)，必须被夹住
        val maxHeaderIndex = 53 * 7 - 1
        val w53 = (7 + (53 - 1) * 7)
        assertEquals(371, w53)
        assertEquals(maxHeaderIndex, w53.coerceIn(0, maxHeaderIndex))
    }

    // ---------- 一周起点（周一 / 周日）的日期排列 ----------

    @Test
    fun weekDatesMondayStartRunsMondayToSunday() {
        // 第 1 周，termStartMonday = 2026-08-31（周一）
        val d = weekDates("2026-08-31", 1, startSunday = false)
        assertEquals(java.time.LocalDate.of(2026, 8, 31), d[0])  // 周一
        assertEquals(java.time.DayOfWeek.MONDAY, d[0]!!.dayOfWeek)
        assertEquals(java.time.DayOfWeek.SUNDAY, d[6]!!.dayOfWeek)
        assertEquals(java.time.LocalDate.of(2026, 9, 6), d[6])   // 周日
    }

    @Test
    fun weekDatesSundayStartRunsSundayToSaturday() {
        // 周日起点：第 1 周第一天应是【本周之前的周日】= 2026-08-30（不是下个周日 9/6）
        val d = weekDates("2026-08-31", 1, startSunday = true)
        assertEquals("周日起点第一列应是本周周日 8/30", java.time.LocalDate.of(2026, 8, 30), d[0])
        assertEquals(java.time.DayOfWeek.SUNDAY, d[0]!!.dayOfWeek)
        assertEquals(java.time.DayOfWeek.SATURDAY, d[6]!!.dayOfWeek)
        assertEquals(java.time.LocalDate.of(2026, 9, 5), d[6])   // 周六
    }

    @Test
    fun weekDatesSundayStartIsSevenConsecutiveDays() {
        // 周日起点也必须是连续 7 天（旧实现旋转数组导致列内日期错位）
        val d = weekDates("2026-08-31", 3, startSunday = true)
        for (i in 0..5) {
            assertEquals("第 $i 与 ${i + 1} 天应相邻", d[i]!!.plusDays(1), d[i + 1])
        }
    }
}
