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
 * 删除课程 + 显示稳定性回归。
 *
 * 背景（用户报"课程显示混乱，有时有有时没有"）：
 * 1) 旧版"仅本周编辑"把其余周次拆出来的课没标 edited → 重抓时被教务完整周次覆盖回去，
 *    和拆出的那条重叠 → 网格里一条被吞，看起来时有时无；
 * 2) 同位置重复条目在网格里只显示一条；
 * 3) 手动加的课重抓后被悄悄清掉。
 * 删除功能本身：整学期删除要能扛住重抓（不导回），仅本周删除只拆周次。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RepositoryDeleteTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun seed(vararg extra: Course) {
        Repository.init(ctx)
        Repository.saveSchedule(
            Schedule(
                "2026-2027-1", "2026-08-31", "xz",
                listOf(
                    Course("g1", "高等数学", "张三", "教五楼 50320", 1, 1, 2, (1..16).toList()),
                    Course("g2", "高等数学", "张三", "教五楼 50320", 3, 3, 4, (1..16).toList()),
                    Course("e1", "大学英语", "李四", "50308", 2, 3, 4, (1..16).toList())
                ) + extra
            )
        )
    }

    @Before
    fun cleanTombstones() {
        Repository.init(ctx)
        Repository.forgetDeletedKeys(Repository.deletedKeys())
    }

    // ---- unionSameSlotCourses：同位置重复课合并周次 ----

    @Test
    fun duplicateSameSlotCoursesUnionWeeks() {
        // 历史重复：同位置两条，一条 1-16 周、一条只有第 5 周（旧 bug 遗留）
        val fixed = unionSameSlotCourses(
            listOf(
                Course("a1", "高等数学", "张三", "50320", 1, 1, 2, (1..16).toList(), edited = true),
                Course("a2", "高等数学", "张三", "50320", 1, 1, 2, listOf(5))
            )
        )
        assertEquals(1, fixed.size)
        assertEquals((1..16).toList(), fixed[0].weeks)
        assertTrue("合并后要保留 edited 标记", fixed[0].edited)
    }

    @Test
    fun differentTeacherSameSlotNotMerged() {
        // 老师不同可能是真的两门并排课，不能乱合
        val kept = unionSameSlotCourses(
            listOf(
                Course("a1", "高等数学", "张三", "50320", 1, 1, 2, (1..16).toList()),
                Course("a2", "高等数学", "李四", "50320", 1, 1, 2, (1..16).toList())
            )
        )
        assertEquals(2, kept.size)
    }

    // ---- deleteCourse：整学期 / 仅本周 / 同名联动 ----

    @Test
    fun deleteWholeSemesterRemovesCourse() {
        seed()
        val c = Repository.loadSchedule().courses.first { it.id == "g1" }
        Repository.deleteCourse(c, onlyThisWeek = false, currentWeek = 7, syncSameName = false)
        val names = Repository.loadSchedule().courses.map { it.id }
        assertFalse("整学期删除应移除该课", "g1" in names)
        assertTrue("别的课不该被误删", "g2" in names && "e1" in names)
    }

    @Test
    fun deleteThisWeekOnlySplitsWeeksAndMarksEdited() {
        seed()
        val c = Repository.loadSchedule().courses.first { it.id == "g1" }
        Repository.deleteCourse(c, onlyThisWeek = true, currentWeek = 5, syncSameName = false)
        val g1 = Repository.loadSchedule().courses.first { it.id == "g1" }
        assertEquals("1-4,6-16", formatWeeks(g1.weeks))
        assertTrue("拆剩的课要标 edited，否则重抓会覆盖回完整周次（显示混乱根因）", g1.edited)
    }

    @Test
    fun deleteThisWeekLastWeekRemovesCourse() {
        // 这门课只有第 5 周 → 仅本周删掉 = 整门移除
        seed(Course("s1", "晨训", "体育部", "操场", 5, 1, 2, listOf(5)))
        val c = Repository.loadSchedule().courses.first { it.id == "s1" }
        Repository.deleteCourse(c, onlyThisWeek = true, currentWeek = 5, syncSameName = false)
        assertFalse(
            "唯一周次删空后应整门移除",
            Repository.loadSchedule().courses.any { it.id == "s1" }
        )
    }

    @Test
    fun deleteThisWeekSyncSameNameRemovesWholeGroupWeek() {
        seed()
        val c = Repository.loadSchedule().courses.first { it.id == "g1" }
        Repository.deleteCourse(c, onlyThisWeek = true, currentWeek = 5, syncSameName = true)
        val courses = Repository.loadSchedule().courses
        assertEquals("1-4,6-16", formatWeeks(courses.first { it.id == "g1" }.weeks))
        assertEquals("本周同名课要一起拆", "1-4,6-16", formatWeeks(courses.first { it.id == "g2" }.weeks))
        assertFalse("别的课不能动", courses.first { it.id == "e1" }.edited)
    }

    @Test
    fun deleteWholeSemesterSyncSameNameRemovesGroup() {
        seed()
        val c = Repository.loadSchedule().courses.first { it.id == "g1" }
        Repository.deleteCourse(c, onlyThisWeek = false, currentWeek = 7, syncSameName = true)
        val ids = Repository.loadSchedule().courses.map { it.id }
        assertFalse("g1" in ids)
        assertFalse("同名 g2 应一起删", "g2" in ids)
        assertTrue("别的课保留", "e1" in ids)
    }

    // ---- 墓碑：整学期删除后重抓不导回 ----

    @Test
    fun deletedCourseNotReImportedOnKeepEdited() {
        seed()
        val c = Repository.loadSchedule().courses.first { it.id == "g1" }
        Repository.deleteCourse(c, onlyThisWeek = false, currentWeek = 7, syncSameName = false)
        // 后台自动更新路径：keepEdited=true（用户改过课表）
        Repository.replaceCourses(
            listOf(
                Course("n1", "高等数学", "张三", "教五楼 50320", 1, 1, 2, (1..16).toList()),
                Course("n2", "高等数学", "张三", "教五楼 50320", 3, 3, 4, (1..16).toList()),
                Course("n3", "大学英语", "李四", "50308", 2, 3, 4, (1..16).toList())
            ),
            "2026-2027-1", keepEdited = true
        )
        val slots = Repository.loadSchedule().courses.map { it.day to it.startPeriod }
        assertFalse("删掉的课重抓后不能回来", (1 to 1) in slots)
        assertTrue("没删的课要正常导入", (3 to 3) in slots)
    }

    @Test
    fun useJwxtDataResetsDeletedKeys() {
        seed()
        val c = Repository.loadSchedule().courses.first { it.id == "g1" }
        Repository.deleteCourse(c, onlyThisWeek = false, currentWeek = 7, syncSameName = false)
        // 手动导入时用户选"用教务的"：整表重置，已删记录一并清掉
        Repository.replaceCourses(
            listOf(Course("n1", "高等数学", "张三", "教五楼 50320", 1, 1, 2, (1..16).toList())),
            "2026-2027-1", keepEdited = false
        )
        assertTrue(
            "选'用教务的'应恢复教务数据",
            Repository.loadSchedule().courses.any { it.name == "高等数学" && it.day == 1 }
        )
        assertTrue("已删记录应被清空", Repository.deletedKeys().isEmpty())
    }

    @Test
    fun manualAddForgivesTombstone() {
        seed()
        val c = Repository.loadSchedule().courses.first { it.id == "g1" }
        Repository.deleteCourse(c, onlyThisWeek = false, currentWeek = 7, syncSameName = false)
        // 删了又手动加回来 = 用户要这门课
        Repository.addCourses(
            listOf(Course("m1", "高等数学", "张三", "教五楼 50320", 1, 1, 2, (1..20).toList())),
            ""
        )
        assertTrue(
            "手动加回的课重抓时不应再被墓碑挡掉",
            Repository.loadSchedule().courses.any { it.id == "m1" }
        )
    }

    // ---- 手动加的课要能扛住重抓 ----

    @Test
    fun manuallyAddedCourseSurvivesReImport() {
        seed()
        // 手动加课（AddCourseDialog 走 addCourses）
        Repository.addCourses(
            listOf(Course("m1", "自制课", "", "", 6, 9, 10, (1..20).toList())),
            ""
        )
        // 后台自动更新覆盖（keepEdited=true）
        Repository.replaceCourses(
            listOf(Course("n1", "高等数学", "张三", "教五楼 50320", 1, 1, 2, (1..16).toList())),
            "2026-2027-1", keepEdited = true
        )
        val manual = Repository.loadSchedule().courses.firstOrNull { it.id == "m1" }
        assertTrue("手动加的课不该被重抓清掉", manual != null)
        assertEquals(6, manual!!.day)
    }

    // ---- 重新抓取时检测已删课，弹窗询问 ----

    @Test
    fun tombstonedCoursesDetectedForReimportDialog() {
        seed()
        val c = Repository.loadSchedule().courses.first { it.id == "g1" }
        Repository.deleteCourse(c, onlyThisWeek = false, currentWeek = 7, syncSameName = false)
        // 抓回来的课表里 高数(周一) 又出现了 → 弹窗要知道该问什么
        val names = Repository.tombstonedCoursesIn(
            listOf(
                Course("n1", "高等数学", "张三", "教五楼 50320", 1, 1, 2, (1..16).toList()),
                Course("n2", "大学英语", "李四", "50308", 2, 3, 4, (1..16).toList())
            )
        )
        assertEquals(listOf("高等数学"), names)
        // 用户选"恢复导入"后（清掉墓碑），再检测应为空
        Repository.forgetDeletedKeys(Repository.deletedKeys())
        assertTrue(Repository.tombstonedCoursesIn(listOf(c)).isEmpty())
    }

    @Test
    fun restoredCourseKeepsTeacherFromJwxtAndCreditFromMeta() {
        // 删除 → 选"恢复导入"（keepEdited=false 重置）：老师来自教务、学分按位置保留
        seed(Course("q1", "大学物理", "王物理", "50401", 4, 1, 2, (1..16).toList()).copy(credit = "4.5"))
        val q1 = Repository.loadSchedule().courses.first { it.id == "q1" }
        Repository.deleteCourse(q1, onlyThisWeek = false, currentWeek = 7, syncSameName = false)
        Repository.replaceCourses(
            listOf(Course("n9", "大学物理", "教务新老师", "50401", 4, 1, 2, (1..16).toList())),
            "2026-2027-1", keepEdited = false
        )
        val restored = Repository.loadSchedule().courses.first { it.name == "大学物理" }
        assertEquals("恢复后老师用教务的", "教务新老师", restored.teacher)
        assertEquals("恢复后学分按原位置带回来", "4.5", restored.credit)
    }

    // ---- 编辑时老师/教室留空 = 保留原值，不误清 ----

    @Test
    fun editWithBlankTeacherKeepsOriginalTeacherAndRoom() {
        seed()
        val c = Repository.loadSchedule().courses.first { it.id == "g1" }
        // 只想改课名，老师/教室框清空了 → 不该把"张三/教五楼 50320"抹掉
        Repository.editCourse(
            c, "高等数学(甲)", "", "",
            slots = listOf(Triple(1, 1, 2)), weeks = (1..16).toList(),
            onlyThisWeek = false, currentWeek = 7
        )
        val edited = Repository.loadSchedule().courses.first { it.id == "g1" }
        assertEquals("高等数学(甲)", edited.name)
        assertEquals("老师留空应保留原值", "张三", edited.teacher)
        assertEquals("教室留空应保留原值", "教五楼 50320", edited.location)
    }

    // ---- 编辑回归：仅本周编辑后，其余周次那条必须标 edited ----

    @Test
    fun thisWeekEditMarksRemainingCourseEdited() {
        seed()
        val c = Repository.loadSchedule().courses.first { it.id == "e1" }
        // 仅本周（第 5 周）把大学英语改到第 9-10 节
        Repository.editCourse(
            c, "大学英语", "李四", "50308",
            slots = listOf(Triple(2, 9, 10)), weeks = listOf(5),
            onlyThisWeek = true, currentWeek = 5
        )
        val courses = Repository.loadSchedule().courses
        val remain = courses.first { it.id == "e1" }
        assertEquals("1-4,6-16", formatWeeks(remain.weeks))
        assertTrue("其余周次那条必须标 edited，否则重抓覆盖回完整周次 → 与本周拆出的课重叠", remain.edited)
        val moved = courses.first { it.weeks == listOf(5) }
        assertEquals(9 to 10, moved.startPeriod to moved.endPeriod)
    }
}
