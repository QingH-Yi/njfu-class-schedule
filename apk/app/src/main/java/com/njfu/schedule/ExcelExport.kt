package com.njfu.schedule

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 把课表导出成 Excel（.xlsx）。
 *
 * 纯 Kotlin 实现（手工拼 xlsx 的 XML + zip 打包），不依赖任何第三方库，
 * 因此 JVM 单测可以直接验证产物。
 *
 * 两个工作表：
 *  ①「课程表」：横轴星期（周一~周日）、纵轴节次（1~11 + 时间）的经典网格；
 *    格子里从上到下是 课程名 / 老师 / 教室，同名同时段（单双周等）的课竖着排在同一格。
 *  ②「课程清单」：一门课一行，列 = 课程名称/老师/教室/星期/节次/周次/学分/备注/类别，
 *    一门课的全部信息一个不少；教务里没有的老师/学分填「——」（不编造，用户可自行补）。
 */
object ExcelExport {

    /** 缺失字段的占位符（两个横线），提示用户自己补填，而不是编造数据 */
    const val MISSING = "——"

    /** 建议的导出文件名 */
    fun suggestedFileName(schedule: Schedule): String {
        val term = schedule.term.replace(Regex("[\\\\/:*?\"<>|]"), "").ifBlank { "未命名学期" }
        return "南林课程表-$term.xlsx"
    }

    /** 生成整个 .xlsx 文件的字节流 */
    fun buildWorkbook(schedule: Schedule, campus: String): ByteArray {
        val times = CampusTimes.times(campus)
        val grid = gridRows(schedule, times)
        val list = listRows(schedule)
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { zip ->
            put(zip, "[Content_Types].xml", CONTENT_TYPES)
            put(zip, "_rels/.rels", ROOT_RELS)
            put(zip, "xl/workbook.xml", workbookXml())
            put(zip, "xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            put(zip, "xl/styles.xml", STYLES)
            put(zip, "xl/worksheets/sheet1.xml", sheetXml(grid, gridColWidths))
            put(zip, "xl/worksheets/sheet2.xml", sheetXml(list, listColWidths))
        }
        return bo.toByteArray()
    }

    // ---- 表格内容（独立成纯函数，方便单测） ----

    /** 「课程表」网格：11 行 × (节次+时间+7天) 列 */
    fun gridRows(schedule: Schedule, times: Map<Int, Pair<String, String>>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        rows.add(listOf("节次", "时间") + (1..7).map { dayName(it) })
        for (p in 1..11) {
            val t = times[p]
            val row = mutableListOf("$p", if (t != null) "${t.first}-${t.second}" else "")
            for (d in 1..7) {
                // 落在本节的课：只写在时段的起始节那一格（跨节大格不在每节重复，与课表显示一致）
                val starting = schedule.courses.filter {
                    it.day == d && it.startPeriod == p && p <= it.endPeriod
                }
                row.add(starting.joinToString("\n———\n") { gridCellText(it) })
            }
            rows.add(row)
        }
        return rows
    }

    /** 网格一格的文字：课程名 / 老师 / 教室 从上到下，缺老师的用「——」占位 */
    private fun gridCellText(c: Course): String = buildString {
        append(c.name)
        append("\n老师：")
        append(c.teacher.ifBlank { MISSING })
        if (c.location.isNotBlank()) {
            append("\n教室：")
            append(c.location)
        }
    }

    /** 「课程清单」：一门课一行，全部字段一个不少 */
    fun listRows(schedule: Schedule): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        rows.add(listOf("课程名称", "老师", "教室", "星期", "节次", "周次", "学分", "备注", "类别"))
        val sorted = schedule.courses.sortedWith(
            compareBy({ it.day }, { it.startPeriod }, { it.name })
        )
        for (c in sorted) {
            rows.add(
                listOf(
                    c.name,
                    c.teacher.ifBlank { MISSING },
                    c.location.ifBlank { MISSING },
                    when {
                        c.day == 0 -> "整周"
                        c.startPeriod == c.endPeriod -> dayName(c.day)
                        else -> dayName(c.day)
                    },
                    when {
                        c.day == 0 -> "整周安排"
                        c.startPeriod == c.endPeriod -> "第${c.startPeriod}节"
                        else -> "第${c.startPeriod}-${c.endPeriod}节"
                    },
                    weekLabel(c.weeks),
                    c.credit?.takeIf { it.isNotBlank() } ?: MISSING,
                    c.note.orEmpty(),
                    if (c.day == 0) (if (c.isLab) "实验" else "实习") else "理论课"
                )
            )
        }
        return rows
    }

    // ---- xlsx 组装 ----

    private val gridColWidths = listOf(6.0, 12.0, 22.0, 22.0, 22.0, 22.0, 22.0, 22.0, 22.0)
    private val listColWidths = listOf(26.0, 14.0, 18.0, 9.0, 14.0, 13.0, 8.0, 22.0, 9.0)

    private fun put(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun workbookXml() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="课程表" sheetId="1" r:id="rId1"/><sheet name="课程清单" sheetId="2" r:id="rId2"/></sheets><styles r:id="rId3"/></workbook>"""

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>"""

    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""

    private const val WORKBOOK_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/><Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""

    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts><fills count="1"><fill><patternFill patternType="none"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment wrapText="1" vertical="top"/></xf></cellXfs></styleSheet>"""

    /**
     * 一个工作表 XML。content 第一行是表头。
     * s="1"：自动换行+顶端对齐（多行课程格能完整显示）。
     * 文本里的 & < > 与非法控制字符都做转义/剔除，保证 XML 合法。
     */
    private fun sheetXml(content: List<List<String>>, widths: List<Double>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><cols>""")
        widths.forEachIndexed { i, w ->
            sb.append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""")
        }
        sb.append("</cols><sheetData>")
        content.forEachIndexed { r, row ->
            sb.append("""<row r="${r + 1}">""")
            row.forEachIndexed { c, text ->
                sb.append("""<c r="${colName(c)}${r + 1}" t="inlineStr" s="${if (r == 0) 0 else 1}"><is><t xml:space="preserve">""")
                sb.append(escape(text))
                sb.append("</t></is></c>")
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun colName(index0: Int): String {
        var n = index0
        val sb = StringBuilder()
        while (n >= 0) {
            sb.insert(0, ('A' + n % 26))
            n = n / 26 - 1
        }
        return sb.toString()
    }

    private fun escape(s: String): String = buildString {
        for (ch in s) {
            when {
                ch == '&' -> append("&amp;")
                ch == '<' -> append("&lt;")
                ch == '>' -> append("&gt;")
                ch == '"' -> append("&quot;")
                ch == '\n' || ch == '\t' -> append(ch)
                ch.code < 0x20 -> {} // 非法 XML 控制字符直接剔除
                else -> append(ch)
            }
        }
    }
}
