package com.njfu.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * 小组件纯逻辑回归：当前周该显示哪些课、正在上哪门、下一节课是哪门、今日还剩几节、下次何时刷新。
 * 小组件渲染测不了（Glance 需真机/模拟器），所以把"算得对不对"全压在这里。
 *
 * 作息（新庄）：第1节 08:00-08:45，第2节 08:55-09:40，第3节 10:00-10:45，第4节 10:55-11:40，
 *              第7节 15:55-16:40，第8节 16:45-17:30。
 * 注意：Course 的 startPeriod/endPeriod 是"节次区间"，连堂课的结束时间是 endPeriod 的结束时刻。
 *   - 高数 1-2 节  → 08:00-09:40
 *   - 英语 3-4 节  → 10:00-11:40
 *   - 体育 7-8 节  → 15:55-17:30
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
        // 周一 07:00：高数(08:00-09:40)还没开始 → 下一节是高数
        val n = nextClass(schedule, week = 3, today = 1, now = LocalTime.of(7, 0))
        assertEquals("高等数学", n?.name)
        assertEquals("08:00", n?.startTime)
        assertEquals("09:40", n?.endTime)
        assertEquals("50101", n?.location)
        assertTrue("07:00 还没开始，ongoing 应为 false", !n!!.ongoing)
    }

    @Test
    fun nextClassShowsOngoingClassWhileInSession() {
        // 周一 08:30：高数 08:00-09:40 正在上 → 小卡片应显示"正在上"，返回这门课且 ongoing=true
        val n = nextClass(schedule, week = 3, today = 1, now = LocalTime.of(8, 30))
        assertEquals("高等数学", n?.name)
        assertEquals("08:00", n?.startTime)
        assertTrue("08:30 时高数正在上，ongoing 应为 true", n!!.ongoing)

        // 周一 09:50：高数已下课(09:40)，英语(10:00)还没开始 → nextClass 回到"下一节=英语"，ongoing=false
        val n2 = nextClass(schedule, week = 3, today = 1, now = LocalTime.of(9, 50))
        assertEquals("大学英语", n2?.name)
        assertEquals("10:00", n2?.startTime)
        assertTrue(!n2!!.ongoing)
    }

    @Test
    fun currentClassDetectsOngoing() {
        // 08:30：高数 08:00-09:40 正在上
        val cur = currentClass(schedule, week = 3, today = 1, now = LocalTime.of(8, 30))
        assertEquals("高等数学", cur?.name)
        assertEquals("09:40", cur?.endTime)
        assertTrue(cur!!.ongoing)

        // 09:50：高数刚下课，英语（10:00）还没开始 → 没有"正在上"的课
        assertNull(currentClass(schedule, week = 3, today = 1, now = LocalTime.of(9, 50)))

        // 上课边界：第1节 08:00 上课那一刻算正在上（闭区间左端）
        assertEquals("高等数学", currentClass(schedule, week = 3, today = 1, now = LocalTime.of(8, 0))?.name)
        // 连堂结束边界：09:40 下课那一刻仍算正在上（闭区间右端）
        assertEquals("高等数学", currentClass(schedule, week = 3, today = 1, now = LocalTime.of(9, 40))?.name)

        // 现在未知
        assertNull(currentClass(schedule, week = 3, today = 1, now = null))
    }

    @Test
    fun currentClassIgnoresOtherWeeks() {
        // 体育只有第 5 周；第 7 周周一 16:00 不该出现
        assertNull(currentClass(schedule, week = 7, today = 1, now = LocalTime.of(16, 0)))
        // 第 5 周周一 16:00 体育 15:55-17:30 正在上
        assertEquals("体育", currentClass(schedule, week = 5, today = 1, now = LocalTime.of(16, 0))?.name)
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
    fun remainingTodayCountsNotYetFinished() {
        // 周一 07:00：一节没上 → 2
        assertEquals(2, remainingToday(schedule, week = 3, today = 1, now = LocalTime.of(7, 0)))
        // 08:30：高数正在上（09:40 才结束）→ 算"还剩"，剩 高数+英语 = 2
        assertEquals(2, remainingToday(schedule, week = 3, today = 1, now = LocalTime.of(8, 30)))
        // 09:50：高数已下课，英语未开始 → 1
        assertEquals(1, remainingToday(schedule, week = 3, today = 1, now = LocalTime.of(9, 50)))
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
    fun nextRefreshTimePicksNearestBoundary() {
        // 周一 09:00：高数 09:40 结束，英语 10:00 开始 → 最近边界是 09:40
        assertEquals(LocalTime.of(9, 40), nextRefreshTime(schedule, week = 3, today = 1, now = LocalTime.of(9, 0)))
        // 周一 10:30：英语 10:00-11:40 正在上 → 最近边界是它结束 11:40
        assertEquals(LocalTime.of(11, 40), nextRefreshTime(schedule, week = 3, today = 1, now = LocalTime.of(10, 30)))
        // 周一 12:00：今天课都上完了 → 无边界
        assertNull(nextRefreshTime(schedule, week = 3, today = 1, now = LocalTime.of(12, 0)))
        // 周一 21:00：已经没边界了
        assertNull(nextRefreshTime(schedule, week = 3, today = 1, now = LocalTime.of(21, 0)))
    }

    @Test
    fun courseKeyIsStableIncludingIdIndependentFields() {
        val c1 = courseKey(Course("x", "英语", "王", "502", 1, 3, 4, listOf(1)))
        assertEquals("英语|1|3", c1)
    }

    @Test
    fun isoDayMapsMondayTo1() {
        assertEquals(1, isoDay(java.time.LocalDate.of(2026, 8, 31)))  // 2026-08-31 是周一
        assertEquals(7, isoDay(java.time.LocalDate.of(2026, 9, 6)))   // 周日
    }
}
