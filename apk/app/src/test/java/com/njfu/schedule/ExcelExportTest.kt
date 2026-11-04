package com.njfu.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipInputStream

/**
 * Excel 导出回归：网格表 / 课程清单表的字段完整性（课名/老师/教室/节次/周次/学分一个不少），
 * 缺失字段用「——」占位不编造；.xlsx 包结构合法（zip 条目齐全、XML 转义正确）。
 */
class ExcelExportTest {

    private val schedule = Schedule(
        term = "2026-2027-1",
        courses = listOf(
            Course(id = "a", name = "高等数学", teacher = "张三", location = "教五楼 50320",
                day = 1, startPeriod = 1, endPeriod = 2, weeks = (1..16).toList(), credit = "4.0"),
            Course(id = "b", name = "体育(4)", teacher = "", location = "",
                day = 3, startPeriod = 5, endPeriod = 6, weeks = listOf(1, 3, 5, 7)),
            // 整周实习（无学分数据是教务的常态，导出也要显示——而不是编 0）
            Course(id = "c", name = "专业实习", teacher = "李四", location = "",
                day = 0, startPeriod = 1, endPeriod = 11, weeks = listOf(17, 18), kind = "intern"),
            // 课名带 XML 特殊字符，验证转义
            Course(id = "d", name = "数<据&库>", teacher = "王五", location = "一教 101",
                day = 5, startPeriod = 3, endPeriod = 3, weeks = (1..20).toList(), credit = "2.5",
                note = "带上教材")
        )
    )

    @Test
    fun gridHeaderHasDaysAndTimes() {
        val rows = ExcelExport.gridRows(schedule, CampusTimes.times("xz"))
        assertEquals(12, rows.size) // 表头 + 11 节
        assertEquals(listOf("节次", "时间", "周一", "周二", "周三", "周四", "周五", "周六", "周日"), rows[0])
        assertEquals("08:00-08:45", rows[1][1])
        assertEquals("20:10-20:55", rows[11][1])
    }

    @Test
    fun gridCellShowsNameTeacherLocationTopDown() {
        val rows = ExcelExport.gridRows(schedule, CampusTimes.times("xz"))
        // 高数 周一 1-2 节：只写在第 1 节那行，跨节不重复
        val cell = rows[1][2]
        assertEquals("高等数学\n老师：张三\n教室：教五楼 50320", cell)
        assertEquals("", rows[2][2])
    }

    @Test
    fun gridMissingTeacherShowsPlaceholder() {
        val rows = ExcelExport.gridRows(schedule, CampusTimes.times("xz"))
        val cell = rows[5][4] // 周三 14:00 第 5 节
        assertEquals("体育(4)\n老师：——", cell)
    }

    @Test
    fun listRowContainsAllFields() {
        val rows = ExcelExport.listRows(schedule)
        assertEquals(
            listOf("课程名称", "老师", "教室", "星期", "节次", "周次", "学分", "备注", "类别"),
            rows[0]
        )
        assertEquals(5, rows.size) // 表头 + 4 门课
        // day=0（整周实习）排在最前，其余按 星期→节次：rows[1]=实习 rows[2]=高数 rows[3]=体育
        val intern = rows[1]
        assertEquals("整周", intern[3])
        assertEquals("整周安排", intern[4])
        assertEquals("17-18周", intern[5])
        assertEquals("实习", intern[8])
        val math = rows[2]
        assertEquals("高等数学", math[0])
        assertEquals("张三", math[1])
        assertEquals("教五楼 50320", math[2])
        assertEquals("周一", math[3])
        assertEquals("第1-2节", math[4])
        assertEquals("1-16周", math[5])
        assertEquals("4.0", math[6])
        assertEquals("理论课", math[8])
        // 没老师的课：不编造，填「——」让用户自己补
        val pe = rows[3]
        assertEquals(ExcelExport.MISSING, pe[1])
        assertEquals(ExcelExport.MISSING, pe[2])
        assertEquals(ExcelExport.MISSING, pe[6]) // 教务没爬到的学分也是「——」
    }

    @Test
    fun listRowsSortedByDayThenPeriod() {
        val rows = ExcelExport.listRows(schedule)
        // 整周实习（day=0）在最前，理论课按 星期→节次
        assertEquals(listOf("专业实习", "高等数学", "体育(4)", "数<据&库>"),
            rows.drop(1).map { it[0] })
    }

    @Test
    fun workbookZipStructureIsValid() {
        val bytes = ExcelExport.buildWorkbook(schedule, "xz")
        val names = mutableListOf<String>()
        val sheet1 = StringBuilder()
        ZipInputStream(bytes.inputStream()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                names.add(e.name)
                if (e.name == "xl/worksheets/sheet1.xml") sheet1.append(z.readBytes().toString(Charsets.UTF_8))
                e = z.nextEntry
            }
        }
        for (required in listOf(
            "[Content_Types].xml", "_rels/.rels", "xl/workbook.xml",
            "xl/_rels/workbook.xml.rels", "xl/styles.xml",
            "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml"
        )) assertTrue("缺少 $required", required in names)
        val x1 = sheet1.toString()
        // 课名里的 XML 特殊字符已转义、多行文字保留
        assertTrue("数&lt;据&amp;库&gt;" in x1)
        assertTrue("\n老师：张三" in x1)
        // 单元格引用从 A1 开始、表头带样式
        assertTrue("""<c r="A1" t="inlineStr"""" in x1)
    }

    @Test
    fun suggestedFileNameStripsIllegalChars() {
        assertEquals("南林课程表-2026-2027-1.xlsx", ExcelExport.suggestedFileName(schedule))
        assertEquals("南林课程表-未命名学期.xlsx", ExcelExport.suggestedFileName(Schedule()))
    }
}
