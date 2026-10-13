package com.njfu.schedule

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/**
 * 小组件"下次精准刷新时刻"纯逻辑回归。
 * nextRefreshAt 决定闹钟几点响——算错会导致"正在上"高亮切不过去，或半夜狂刷。
 */
class WidgetRefreshSchedulerTest {

    private fun c(id: String, name: String, day: Int, s: Int, e: Int, weeks: List<Int> = emptyList()) =
        Course(id, name, "", "", day, s, e, weeks)

    private val schedule = Schedule(
        term = "2026-2027-1", termStartMonday = "2026-08-31", campus = "xz",
        courses = listOf(
            c("a", "高等数学", 1, 1, 2),   // 08:00-09:40
            c("b", "大学英语", 1, 3, 4)    // 10:00-11:40
        )
    )

    @Test
    fun nextRefreshAtTakesNearestBoundaryToday() {
        // 08:50 时：高数 09:40 结束、英语 10:00 开始 → 最近边界 09:40
        val at = WidgetRefreshScheduler.nextRefreshAt(schedule, week = 3, today = 1, now = LocalDateTime.of(2026, 9, 14, 8, 50))
        assertEquals(LocalDateTime.of(2026, 9, 14, 9, 40), at)
    }

    @Test
    fun nextRefreshAtFallsBackToMidnightWhenDayOver() {
        // 12:00 当天课都上完 → 排到次日 0 点
        val at = WidgetRefreshScheduler.nextRefreshAt(schedule, week = 3, today = 1, now = LocalDateTime.of(2026, 9, 14, 12, 0))
        assertEquals(LocalDateTime.of(2026, 9, 15, 0, 0), at)
    }

    @Test
    fun nextRefreshAtFallsBackToMidnightOnDayWithoutClass() {
        // 周二没课，09:00 → 次日 0 点
        val at = WidgetRefreshScheduler.nextRefreshAt(schedule, week = 3, today = 2, now = LocalDateTime.of(2026, 9, 15, 9, 0))
        assertEquals(LocalDateTime.of(2026, 9, 16, 0, 0), at)
    }

    @Test
    fun nextRefreshAtIsAlwaysInFuture() {
        // 刚过某边界 1 秒：不能把闹钟排在"过去"，否则会立刻连环触发
        val at = WidgetRefreshScheduler.nextRefreshAt(schedule, week = 3, today = 1, now = LocalDateTime.of(2026, 9, 14, 9, 40, 1))
        org.junit.Assert.assertTrue("下次刷新必须晚于当前时间", at.isAfter(LocalDateTime.of(2026, 9, 14, 9, 40, 1)))
    }
}
