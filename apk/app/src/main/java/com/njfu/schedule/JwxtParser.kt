package com.njfu.schedule

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.UUID

/**
 * 正方教务系统课表解析器（Kotlin 版，兼容旧版与新版门户）。
 * 逻辑与网页版 web/js/parser.js 一致。
 */
object JwxtParser {

    private val DAJIE = mapOf(
        "一" to (1 to 2), "二" to (3 to 4), "三" to (5 to 6),
        "四" to (7 to 8), "五" to (9 to 10), "六" to (11 to 12)
    )

    fun parseHtml(html: String): ParseResult? {
        val doc = Jsoup.parse(html)
        return parseCourseTable(doc)
    }

    fun parseCourseTable(doc: Document): ParseResult? {
        // 1. 选出最像课表的表格
        var best: Element? = null
        var bestScore = -1
        for (tb in doc.select("table")) {
            var dayCount = 0; var hasHeader = false; var content = 0
            for (tr in tb.select("tr")) {
                for (el in tr.children()) {
                    if (headerDay(el.text()) > 0) { hasHeader = true; dayCount++ }
                    if (el.text().isNotBlank()) content++
                }
            }
            if (!hasHeader) continue
            val score = content + dayCount
            if (score > bestScore) { bestScore = score; best = tb }
        }
        val table = best ?: return null

        val trs = table.select("tr")
        val R = trs.size; val C = 40

        // 2. 网格填充（处理 rowspan/colspan）
        val grid = Array(R) { arrayOfNulls<Element>(C) }
        val occupied = HashSet<String>()
        // key "r,c" -> (cell, rowspan)
        val cellInfo = HashMap<String, Pair<Element, Int>>()
        for (r in 0 until R) {
            var col = 0
            for (el in trs[r].children()) {
                while (col < C && occupied.contains("$r,$col")) col++
                if (col >= C) break
                val rs = el.attr("rowspan").toIntOrNull()?.coerceAtLeast(1) ?: 1
                val cs = el.attr("colspan").toIntOrNull()?.coerceAtLeast(1) ?: 1
                grid[r][col] = el
                cellInfo["$r,$col"] = el to rs
                for (dr in 0 until rs) for (dc in 0 until cs)
                    occupied.add("${r + dr},${col + dc}")
                col += cs
            }
        }

        // 3. 星期表头行 -> 列->星期
        val colDay = HashMap<Int, Int>()
        var headerRow = -1
        for (hr in 0 until R) {
            var found = 0
            for (hc in 0 until C) {
                val cell = grid[hr][hc] ?: continue
                val dh = headerDay(cell.text())
                if (dh > 0 && colDay[hc] == null) { colDay[hc] = dh; found++ }
            }
            if (found >= 5) { headerRow = hr; break }
        }
        if (headerRow < 0) return null

        // 4. 每行默认节次（"第X大节" / "第X-Y节"）
        val rowPeriod = HashMap<Int, Pair<Int, Int>>()
        for (pr in headerRow + 1 until R) {
            val first = grid[pr][0]
            val pp = first?.let { parseRowPeriod(it.text()) }
            if (pp != null) rowPeriod[pr] = pp
        }

        // 5. 遍历数据格
        val courses = mutableListOf<Course>()
        val seen = HashSet<String>()
        for (dr in headerRow + 1 until R) {
            for (dc in 1 until C) {
                val info = cellInfo["$dr,$dc"] ?: continue
                val day = colDay[dc] ?: continue
                val el = info.first
                if (!el.tagName().equals("td", true)) continue
                val text = el.text().trim()
                if (text.isEmpty()) continue

                val base = parseCell(el) ?: continue
                var sp = base.second.first      // startPeriod (nullable)
                var ep = base.second.second     // endPeriod (nullable)

                if (sp == null) {
                    val rp = rowPeriod[dr]
                    if (rp != null) { sp = rp.first; ep = rp.second } else { sp = 1; ep = 1 }
                }
                val rs = info.second
                if (rs >= 2) {
                    val lastRow = dr + rs - 1
                    val rLast = rowPeriod[lastRow] ?: rowPeriod[dr]
                    ep = rLast?.second ?: (sp + rs - 1)
                }
                if (ep == null || ep < sp) ep = sp
                val spF = sp ?: 1
                val epF = ep ?: spF

                val name = base.first.name
                val key = "$name|$day|$spF"
                if (seen.contains(key)) continue
                seen.add(key)
                courses.add(
                    Course(
                        id = UUID.randomUUID().toString().take(8),
                        name = name,
                        teacher = base.first.teacher,
                        location = base.first.location,
                        day = day,
                        startPeriod = spF,
                        endPeriod = epF,
                        weeks = base.first.weeks,
                        startTime = base.first.startTime,
                        endTime = base.first.endTime
                    )
                )
            }
        }

        // 6. 备注行的整周实习/课程设计（无课表格子，如"专业实习 蒋小川,张颖 9周"）
        //    解析为 day=0 的整周课，UI 上横跨周一~周五展示
        var remark = ""
        for (r in 0 until R) {
            val firstText = grid[r][0]?.text()?.trim() ?: ""
            if (!firstText.startsWith("备注")) continue
            for (c in 0 until C) {
                val cell = grid[r][c] ?: continue
                val t = cell.text().trim().replace(Regex("^备注\\s*[:：]?"), "").trim()
                if (t.isNotEmpty()) remark += "$t "
            }
            break
        }
        for (entry in remark.split(';', '；')) {
            val parts = entry.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.size < 2) continue
            val weeks = parseWeeks(parts.last())
            if (weeks.isEmpty()) continue
            val tTok = parts[parts.size - 2]
            val teachers =
                if (Regex("[\\u4e00-\\u9fa5]+([,,、][\\u4e00-\\u9fa5]+)*").matches(tTok)) tTok else ""
            val name =
                (if (teachers.isNotEmpty()) parts.dropLast(2) else parts.dropLast(1))
                    .joinToString(" ").trim()
            if (name.isEmpty()) continue
            if (!seen.add("$name|0|1")) continue
            courses.add(
                Course(
                    id = UUID.randomUUID().toString().take(8),
                    name = name,
                    teacher = teachers,
                    location = "",
                    day = 0,
                    startPeriod = 1,
                    endPeriod = 11,
                    weeks = weeks
                )
            )
        }

