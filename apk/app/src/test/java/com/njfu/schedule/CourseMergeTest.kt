package com.njfu.schedule

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * v2.3.1 新功能回归：
 * ① 同名课程竖向相邻合并（显示层，纯函数 mergeSameNameVertical）；
 * ② 同名课程同步修改（Repository.editCourseSameName + sameNameCoursesThisWeek）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CourseMergeTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun c(
        id: String, name: String, day: Int, start: Int, end: Int,
        weeks: List<Int> = listOf(1, 2, 3), teacher: String = "老师", loc: String = "教室"
    ) = Course(id, name, teacher, loc, day, start, end, weeks)

    // ---------- ① 竖向相邻合并 ----------

    @Test
    fun adjacentSameNameMergesIntoOne() {
        // 高数 周一 1-2 节 + 周一 3-4 节（同名、紧挨）→ 合成 1-4 一节大格
        val merged = mergeSameNameVertical(
            listOf(c("a", "高数", 1, 1, 2), c("b", "高数", 1, 3, 4))
        )
        assertEquals(1, merged.size)
        assertEquals(1, merged[0].startPeriod)
        assertEquals(4, merged[0].endPeriod)
        assertEquals("高数", merged[0].name)
    }

    @Test
    fun gapBetweenSameNameDoesNotMerge() {
        // 高数 1-2 与 5-6（中间空档）→ 不合并
        val merged = mergeSameNameVertical(
            listOf(c("a", "高数", 1, 1, 2), c("b", "高数", 1, 5, 6))
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun differentNameDoesNotMerge() {
        // 1-2 高数、3-4 英语（紧挨但不同名）→ 不合并
        val merged = mergeSameNameVertical(
            listOf(c("a", "高数", 1, 1, 2), c("b", "英语", 1, 3, 4))
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun threeConsecutiveSameNameMergesAll() {
        // 1-2 + 3-4 + 5-6 同名校 → 合成 1-6
        val merged = mergeSameNameVertical(
            listOf(c("a", "高数", 2, 1, 2), c("b", "高数", 2, 3, 4), c("c", "高数", 2, 5, 6))
        )
        assertEquals(1, merged.size)
        assertEquals(1, merged[0].startPeriod)
        assertEquals(6, merged[0].endPeriod)
    }

    @Test
    fun mergeIgnoresWhitespaceInName() {
        // "高数 " 与 "高数" 视为同名
        val merged = mergeSameNameVertical(
            listOf(c("a", "高数", 1, 1, 2), c("b", "高数 ", 1, 3, 4))
        )
        assertEquals(1, merged.size)
    }

    @Test
    fun overlappingSameNameDoesNotDoubleCount() {
        // 已有重叠：1-3 与 3-5（end+1 == start？3+? no: 3+1=4 != 3）→ 不合并（避免把重叠课叠一起）
        val merged = mergeSameNameVertical(
            listOf(c("a", "高数", 1, 1, 3), c("b", "高数", 1, 3, 5))
        )
        assertEquals(2, merged.size)
    }

    // ---------- ② 同名课程同步修改 ----------

    @Before
    fun setup() {
        Repository.init(ctx)
    }

    private fun seedThreeSameName() {
        // 本周（第 2 周）三门"高数"：周一 1-2、周三 3-4、周五 5-6
        Repository.saveSchedule(
            Schedule("2026-2027-1", "2026-08-31", "xz", listOf(
                c("h1", "高数", 1, 1, 2, teacher = "张", loc = "A101"),
                c("h2", "高数", 3, 3, 4, teacher = "张", loc = "A101"),
                c("h3", "高数", 5, 5, 6, teacher = "张", loc = "A101"),
                c("e1", "英语", 2, 1, 2)
            ))
        )
    }

    @Test
    fun sameNameCoursesThisWeekFindsAll() {
        seedThreeSameName()
        val s = Repository.loadSchedule()
        val target = s.courses.first { it.id == "h2" }
        val group = sameNameCoursesThisWeek(s, target, week = 2)
        assertEquals(3, group.size)
        assertTrue(group.all { it.name.trim() == "高数" })
    }

    @Test
    fun sameNameRenameSyncsAll() {
        seedThreeSameName()
        val s = Repository.loadSchedule()
        val target = s.courses.first { it.id == "h1" }
        // 改名 + 改教室，时段不变（slots 为空只改名称/老师/教室）
        Repository.editCourseSameName(
            target, name = "高等数学", teacher = "李", location = "B202",
            slots = emptyList(), weeks = listOf(1, 2, 3), onlyThisWeek = false, currentWeek = 2
        )
        val after = Repository.loadSchedule().courses
        val gaoshu = after.filter { it.name == "高等数学" }
        assertEquals("三节高数应一起改名", 3, gaoshu.size)
        assertTrue("教室应同步", gaoshu.all { it.location == "B202" })
        assertTrue("老师应同步", gaoshu.all { it.teacher == "李" })
        assertTrue("英语不受影响", after.any { it.name == "英语" })
        assertTrue("时段不变：仍有 1-2/3-4/5-6", gaoshu.any { it.day == 1 && it.startPeriod == 1 })
    }

    @Test
    fun sameNameSlotsSyncAll() {
        seedThreeSameName()
        val s = Repository.loadSchedule()
        val target = s.courses.first { it.id == "h1" }
        // 把整门课时段改成"周二 1-2 + 周四 7-8"
        Repository.editCourseSameName(
            target, name = "高数", teacher = "张", location = "A101",
            slots = listOf(Triple(2, 1, 2), Triple(4, 7, 8)),
            weeks = listOf(1, 2, 3), onlyThisWeek = false, currentWeek = 2
        )
        val gaoshu = Repository.loadSchedule().courses.filter { it.name == "高数" }
        assertEquals("两节高数", 2, gaoshu.size)
        assertTrue(gaoshu.any { it.day == 2 && it.startPeriod == 1 && it.endPeriod == 2 })
        assertTrue(gaoshu.any { it.day == 4 && it.startPeriod == 7 && it.endPeriod == 8 })
    }

    @Test
    fun syncFalseChangesOnlyOne() {
        seedThreeSameName()
        val s = Repository.loadSchedule()
        val target = s.courses.first { it.id == "h1" }
        // 走旧的单节编辑（不同步）：只改这一节（时段用原值，只改名）
        Repository.editCourse(
            target, name = "高等数学", teacher = "张", location = "A101",
            slots = listOf(Triple(1, 1, 2)), weeks = listOf(1, 2, 3), onlyThisWeek = false, currentWeek = 2
        )
        val after = Repository.loadSchedule().courses
        assertEquals("只有一节改名", 1, after.count { it.name == "高等数学" })
        assertEquals("其余两节仍是高数", 2, after.count { it.name == "高数" })
    }

    @Test
    fun syncThisWeekOnlyKeepsOtherWeeks() {
        // 高数在 1-4 周，周一 1-2；本周第 2 周只改本周时段
        Repository.saveSchedule(
            Schedule("2026-2027-1", "2026-08-31", "xz", listOf(
                c("h1", "高数", 1, 1, 2, weeks = listOf(1, 2, 3, 4)),
                c("h2", "高数", 3, 3, 4, weeks = listOf(1, 2, 3, 4))
            ))
        )
        val s = Repository.loadSchedule()
        val target = s.courses.first { it.id == "h1" }
        Repository.editCourseSameName(
            target, name = "高数", teacher = "张", location = "A101",
            slots = listOf(Triple(2, 5, 6)), weeks = listOf(2), onlyThisWeek = true, currentWeek = 2
        )
        val after = Repository.loadSchedule().courses
        // 第 2 周：两节都换成 周二 5-6
        assertTrue(after.any { it.day == 2 && it.startPeriod == 5 && it.weeks == listOf(2) })
        // 其余周次（1,3,4）仍在
        assertTrue(after.any { it.day == 1 && it.weeks.contains(1) && it.weeks.contains(3) })
        assertTrue(after.any { it.day == 3 && it.weeks.contains(1) && it.weeks.contains(4) })
    }

    @Test
    fun unionSlotsCollectsAll() {
        seedThreeSameName()
        val s = Repository.loadSchedule()
        val group = sameNameCoursesThisWeek(s, s.courses.first { it.id == "h1" }, 2)
        val u = unionSlots(group)
        assertTrue(u.contains(1 to 1)); assertTrue(u.contains(1 to 2))
        assertTrue(u.contains(3 to 3)); assertTrue(u.contains(3 to 4))
        assertTrue(u.contains(5 to 5)); assertTrue(u.contains(5 to 6))
    }
}
