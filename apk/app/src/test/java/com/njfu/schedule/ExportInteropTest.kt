package com.njfu.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 借鉴开源生态的两个导出格式回归：
 *  - WakeUp 课表标准 CSV（wakeup.fun/doc/import_from_csv.html）：导出可导入 WakeUp，导入可从别的 App 迁移，往返一致；
 *  - ICS 日历（ZFClassTable2ICS 思路）：每门课×每周一个事件，时间由三校区作息换算。
 */
class ExportInteropTest {

    private val schedule = Schedule(
        term = "2026-2027-1",
        termStartMonday = "2026-08-31",
        campus = "xz",
        courses = listOf(
            Course(id = "a", name = "高等数学", teacher = "张三", location = "教五楼 50320",
                day = 1, startPeriod = 1, endPeriod = 2, weeks = (1..16).toList()),
            Course(id = "b", name = "体育,④", teacher = "", location = "",
                day = 5, startPeriod = 3, endPeriod = 4, weeks = listOf(1, 3, 5, 7, 9)),
            // 整周实习：CSV/ICS 都不导出（网格交换格式没有"整周"这个位置）
            Course(id = "c", name = "专业实习", teacher = "李四", location = "",
                day = 0, startPeriod = 1, endPeriod = 11, weeks = listOf(17, 18), kind = "intern")
        )
    )

    // ---- WakeUp CSV ----

    @Test
    fun wakeWeeksTextFormats() {
        assertEquals("1-16", WakeCsv.weeksToWakeText((1..16).toList()))
        // 全单周 → 末尾「单」；全双周 → 「双」
        assertTrue(WakeCsv.weeksToWakeText(listOf(1, 3, 5)).endsWith("单"))
        assertTrue(WakeCsv.weeksToWakeText(listOf(2, 4, 6)).endsWith("双"))
        // 间断周用中文顿号
        assertEquals("1-3、9-10", WakeCsv.weeksToWakeText(listOf(1, 2, 3, 9, 10)))
        // 空周次（=全学期语义）导出为 1-20，WakeUp 周数不许为空
        assertEquals("1-20", WakeCsv.weeksToWakeText(emptyList()))
    }

    @Test
    fun csvExportFollowsWakeUpFormat() {
        val csv = WakeCsv.buildCsv(schedule)
        assertTrue("UTF-8 BOM 让 Excel/WakeUp 识别中文", csv.startsWith("\uFEFF"))
        val lines = csv.removePrefix("\uFEFF").split("\r\n").filter { it.isNotBlank() }
        assertEquals("课程名称,星期,开始节数,结束节数,老师,地点,周数", lines[0])
        // 整周实习不导出，其余按 星期→节次 排序
        assertEquals(2, lines.size - 1)
        assertEquals("高等数学,1,1,2,张三,教五楼 50320,1-16", lines[1])
        // 没有的老师/教室写「无」；课名含逗号 → 整段加引号（标准 CSV 转义）；全单周末尾带「单」
        assertEquals("\"体育,④\",5,3,4,无,无,1、3、5、7、9单", lines[2])
    }

    @Test
    fun csvRoundTripPreservesCourses() {
        val (courses, skipped) = WakeCsv.parseCsv(WakeCsv.buildCsv(schedule))
        assertEquals("无无效行", 0, skipped)
        assertEquals(2, courses.size)
        val math = courses.first { it.name == "高等数学" }
        assertEquals(1, math.day); assertEquals(1, math.startPeriod); assertEquals(2, math.endPeriod)
        assertEquals("张三", math.teacher)
        assertEquals("教五楼 50320", math.location)
        assertEquals((1..16).toList(), math.weeks)
        val pe = courses.first { it.name.startsWith("体育") }
        assertEquals("「无」转回空串（不编造，进 App 显——）", "", pe.teacher)
        assertEquals("", pe.location)
        assertEquals(listOf(1, 3, 5, 7, 9), pe.weeks)
    }

    @Test
    fun csvImportParsesWakeUpDocExample() {
        // WakeUp 官方文档示例（含引号字段、CRLF、顿号周次、单双周）
        val csv = "\uFEFF课程名称,星期,开始节数,结束节数,老师,地点,周数\r\n" +
                "高等数学,1,1,2,张三,逸夫楼101,1-5、7-11单\r\n" +
                "大学计算机,5,1,2,无,无,1-16\r\n" +
                "有机化学,4,3,4,\"李,四\",逸夫楼108,1-5\r\n" +
                "坏行,9,1,2,张三,101,1-16\r\n" +          // 星期 9 越界 → 跳过
                "缺周数,2,1,2,张三,101,\r\n"              // 周数为空 → 跳过
        val (courses, skipped) = WakeCsv.parseCsv(csv)
        assertEquals(3, courses.size)
        assertEquals(2, skipped)
        val math = courses.first { it.name == "高等数学" }
        assertEquals(listOf(1, 3, 5, 7, 9, 11), math.weeks)
        assertEquals("", courses.first { it.name == "大学计算机" }.teacher)
        assertEquals("李,四", courses.first { it.name == "有机化学" }.teacher)
    }

    // ---- ICS ----

    @Test
    fun icsExpandsEachWeekToAnEvent() {
        val ics = IcsExport.buildIcs(schedule, "xz")
        assertTrue(ics.startsWith("BEGIN:VCALENDAR"))
        assertTrue(ics.endsWith("END:VCALENDAR\r\n"))
        // 高数 16 周 + 体育 5 周 = 21 个事件（整周实习不生成）
        assertEquals(21, Regex("BEGIN:VEVENT").findAll(ics).count())
        // 第一周周一（2026-08-31）第 1-2 节（新庄 08:00-09:40）
        assertTrue("DTSTART:20260831T080000" in ics)
        assertTrue("DTEND:20260831T094000" in ics)
        // UID 按课×周唯一
        assertTrue("UID:a-w1@njfu-class-schedule" in ics)
        assertTrue("UID:a-w16@njfu-class-schedule" in ics)
    }

    @Test
    fun icsEscapesCommasAndSemicolons() {
        val s = Schedule("2026-2027-1", "2026-08-31", "xz", listOf(
            Course("d", "课;名,测试", "师", "教101", 2, 1, 2, listOf(3))
        ))
        val ics = IcsExport.buildIcs(s, "xz")
        assertTrue("SUMMARY:课\\;名\\,测试" in ics)
        // 第 3 周周二 = 2026-09-15（第一周周一 2026-08-31 + (3-1)*7 + 1 天）
        assertTrue("DTSTART:20260915T080000" in ics)
    }

    @Test
    fun icsBadTermStartYieldsEmpty() {
        assertEquals("", IcsExport.buildIcs(Schedule("t", "bad-date", "xz", emptyList()), "xz"))
    }
}