        val term = extractTerm(doc)
        return ParseResult(term, courses)
    }

    /** 解析单个单元格 -> (CellInfo, startPeriod to endPeriod) */
    private data class CellInfo(
        var name: String = "",
        var teacher: String = "",
        var location: String = "",
        var weeks: List<Int> = emptyList(),
        var startTime: String = "",
        var endTime: String = ""
    )

    private fun parseCell(el: Element): Pair<CellInfo, Pair<Int?, Int?>>? {
        val lines = el.html()
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</(div|p|td|li)>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ").replace("&amp;", "&")
            // 强智新版格子内有 kbcontent1/kbcontent 两个隐藏 div，内容重复；
            // 去重可避免重复行顶掉真老师名、或把周次行漏进地点
            .split('\n').map { norm(it) }.filter { it.isNotEmpty() }.distinct()
            // 强智格子里的"通知单编号/班级/备注"管理元数据、横线分隔行、
            // "(讲课:16)"/"(舞龙)"等括号备注都不是课程字段，直接丢弃
            .filter {
                !(it.startsWith("通知单编号") || it.startsWith("班级") || it.startsWith("备注") ||
                        Regex("^[-—=_]{3,}").containsMatchIn(it) ||
                        Regex("^\\(.*\\)$").matches(it))
            }
        if (lines.isEmpty()) return null

        val c = CellInfo()
        val used = BooleanArray(lines.size)
        var periodList: List<Int>? = null

        for (i in lines.indices) {
            val l = lines[i]
            val pl = parsePeriodList(l)
            if (pl != null && periodList == null) {
                periodList = pl
                val wk = parseWeeks(l)
                if (wk.isNotEmpty() && c.weeks.isEmpty()) c.weeks = wk
                used[i] = true; continue
            }
            if (isWeekLine(l)) {
                // 周次行一律消费掉（首个设置周次，其余丢弃），防止漏进地点
                if (c.weeks.isEmpty()) c.weeks = parseWeeks(l)
                used[i] = true; continue
            }
            val t = parseTime(l)
            if (t != null && c.startTime.isEmpty()) {
                c.startTime = t.first; c.endTime = t.second; used[i] = true; continue
            }
            val tm = Regex("^(?:教师|老师|任课)\\s*[:：]\\s*(.+)$").find(l)
            if (tm != null && c.teacher.isEmpty()) {
                c.teacher = tm.groupValues[1].trim(); used[i] = true; continue
            }
        }

        var sp: Int? = null; var ep: Int? = null
        if (periodList != null) { sp = periodList.first(); ep = periodList.last() }

        val rest = lines.filterIndexed { idx, _ -> !used[idx] }
        if (rest.isNotEmpty()) {
            c.name = rest[0]
            for (j in 1 until rest.size) {
                val line = rest[j]
                val tm2 = Regex("^(?:教师|老师|任课)\\s*[:：]\\s*(.+)$").find(line)
                if (tm2 != null) { if (c.teacher.isEmpty()) c.teacher = tm2.groupValues[1].trim(); continue }
                val looksPlace = Regex("(楼|教|实验|馆|室|区|号楼|\\d{2,4})").containsMatchIn(line) && !line.contains("@")
                val looksTeacher = Regex("(老师|教授|副教授|讲师|博士)").containsMatchIn(line) ||
                        Regex("^[\\u4e00-\\u9fa5·、,\\s]{2,12}$").matches(line)
                if (c.location.isEmpty() && looksPlace && !looksTeacher) c.location = line
                else if (c.teacher.isEmpty() && (looksTeacher || (c.location.isEmpty() && !looksPlace)))
                    c.teacher = if (c.teacher.isEmpty()) line else c.teacher + "、" + line
                else if (c.location.isEmpty() && c.teacher.isEmpty()) c.teacher = line
            }
        }
        if (c.name.isEmpty()) return null
        return c to (sp to ep)
    }

    // ---- 工具 ----
    private fun norm(s: String) = s.replace(Regex("[\\u00a0\\u3000\\s]+"), " ").trim()

    private fun isWeekLine(l: String) =
        l.any { it.isDigit() } && l.contains("周") &&
                !Regex("上课|地点|老师|教师").containsMatchIn(l)

    private fun parseWeeks(text: String): List<Int> {
        if (text.isEmpty()) return emptyList()
        val single = text.contains("单周"); val double = text.contains("双周")
        val out = sortedSetOf<Int>()
        val re = Regex("(\\d+)\\s*[-~—–]\\s*(\\d+)|(\\d+)")
        for (m in re.findAll(text)) {
            val g = m.groupValues
            if (g[1].isNotEmpty()) {
                var a = g[1].toInt(); var b = g[2].toInt()
                if (a > b) { val t = a; a = b; b = t }
                for (i in a..b) out.add(i)
            } else out.add(g[3].toInt())
        }
        var list = out.toList()
        if (single) list = list.filter { it % 2 == 1 }
        if (double) list = list.filter { it % 2 == 0 }
        return list
    }

    private fun parseRowPeriod(text: String): Pair<Int, Int>? {
        val m = Regex("第\\s*([一二三四五六])\\s*大节").find(text)
        if (m != null) DAJIE[m.groupValues[1]]?.let { return it }
        val r = Regex("(\\d+)\\s*[-~—–]\\s*(\\d+)").find(text)
        if (r != null) return r.groupValues[1].toInt() to r.groupValues[2].toInt()
        val s = Regex("(\\d+)").find(text)
        if (s != null) return s.groupValues[1].toInt() to s.groupValues[1].toInt()
        return null
    }

    private fun parsePeriodList(text: String): List<Int>? {
        var seg: String? = null
        var m = Regex("([0-9][0-9\\-~,，\\s]*)\\s*小\\s*节").find(text)
        if (m == null) m = Regex("(\\d{1,2}(?:\\s*[-~—–]\\s*\\d{1,2}){1,3})\\s*[大小]?节").find(text)
        if (m != null) seg = m.groupValues[1] else return null
        val out = sortedSetOf<Int>()
        val re = Regex("(\\d+)\\s*[-~—–]\\s*(\\d+)|(\\d+)")
        for (mm in re.findAll(seg)) {
            val g = mm.groupValues
            if (g[1].isNotEmpty()) {
                var a = g[1].toInt(); var b = g[2].toInt()
                if (a > b) { val t = a; a = b; b = t }
                for (i in a..b) out.add(i)
            } else out.add(g[3].toInt())
        }
        return if (out.isEmpty()) null else out.toList()
    }

    private fun parseTime(text: String): Pair<String, String>? {
        val m = Regex("(\\d{1,2}):(\\d{2})\\s*[-~—–]\\s*(\\d{1,2}):(\\d{2})").find(text) ?: return null
        val g = m.groupValues
        return "%02d:%s".format(g[1].toInt(), g[2]) to "%02d:%s".format(g[3].toInt(), g[4])
    }

    private fun headerDay(text: String): Int {
        val s = norm(text)
        val map = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '日' to 7, '天' to 7)
        Regex("周\\s*([一二三四五六日天])").find(s)?.let { return map[it.groupValues[1].first()] ?: 0 }
        Regex("星期\\s*([一二三四五六日天])").find(s)?.let { return map[it.groupValues[1].first()] ?: 0 }
        return 0
    }

    private fun extractTerm(doc: Document): String {
        val body = doc.body()?.text() ?: ""
        Regex("\\d{4}\\s*[-—]\\s*\\d{4}\\s*学年\\s*第?\\s*[12]\\s*学期?").find(body)?.let { return norm(it.value) }
        // 强智新版门户：学期下拉选项形如 "2026-2027-1"（与 parser.js 保持同步）
        Regex("\\d{4}-\\d{4}-[12]").find(body)?.let { return it.value }
        return ""
    }
}
