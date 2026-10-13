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

    /**
     * 从个人中心课表（framework/mainV_index_loadkb.htmlx）HTML 提取 课名→学分。
     * 该页悬停课程卡片显示的浮层就是 div.item-box，其中带「学分：X」。
     * 与 web/js/parser.js 的 parseCredits 保持同步（改一处必须同步另一处）。
     */
    fun parseCredits(html: String): Map<String, String> {
        val doc = Jsoup.parse(html)
        val out = LinkedHashMap<String, String>()
        val creditRe = Regex("学分[：:]\\s*([0-9]+(?:\\.[0-9]+)?)")
        for (box in doc.select("div.item-box")) {
            val name = box.selectFirst("p")?.text()?.trim() ?: continue
            if (name.isEmpty()) continue
            val creditSpan = box.select("span").firstOrNull { it.text().contains("学分") } ?: continue
            val m = creditRe.find(creditSpan.text()) ?: continue
            out[name] = m.groupValues[1]
        }
        return out
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
                cellInfo["$r,$col"] = el to rs
                // 把格子的 span 区域在网格里铺满（同一 Element 指向多格）。
                // 关键：表头若用 colspan 合并（如周六跨两列），必须让被覆盖列也拿到该 Element，
                // 否则 colDay 里那列为空 → 该列下的课整列丢失（"漏某天一两节课"的根因）。
                for (dr in 0 until rs) for (dc in 0 until cs) {
                    val rr = r + dr; val cc = col + dc
                    if (rr < R && cc < C) {
                        grid[rr][cc] = el
                        occupied.add("$rr,$cc")
                    }
                }
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

        // 5. 遍历数据格（一个格子可能同时放多门课，如单周课+双周课，必须逐门产出）
        val courses = mutableListOf<Course>()
        val seen = HashSet<String>()
        for (dr in headerRow + 1 until R) {
            for (dc in 1 until C) {
                val info = cellInfo["$dr,$dc"] ?: continue
                val el = info.first
                if (!el.tagName().equals("td", true)) continue
                val text = el.text().trim()
                if (text.isEmpty()) continue
                // 星期列优先取格子自带隐藏 input 的精确坐标（jsxsd 的 jx0415zbdiv_<小节>
                // value 末段形如 "<星期列>-<组号>"）；取不到再退回表头网格推断。
                // 这条比网格推断可靠：colspan 合并表头时不会串天。
                val day = cellDayFromInput(el) ?: colDay[dc] ?: continue

                val rs = info.second
                // 一格多课：逐门解析，各自算节次/周次，分别入表
                for (base in parseCellMulti(el)) {
                    var sp = base.second.first
                    var ep = base.second.second
                    if (sp == null) {
                        val rp = rowPeriod[dr]
                        if (rp != null) { sp = rp.first; ep = rp.second } else { sp = 1; ep = 1 }
                    }
                    if (rs >= 2) {
                        val lastRow = dr + rs - 1
                        val rLast = rowPeriod[lastRow] ?: rowPeriod[dr]
                        ep = rLast?.second ?: (sp + rs - 1)
                    }
                    if (ep == null || ep < sp) ep = sp
                    val spF = sp ?: 1
                    val epF = ep ?: spF

                    val c = base.first
                    if (c.name.isEmpty()) continue
                    // 去重键含结束节/周次/教师：既能去掉"同课跨大节重复格"，
                    // 又不会把"同名同天同起始节但周次/教师不同"的两门课误合成一门
                    val weekSig = c.weeks.sorted().joinToString(",")
                    val key = "${c.name}|$day|$spF|$epF|$weekSig|${c.teacher}"
                    if (!seen.add(key)) continue
                    courses.add(
                        Course(
                            id = UUID.randomUUID().toString().take(8),
                            name = c.name,
                            teacher = c.teacher,
                            location = c.location,
                            day = day,
                            startPeriod = spF,
                            endPeriod = epF,
                            weeks = c.weeks,
                            startTime = c.startTime,
                            endTime = c.endTime
                        )
                    )
                }
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
                    weeks = weeks,
                    // 区分实习/实验：名字里带"实验"→实验（第11节下方单独一行）；否则当实习（周一到周五大卡片）
                    kind = if (name.contains("实验")) "lab" else "intern"
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

    /**
     * 解析一个课程块（td 里一门课对应的 div 内容）-> (CellInfo, startPeriod to endPeriod)。
     * 与 web/js/parser.js 的 parseCellBlock 保持同步。
     */
    private fun parseCellBlock(blockHtml: String): Pair<CellInfo, Pair<Int?, Int?>>? {
        val lines = blockHtml
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</(div|p|td|li|span|font|a|b|strong|i|em)>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ").replace("&amp;", "&")
            .split('\n').map { norm(it) }.filter { it.isNotEmpty() }.distinct()
            .filter {
                !(it.startsWith("通知单编号") || it.startsWith("班级") || it.startsWith("备注") ||
                        it.startsWith("学分") ||
                        Regex("[.．…]{2,}$").containsMatchIn(it) ||
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
            if (pl != null) {
                if (periodList == null) {
                    periodList = pl
                    // 只有该行确实含"周"时才取周次（如 "01~02~03小节 第3-8周"）；
                    // 否则纯节次行 "01~02~03节" 会被误当成周次 [1,2,3]。
                    if (l.contains("周")) {
                        val wk = parseWeeks(l.replace(Regex("\\d+(?:\\s*[-~—–]\\s*\\d+)+\\s*小节"), " "))
                        if (wk.isNotEmpty() && c.weeks.isEmpty()) c.weeks = wk
                    }
                    // 该行去掉节次/周次后若还残留课名，单独取出来；否则整行消费会让课名丢失
                    val residual = l
                        .replace(Regex("\\[[^\\]]*\\d+[^\\]]*节[^\\]]*\\]"), " ")
                        .replace(Regex("\\d{1,2}(?:\\s*[-~—–]\\s*\\d{1,2}){1,3}\\s*小?节"), " ")
                        .replace(Regex("\\d+(?:\\s*[-~—–,]\\s*\\d+)*\\s*\\(?周\\)?"), " ")
                    val rn = norm(residual)
                    if (rn.isNotEmpty() && c.name.isEmpty()) c.name = rn
                }
                used[i] = true; continue
            }
            if (isWeekLine(l)) {
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
        if (rest.isNotEmpty() && c.name.isEmpty()) {
            c.name = rest[0]
            for (j in 1 until rest.size) {
                val line = rest[j]
                val tm2 = Regex("^(?:教师|老师|任课)\\s*[:：]\\s*(.+)$").find(line)
                if (tm2 != null) { if (c.teacher.isEmpty()) c.teacher = tm2.groupValues[1].trim(); continue }
                val looksPlace = Regex("(楼|教|实验|馆|室|区|号楼|\\d{2,4})").containsMatchIn(line) && !line.contains("@")
                val looksTeacher = Regex("(老师|教授|副教授|讲师|博士)").containsMatchIn(line) ||
                        Regex("^[\\u4e00-\\u9fa5·、,\\s]{2,12}$").matches(line)
                if (c.location.isEmpty() && looksPlace && !looksTeacher) c.location = line
                else if (c.teacher.isEmpty() && (looksTeacher || (c.location.isEmpty() && !looksPlace))) c.teacher = line
                else if (c.location.isEmpty() && c.teacher.isEmpty()) c.teacher = line
            }
        }
        if (c.name.isEmpty()) return null
        return c to (sp to ep)
    }

    /**
     * 解析整个 td，可能含多门课（正方/强智会把单周课+双周课、分组课等塞进同一格）。
     * 优先按 div.item-box（个人中心页，每门课一个）逐门解析，其次按 div.kbcontent1（标准课表页）逐门解析，
     * 都取不到则退回整格单课解析。与 web/js/parser.js 的 parseCellMulti 保持同步。
     */
    private fun parseCellMulti(el: Element): List<Pair<CellInfo, Pair<Int?, Int?>>> {
        // 个人中心课表页（mainV_index_loadkb）：每门课一个 div.item-box（完整课名），
        // 同一 td 里还并排一个 span.box（服务端截断的课名 + 教师 + 周次），是重复信息。
        // 必须优先按 item-box 逐门解析，否则整格解析会把浮层里的"学分：X"混进教室字段。
        val itemBoxes = el.select("div.item-box")
        if (itemBoxes.size >= 1) {
            val out = ArrayList<Pair<CellInfo, Pair<Int?, Int?>>>()
            for (b in itemBoxes) {
                val parsed = parseCellBlock(b.html()) ?: continue
                if (parsed.first.name.isEmpty()) continue
                out.add(parsed)
            }
            if (out.isNotEmpty()) return out
        }
        // 强智标准课表页：每门课一个 div.kbcontent1（同格多门课时逐门产出）
        val blocks = el.select("div.kbcontent1")
        if (blocks.size >= 2) {
            val out = ArrayList<Pair<CellInfo, Pair<Int?, Int?>>>()
            for (b in blocks) {
                val parsed = parseCellBlock(b.html()) ?: continue
                if (parsed.first.name.isEmpty()) continue
                out.add(parsed)
            }
            if (out.isNotEmpty()) return out
        }
        val one = parseCellBlock(el.html()) ?: return emptyList()
        return listOf(one)
    }

    /**
     * 从格子里 jsxsd 的隐藏 input（name=jx0415zbdiv_<小节>，value="HASH-<星期列>-<组号>"）
     * 读出星期列。取不到返回 null，调用方退回表头网格推断。
     * 与 web/js/parser.js 的 cellDayFromInput 保持同步。
     */
    private fun cellDayFromInput(el: Element): Int? {
        for (inp in el.select("input[name^=jx0415zbdiv_]")) {
            val v = inp.attr("value")
            val m = Regex("-(\\d+)-(\\d+)$").find(v.trim()) ?: continue
            val d = m.groupValues[1].toIntOrNull() ?: continue
            if (d in 1..7) return d
        }
        return null
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

