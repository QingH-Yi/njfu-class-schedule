package com.njfu.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * 小组件纯逻辑回归：当前周该显示哪些课、下一节课是哪门、今日还剩几节。
 * 沙箱跑不了模拟器，小组件渲染测不了——所以把"算得对不对"全压在这里。
 */
class WidgetLogicTest {

    private fun c(
        id: String, name: String, day: Int, s: Int, e: Int,
        weeks: List<Int> = emptyList(), loc: String = "", teacher: String = "", kind: String = ""
    ) = Course(id, name, teacher, loc, day, s, e, weeks, kind = kind)

    private val schedule = Schedule(
        term = "2026-2027-1", termStartMonday = "2026-08-31", campus = "xz",
        courses = listOf(
            c("a", "高等数学", 1, 1, 2, weeks = listOf(1, 2, 3), loc = "50101", teacher = "张"),
            c("b", "大学英语", 1, 3, 4, loc = "50202"),              // 全学期
            c("c", "体育", 1, 7, 8, weeks = listOf(5)),              // 只有第 5 周
            c("d", "物理", 3, 1, 2),                                 // 周三
            c("e", "专业实习", 0, 1, 11, weeks = listOf(2))          // 整周安排（day=0）
        )
    )

    @Test
    fun todayCoursesFiltersByWeekAndDay() {
        // 第 3 周周一：高等数学(1-3周)+大学英语(全学期)；体育第5周不在；整周实习(第2周)不在
        val mon3 = todayCourses(schedule, week = 3, today = 1)
        assertEquals(listOf("高等数学", "大学英语"), mon3.map { it.name })

        // 第 5 周周一：高数已结束(1-3)，体育出现
        val mon5 = todayCourses(schedule, week = 5, today = 1)
        assertEquals(listOf("大学英语", "体育"), mon5.map { it.name })
    }

    @Test
    fun nextClassPicksFirstNotYetStarted() {
        // 周一 08:30（第1节 08:00 已过，第3节 10:00 未到）→ 大学英语
        val n = nextClass(schedule, week = 3, today = 1, now = LocalTime.of(8, 30))
        assertEquals("大学英语", n?.name)
        assertEquals("10:00", n?.startTime)
        assertEquals("50202", n?.location)
    }

    @Test
    fun nextClassBeforeFirstIsFirstClass() {
        val n = nextClass(schedule, week = 3, today = 1, now = LocalTime.of(7, 0))
        assertEquals("高等数学", n?.name)
        assertEquals("08:00", n?.startTime)
    }

    @Test
    fun nextClassNullWhenDayOverOrNoClass() {
        // 周一 21:00：所有课都开始了 → 没下一节
        assertNull(nextClass(schedule, week = 3, today = 1, now = LocalTime.of(21, 0)))
        // 周二没课
        assertNull(nextClass(schedule, week = 3, today = 2, now = LocalTime.of(9, 0)))
        // 现在时间未知
        assertNull(nextClass(schedule, week = 3, today = 1, now = null))
    }

    @Test
    fun remainingTodayCountsFromNow() {
        // 周一 08:30：高数(08:00)已开始但仍算"还剩"？—— 规则：start>=now 才算。
        // 08:00 已过 → 剩 大学英语(10:00) = 1
        assertEquals(1, remainingToday(schedule, week = 3, today = 1, now = LocalTime.of(8, 30)))
        // 07:00 一节没上 → 2
        assertEquals(2, remainingToday(schedule, week = 3, today = 1, now = LocalTime.of(7, 0)))
        // 21:00 全上完 → 0
        assertEquals(0, remainingToday(schedule, week = 3, today = 1, now = LocalTime.of(21, 0)))
    }

    @Test
    fun baiMaCampusShiftsTimes() {
        // 白马校区第 1 节 08:30；07:00 时下一节应是 08:30 开始的高数
        val bm = schedule.copy(campus = "bm")
        val n = nextClass(bm, week = 3, today = 1, now = LocalTime.of(7, 0))
        assertEquals("08:30", n?.startTime)
    }

    @Test
    fun isoDayMapsMondayTo1() {
        assertEquals(1, isoDay(java.time.LocalDate.of(2026, 8, 31)))  // 2026-08-31 是周一
        assertEquals(7, isoDay(java.time.LocalDate.of(2026, 9, 6)))   // 周日
    }
}
