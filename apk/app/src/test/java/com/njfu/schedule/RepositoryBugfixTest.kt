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
 * v2.4.2 二轮排查的 bug 复现（先红后绿）：
 * 1) 后台重抓会重新生成课程 id —— 编辑弹窗开着时数据被覆盖，点保存按 id 找不到课 → 静默丢失。
 *    修复：按 id 找不到时回退按 名称|星期|起始节 匹配（编辑/删除/备忘录同享）。
 * 2) 用户把课挪了节次（edited）→ 重抓 keepEdited 只挡同位置 → 教务旧位置的"幽灵课"被导回。
 *    修复：keepEdited 时，同名同星期且周次有交集的教务课不导入（单双周老师不同周次不交集的不受影响）。
 * 3) 课表 JSON 损坏 → loadSchedule 返回空 → 后续任何 saveSchedule（如切校区）把空课表写回 = 清库。
 *    修复：解析失败备份坏 JSON，并返回最近一次成功解析的课表。
 * 4) weekOverride 手填越界（如 99）→ 界面显示"第 99 周"。修复：不在 1..53 视为自动。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RepositoryBugfixTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private val math = Course("g1", "高等数学", "张三", "50320", 1, 1, 2, (1..16).toList())
    private val english = Course("e1", "大学英语", "李四", "50308", 2, 3, 4, (1..16).toList())

    @Before
    fun setup() {
        Repository.init(ctx)
        Repository.saveSchedule(Schedule("2026-2027-1", "2026-08-31", "xz", listOf(math, english)))
        Repository.forgetDeletedKeys(Repository.deletedKeys())
    }

    // ---- 1) 重抓换 id 后，编辑/删除/备忘录不能静默丢失 ----

    @Test
    fun editCourseFallsBackToKeyWhenIdRegenerated() {
        // 模拟：编辑弹窗开着，后台重抓把 id 换了 → 手里的 course 引用还是旧 id
        val stale = math.copy(id = "regenerated")
        Repository.editCourse(
            stale, "高等数学(改)", "张三", "50320",
            slots = listOf(Triple(1, 1, 2)), weeks = (1..16).toList(),
            onlyThisWeek = false, currentWeek = 7
        )
        val c = Repository.loadSchedule().courses.first { it.day == 1 }
        assertEquals("旧 id 也必须能找到课（按 名称|星期|起始节 兜底）", "高等数学(改)", c.name)
        assertTrue(c.edited)
    }

    @Test
    fun deleteCourseFallsBackToKeyWhenIdRegenerated() {
        val stale = math.copy(id = "regenerated")
        Repository.deleteCourse(stale, onlyThisWeek = false, currentWeek = 7, syncSameName = false)
        assertFalse(
            "旧 id 引用也要能删掉课",
            Repository.loadSchedule().courses.any { it.day == 1 && it.startPeriod == 1 }
        )
    }

    @Test
    fun updateCourseMetaFallsBackToKeyWhenIdRegenerated() {
        val stale = math.copy(id = "regenerated")
        Repository.updateCourseMeta(stale, "下周考试", "")
        // 修复后：按 id 找不到 → 按 key 匹配到 math，备忘录写进去
        val c = Repository.loadSchedule().courses.first { it.day == 1 }
        assertEquals("备忘录不能因为换 id 而丢", "下周考试", c.note)
    }

    // ---- 2) 挪过节次的课，重抓不能把教务旧位置的"幽灵课"导回来 ----

    @Test
    fun movedCourseNotResurrectedAtOldSlotOnReimport() {
        // 用户把高数从周一 1-2 挪到周一 3-4（edited=true）
        Repository.saveSchedule(
            Schedule(
                "2026-2027-1", "2026-08-31", "xz",
                listOf(math.copy(startPeriod = 3, endPeriod = 4, edited = true), english)
            )
        )
        // 后台重抓：教务还是周一 1-2
        Repository.replaceCourses(
            listOf(math, english), "2026-2027-1", keepEdited = true
        )
        val slots = Repository.loadSchedule().courses.map { it.day to it.startPeriod }
        assertFalse(
            "教务旧位置的幽灵课不能导回来（否则新旧两个位置都有课）",
            (1 to 1) in slots
        )
        assertTrue("用户挪到的新位置要保留", (1 to 3) in slots)
        assertTrue("没动过的课正常导入", (2 to 3) in slots)
    }

    @Test
    fun differentTeacherOddEvenWeeksNotTreatedAsPhantom() {
        // 单双周不同老师的同名课：周次不交集，不能被当幽灵误杀
        Repository.saveSchedule(
            Schedule(
                "2026-2027-1", "2026-08-31", "xz",
                listOf(
                    math.copy(weeks = (1..16).filter { it % 2 == 1 }, edited = true), // 单周 张三
                    math.copy(id = "g2", teacher = "李四", weeks = (1..16).filter { it % 2 == 0 }) // 双周 李四
                )
            )
        )
        Repository.replaceCourses(
            listOf(
                math.copy(weeks = (1..16).filter { it % 2 == 1 }),
                math.copy(id = "n2", teacher = "李四", weeks = (1..16).filter { it % 2 == 0 })
            ), "2026-2027-1", keepEdited = true
        )
        val teachers = Repository.loadSchedule().courses.filter { it.day == 1 }.map { it.teacher }.toSet()
        assertEquals("单双周两个老师的同名课都要在", setOf("张三", "李四"), teachers)
    }

    // ---- 3) 课表 JSON 损坏：不能返回空课表，更不能被后续保存清库 ----

    @Test
    fun corruptScheduleJsonKeepsLastGoodData() {
        val sp = ctx.getSharedPreferences("njfu_schedule", Context.MODE_PRIVATE)
        Repository.loadSchedule() // 先正常读一次（填充"最近一次成功解析"缓存）
        // 模拟存储被写坏
        sp.edit().putString("schedule", "{\"term\":\"broken\"").apply()
        val s = Repository.loadSchedule()
        assertTrue(
            "解析失败要返回最近一次成功解析的课表（而不是空）",
            s.courses.size == 2
        )
        // 用户此时切校区/保存设置 → 不能把空课表写回去
        Repository.saveSchedule(s.copy(campus = "bm"))
        assertEquals("保存设置不能清掉课表", 2, Repository.loadSchedule().courses.size)
        assertEquals("bm", Repository.loadSchedule().campus)
        // 坏 JSON 要留底供找回
        assertTrue(sp.getString("schedule_corrupt", "")!!.contains("broken"))
    }

    @Test
    fun freshEmptyStorageStillReturnsEmptySchedule() {
        val sp = ctx.getSharedPreferences("njfu_schedule", Context.MODE_PRIVATE)
        sp.edit().clear().apply()
        assertEquals("全新安装没有课表是正常的", 0, Repository.loadSchedule().courses.size)
    }

    // ---- 4) weekOverride 越界 ----

    @Test
    fun weekOverrideOutOfRangeFallsBackToAuto() {
        Repository.weekOverride = 99
        val w = resolveCurrentWeek("2026-08-31")
        assertTrue("越界的手动周次应回退为自动推算，得到 1..53，实际 $w", w in 1..53)
        Repository.weekOverride = 0
        val auto = resolveCurrentWeek("2026-08-31")
        assertTrue(auto in 1..53)
    }
}
