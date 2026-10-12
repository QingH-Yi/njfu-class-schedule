package com.njfu.schedule

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Repository.editCourse 的回归：整周安排（实习/实验/设计，day=0）
 * 设了具体时段 → 变成普通课（进上面的网格）；不设 → 保持整周。
 * 编辑弹窗现在对 day=0 也显示时段选择器，这条逻辑必须稳。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RepositoryEditTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun internCourse() = Course(
        id = "i1", name = "专业实习", teacher = "蒋小川", location = "",
        day = 0, startPeriod = 1, endPeriod = 11, weeks = listOf(7, 8, 9)
    )

    @Before
    fun setup() {
        Repository.init(ctx)
        Repository.saveSchedule(Schedule("2026-2027-1", "2026-08-31", "xz", listOf(internCourse())))
    }

    @Test
    fun internWithSlotsBecomesNormalCourse() {
        // 给整周实习选了 周三 第3-4节 → 应变成 day=3 的普通课，不再有 day=0
        Repository.editCourse(
            internCourse(), name = "专业实习", teacher = "蒋小川", location = "实验楼-201",
            slots = listOf(Triple(3, 3, 4)), weeks = listOf(7, 8, 9),
            onlyThisWeek = false, currentWeek = 7
        )
        val courses = Repository.loadSchedule().courses
        assertTrue("不该再有整周安排", courses.none { it.day == 0 })
        val c = courses.single()
        assertEquals(3, c.day)
        assertEquals(3, c.startPeriod)
        assertEquals(4, c.endPeriod)
        assertEquals("实验楼-201", c.location)
        assertTrue("改过应标 edited", c.edited)
    }

    @Test
    fun internWithoutSlotsStaysWholeWeek() {
        // 不选时段 → 保持 day=0 整周，只改名字/老师
        Repository.editCourse(
            internCourse(), name = "专业实习(改)", teacher = "蒋小川", location = "",
            slots = emptyList(), weeks = listOf(7, 8, 9),
            onlyThisWeek = false, currentWeek = 7
        )
        val c = Repository.loadSchedule().courses.single()
        assertEquals(0, c.day)
        assertEquals("专业实习(改)", c.name)
        assertEquals(1, c.startPeriod)
        assertEquals(11, c.endPeriod)
    }

    @Test
    fun internMultipleSlotsSplitIntoMultipleCourses() {
        // 选两个不连续时段 → 拆成两条普通课
        Repository.editCourse(
            internCourse(), name = "专业实习", teacher = "蒋小川", location = "",
            slots = listOf(Triple(1, 1, 2), Triple(5, 7, 8)), weeks = listOf(9),
            onlyThisWeek = false, currentWeek = 9
        )
        val courses = Repository.loadSchedule().courses
        assertEquals(2, courses.size)
        assertFalse(courses.any { it.day == 0 })
        assertTrue(courses.any { it.day == 1 && it.startPeriod == 1 && it.endPeriod == 2 })
        assertTrue(courses.any { it.day == 5 && it.startPeriod == 7 && it.endPeriod == 8 })
    }
}
