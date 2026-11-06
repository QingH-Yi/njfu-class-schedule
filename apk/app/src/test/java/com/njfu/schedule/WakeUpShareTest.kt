package com.njfu.schedule

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WakeUp 分享口令互通的纯逻辑测试（不发网络请求）：
 * 口令文本的生成形状、解析回来是否等价、间断周怎么拆/怎么合、以及从整段分享消息里取口令。
 */
class WakeUpShareTest {

    private fun sample(): Schedule = Schedule(
        term = "2026-2027-1",
        termStartMonday = "2026-08-31",
        campus = "xz",
        courses = listOf(
            Course(
                id = "a", name = "高等数学", teacher = "张三", location = "教五楼 50320",
                day = 1, startPeriod = 1, endPeriod = 2, weeks = (1..16).toList()
            ),
            Course(
                id = "b", name = "体育,④", teacher = "", location = "",
                day = 5, startPeriod = 3, endPeriod = 4, weeks = listOf(1, 3, 5, 7, 9)
            ),
            Course(
                id = "c", name = "专业实习", teacher = "李四", location = "",
                day = 0, startPeriod = 1, endPeriod = 11, weeks = listOf(17, 18), kind = "intern"
            )
        )
    )

    @Test
    fun scheduleFileHasSixLinesAndValidJson() {
        val text = WakeUpShare.buildScheduleFile(sample())
        val lines = text.split("\n")
        // 6 行内容 + 末尾换行产生的空串
        assertEquals(7, lines.size)
        assertEquals("", lines[5])
        assertEquals("", lines[6])

        val conf = JsonParser.parseString(lines[0]).asJsonObject
        assertEquals(1, conf.get("id").asInt)
        assertEquals(50, conf.get("courseLen").asInt)

        val attr = JsonParser.parseString(lines[2]).asJsonObject
        assertTrue(attr.get("startDate").asString.matches(Regex("^\\d{4}-\\d{1,2}-\\d{1,2}$")))
        // 不补零
        assertEquals("2026-8-31", attr.get("startDate").asString)
    }

    @Test
    fun internCourseIsSkippedAndRealCoursesKept() {
        val text = WakeUpShare.buildScheduleFile(sample())
        val lines = text.split("\n")
        val info = JsonParser.parseString(lines[3]).asJsonArray
        // 只保留 day 1..7 的课：高等数学 + 体育,④
        assertEquals(2, info.size())
        val names = info.map { it.asJsonObject.get("courseName").asString }
        assertTrue(names.contains("高等数学"))
        assertTrue(names.contains("体育,④"))
        assertTrue(!names.contains("专业实习"))
    }

    @Test
    fun gappedWeeksAreSplitIntoContinuousSegments() {
        val s = Schedule(
            term = "t", termStartMonday = "2026-08-31", campus = "xz",
            courses = listOf(
                Course(id = "b", name = "体育", teacher = "", location = "",
                    day = 5, startPeriod = 3, endPeriod = 4, weeks = listOf(1, 2, 3, 7, 9, 10))
            )
        )
        val times = JsonParser.parseString(WakeUpShare.buildScheduleFile(s).split("\n")[4]).asJsonArray
        // 1-3、7-7、9-10 → 三段
        assertEquals(3, times.size())
        val segs = times.map { it.asJsonObject.get("startWeek").asInt to it.asJsonObject.get("endWeek").asInt }
        assertEquals(listOf(1 to 3, 7 to 7, 9 to 10), segs)
    }

    @Test
    fun weekSegmentsEdges() {
        assertEquals(emptyList<List<Int>>(), WakeUpShare.weekSegments(emptyList()))
        assertEquals(listOf(listOf(1)), WakeUpShare.weekSegments(listOf(1)))
        assertEquals(
            listOf(listOf(1, 2, 3), listOf(20)),
            WakeUpShare.weekSegments(listOf(1, 2, 3, 20))
        )
        assertEquals(
            listOf(listOf(1), listOf(3), listOf(5)),
            WakeUpShare.weekSegments(listOf(1, 3, 5))
        )
    }

    @Test
    fun parseScheduleFileRoundTripsCourses() {
        val s = sample()
        val text = WakeUpShare.buildScheduleFile(s)
        val (parsed, skipped) = WakeUpShare.parseScheduleFile(text)
        assertEquals(0, skipped)
        // 整周实习不进 WakeUp，所以回来是 2 门
        assertEquals(2, parsed.size)

        val math = parsed.first { it.name == "高等数学" }
        assertEquals(1, math.day)
        assertEquals(1, math.startPeriod)
        assertEquals(2, math.endPeriod)
        assertEquals("张三", math.teacher)
        assertEquals("教五楼 50320", math.location)
        assertEquals((1..16).toList(), math.weeks)
        assertTrue(math.edited)

        val pe = parsed.first { it.name == "体育,④" }
        assertEquals(5, pe.day)
        assertEquals(3, pe.startPeriod)
        assertEquals(4, pe.endPeriod)
        // 间断周合并回一条
        assertEquals(listOf(1, 3, 5, 7, 9), pe.weeks)
    }

    @Test
    fun parseScheduleFileMergesDuplicateSegments() {
        val text = WakeUpShare.buildScheduleFile(
            Schedule(
                term = "t", termStartMonday = "2026-08-31", campus = "xz",
                courses = listOf(
                    Course(id = "x", name = "线性代数", teacher = "王五", location = "A101",
                        day = 3, startPeriod = 5, endPeriod = 6, weeks = listOf(2, 3, 8, 9))
                )
            )
        )
        val (parsed, _) = WakeUpShare.parseScheduleFile(text)
        assertEquals(1, parsed.size)
        assertEquals(listOf(2, 3, 8, 9), parsed[0].weeks)
    }

    @Test
    fun parseScheduleFileRejectsGarbage() {
        assertEquals(0, WakeUpShare.parseScheduleFile("abc").first.size)
        assertEquals(0, WakeUpShare.parseScheduleFile("a\nb\nc\nd\ne").first.size)
    }

    @Test
    fun wakeDateStripsLeadingZeros() {
        assertEquals("2026-8-31", WakeUpShare.wakeDate("2026-08-31"))
        assertEquals("2026-12-1", WakeUpShare.wakeDate("2026-12-01"))
        // 已经是 WakeUp 格式就原样返回
        assertEquals("2026-8-31", WakeUpShare.wakeDate("2026-8-31"))
    }

    @Test
    fun timeFallsBackToCampusTimetable() {
        val text = WakeUpShare.buildScheduleFile(sample())
        val times = JsonParser.parseString(text.split("\n")[4]).asJsonArray.map { it.asJsonObject }
        // 高等数学第 1-2 节：新庄校区 08:00 开始、09:40 结束
        val math = times.first { it.get("day").asInt == 1 && it.get("startNode").asInt == 1 }
        assertEquals("08:00", math.get("startTime").asString)
        assertEquals("09:40", math.get("endTime").asString)
        // 体育第 3-4 节：10:00-11:40
        val pe = times.first { it.get("day").asInt == 5 && it.get("startNode").asInt == 3 }
        assertEquals("10:00", pe.get("startTime").asString)
        assertEquals("11:40", pe.get("endTime").asString)
    }
}
