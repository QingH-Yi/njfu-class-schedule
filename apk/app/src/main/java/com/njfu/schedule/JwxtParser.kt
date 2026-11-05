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

    // 热点正则预编译（解析一页要跑几百次，别在函数里反复 Regex(...)）
    private val RE_PERIOD_SMALL = Regex("([0-9][0-9\\-~,，\\s]*)\\s*小\\s*节")
    private val RE_PERIOD_RANGE = Regex("(\\d{1,2}(?:\\s*[-~—–]\\s*\\d{1,2}){1,3})\\s*[大小]?节")
    private val RE_NUM_RANGE = Regex("(\\d+)\\s*[-~—–]\\s*(\\d+)|(\\d+)")
    private val RE_ROW_RANGE = Regex("(\\d+)\\s*[-~—–]\\s*(\\d+)")
    private val RE_DAJIE = Regex("第\\s*([一二三四五六])\\s*大节")

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
        // 同一格塞两门课时，一个 item-box 里会有两组「<p>课名</p>…学分：X」——
        // 按文档顺序遍历，每个学分归它前面最近的课名（与 web/js/parser.js 的 parseCredits 保持同步）
        for (box in doc.select("div.item-box")) {
            var name = ""
            for (el in box.select("p, span")) {
                val txt = el.text().trim()
                if (txt.isEmpty()) continue
                if (el.tagName() == "p") { name = txt; continue }
                val m = creditRe.find(txt)
                if (m != null && name.isNotEmpty()) {
                    out[name] = m.groupValues[1]
                    name = ""
                }
            }
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
        var building: String = "",
        var weeks: List<Int> = emptyList(),
        var startTime: String = "",
        var endTime: String = "",
        // title 解析的中间收集：同一课的多位老师/多间教室/该课的节次，组装时并成字符串
        val teachers: LinkedHashSet<String> = LinkedHashSet(),
        val rooms: LinkedHashSet<String> = LinkedHashSet(),
        var periodList: List<Int>? = null
    )

    /**
     * 解析一个块（td/课程 div 的 HTML），可产出多门课。
     * 先按 font[title] 分组精确解析（多老师/同块多课各自归组）；
     * 解析不出且 allowFallback=true（非标准页，无 title 标注）才用"按行猜"的老回退——
     * 标准课表格绝不猜：猜会把教室猜成老师、把在线教学猜成老师（字段错位根因）。
     * 与 web/js/parser.js 的 parseCellBlock 保持同步。
     */
    private fun parseCellBlocks(blockHtml: String, allowFallback: Boolean): List<Pair<CellInfo, Pair<Int?, Int?>>> {
        parseCellByTitleMulti(blockHtml).let { if (it.isNotEmpty()) return it }
        if (!allowFallback) return emptyList()
        val one = parseCellByLines(blockHtml) ?: return emptyList()
        return listOf(one)
    }

    /** 老回退：无 title 标注的页面按行猜字段（loadkb 预览等非标准页才走到） */
    private fun parseCellByLines(blockHtml: String): Pair<CellInfo, Pair<Int?, Int?>>? {
        val lines = blockHtml
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</(div|p|td|li|span|font|a|b|strong|i|em)>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ").replace("&amp;", "&")
            .split('\n').map { norm(it) }.filter { it.isNotEmpty() }.distinct()
            .filter {
                !(it.startsWith("通知单编号") || it.startsWith("班级") || it.startsWith("备注") ||
                        it.startsWith("学分") || it.startsWith("群号") || it.startsWith("链接") ||
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
                // 在线教学类文本是"地点"（放教室栏），绝不当老师
                if (line.contains("在线")) { if (c.location.isEmpty()) c.location = line; continue }
                val looksTeacher = Regex("(老师|教授|副教授|讲师|博士)").containsMatchIn(line) ||
                        Regex("^[\\u4e00-\\u9fa5·、,\\s]{2,12}$").matches(line)
                if (c.location.isEmpty() && looksPlace && !looksTeacher) c.location = normalizeLocation("", line)
                // 地点样式的行永远不进老师栏（宁空勿错）；都空着才兜底当老师
                else if (c.teacher.isEmpty() && looksTeacher && !looksPlace) c.teacher = line
                else if (c.location.isEmpty() && c.teacher.isEmpty() && !looksPlace) c.location = normalizeLocation("", line)
                else if (c.location.isNotEmpty() && c.teacher.isEmpty() && !looksPlace) c.teacher = line
            }
        }
        if (c.name.isEmpty()) return null
        return c to (sp to ep)
    }

    /**
     * 按 <font title='...'> 精确解析一个课程块（强智课表页每个字段都带 title）。
     * 一个块里可能塞多门课（如"动物与人类"+补课的 PLC）：**无 title 的课名字体开启新课组**，
     * 其后的 title 字体（教师/教室/周次）归该组——这样四位老师只归自己的课，不会并到同格另一门课。
     * 识别：课名（无 title 的 font）、周次(节次)、教室、教学楼、教师。
     * 与 web/js/parser.js 的 parseCellByTitle 保持同步。
     */
    private fun parseCellByTitleMulti(blockHtml: String): List<Pair<CellInfo, Pair<Int?, Int?>>> {
        val doc = Jsoup.parseBodyFragment(blockHtml)
        val fonts = doc.select("font")
        if (fonts.none { it.hasAttr("title") }) return emptyList()

        val groups = ArrayList<CellInfo>()
        var cur: CellInfo? = null
        for (f in fonts) {
            val title = f.attr("title").trim()
            val text = norm(f.text())
            if (text.isEmpty()) continue
            if (!f.hasAttr("title")) {
                // 课名字体：块里第一段开新课；再出现 = 同块还有另一门课。
                // "通知单编号/班级/备注"尾注和 "(讲课:33)" 这类无 title 括号注释不算课名
                if (text.startsWith("(") && text.endsWith(")")) continue
                if (cur == null || cur.name.isNotEmpty()) { cur = CellInfo(); groups.add(cur) }
                if (cur.name.isEmpty()) cur.name = text
                continue
            }
            if (cur == null) { cur = CellInfo(); groups.add(cur) }
            when (title) {
                "周次(节次)", "周次" -> {
                    val pl = parsePeriodList(text)
                    if (pl != null && cur.periodList == null) {
                        // 该 font 形如 "3-8,10-13,18(周)[01-02-03节]"：既含周次又含节次
                        val wk = parseWeeks(text.replace(Regex("\\[.*?\\]"), " "))
                        if (wk.isNotEmpty()) cur.weeks = wk
                        cur.periodList = pl
                    } else if (isWeekLine(text) && cur.weeks.isEmpty()) {
                        cur.weeks = parseWeeks(text)
                    }
                }
                "教室" -> if (text !in cur.rooms) cur.rooms.add(text)
                "教学楼" -> if (cur.building.isEmpty()) cur.building = text.trim('【', '】')
                "教师" -> if (text !in cur.teachers) cur.teachers.add(text)
            }
        }

        val out = ArrayList<Pair<CellInfo, Pair<Int?, Int?>>>()
        // 同一门课在 td 里常有两套呈现（简版显示层 + 悬停浮层），同名组合并、字段互补；
        // 两个同名组都有各自老师（单双周同名师不同）才当两门课保留
        val merged = LinkedHashMap<String, CellInfo>()
        for (g in groups) {
            if (g.name.isEmpty()) continue
            val prev = merged[g.name]
            if (prev == null) { merged[g.name] = g; continue }
            if (prev.teachers.isNotEmpty() && g.teachers.isNotEmpty() &&
                prev.teachers.joinToString(",") != g.teachers.joinToString(",")) {
                merged[g.name + "#" + merged.size] = g
                continue
            }
            if (prev.teachers.isEmpty()) prev.teachers.addAll(g.teachers)
            if (prev.rooms.isEmpty()) prev.rooms.addAll(g.rooms)
            if (prev.building.isEmpty()) prev.building = g.building
            if (prev.weeks.isEmpty()) prev.weeks = g.weeks
            if (prev.periodList == null) prev.periodList = g.periodList
        }
        for (g in merged.values) {
            g.teacher = g.teachers.joinToString(",")
            g.location = normalizeLocation(g.building, g.rooms.joinToString("；"))
            val sp: Int?; val ep: Int?
            if (g.periodList != null) { sp = g.periodList!!.first(); ep = g.periodList!!.last() } else { sp = null; ep = null }
            out.add(g to (sp to ep))
        }
        return out
    }

    /** 兼容入口：单课块（item-box 等）取第一组 */
    private fun parseCellByTitle(blockHtml: String): Pair<CellInfo, Pair<Int?, Int?>>? {
        val all = parseCellByTitleMulti(blockHtml)
        return all.firstOrNull()
    }

    /**
     * 解析整个 td，可能含多门课（正方/强智会把单周课+双周课、分组课等塞进同一格）。
     * 优先按 div.item-box（个人中心页，每门课一个）逐门解析，其次按 div.kbcontent1（标准课表页）逐门解析，
     * 都取不到则退回整格单课解析。与 web/js/parser.js 的 parseCellMulti 保持同步。
     */
    private fun parseCellMulti(el: Element): List<Pair<CellInfo, Pair<Int?, Int?>>> {
        // 个人中心课表页（mainV_index_loadkb）：每门课一个 div.item-box（含完整文本字段），优先逐个解析。
        // 标准课表页（xskb_list.do）的 div.item-box 只是每个字段前插的二维码空壳（仅 img，无字段），
        // 在这里解析不出任何东西，自然落到下一层。
        val hasTitles = el.select("font[title]").isNotEmpty()
        val noGuess = !hasTitles   // 无 title 的页面才允许老回退
        val itemBoxes = el.select("div.item-box")
        if (itemBoxes.size >= 1) {
            val out = ArrayList<Pair<CellInfo, Pair<Int?, Int?>>>()
            for (b in itemBoxes) out.addAll(parseCellBlocks(b.html(), noGuess))
            if (out.isNotEmpty()) return mergeLayerGroups(out)
        }
        // 标准课表页：每格多个呈现层——div.kbcontent1（显示层：课名/周次/教室，通常无教师）
        // 和 div.kbcontent（悬停完整层：课名/教师/教学楼/教室/节次）。
        // 全部解析后按课名合并互补（教师/节次以悬停层为准），同格两门课（不同名/不同师）各自成组。
        val layers = el.select("div.kbcontent, div.kbcontent1")
        if (true) println("DBG layers=" + layers.size + " titles=" + hasTitles)
        if (layers.size >= 1) {
            val out = ArrayList<Pair<CellInfo, Pair<Int?, Int?>>>()
            for (b in layers) {
                out.addAll(parseCellBlocks(b.html(), noGuess))
            }
            if (out.isNotEmpty()) return mergeLayerGroups(out)
        }
        // 都没有（旧版页面）：整个 td 一起解析
        return mergeLayerGroups(parseCellBlocks(el.html(), noGuess))
    }

    /**
     * 跨层合并：同一格的"显示层组 + 悬停浮层组"是同一门课（同名），字段互补——
     * 教师/教学楼/节次以悬停层为准（显示层没有教师、周次不带节次）。
     * 同名但两套老师（单双周同名不同师的真两门课）才保留两条。
     */
    private fun mergeLayerGroups(
        all: List<Pair<CellInfo, Pair<Int?, Int?>>>
    ): List<Pair<CellInfo, Pair<Int?, Int?>>> {
        val merged = LinkedHashMap<String, CellInfo>()
        val periods = HashMap<String, Pair<Int?, Int?>>()
        for ((c, p) in all) {
            val prev = merged[c.name]
            if (prev == null) { merged[c.name] = c; periods[c.name] = p; continue }
            val pT = prev.teachers.joinToString(","); val cT = c.teachers.joinToString(",")
            val pW = prev.weeks.joinToString(","); val cW = c.weeks.joinToString(",")
            val pP = prev.periodList; val cP = c.periodList
            // 同名但 老师/周次/节次 任一不同 = 真是两门课（单双周同名不同师、同格同名不同周次），保留两条
            if ((pT.isNotEmpty() && cT.isNotEmpty() && pT != cT) ||
                (pW.isNotEmpty() && cW.isNotEmpty() && pW != cW) ||
                (pP != null && cP != null && (pP.first() != cP.first() || pP.last() != cP.last()))) {
                val k2 = c.name + "#" + merged.size
                merged[k2] = c; periods[k2] = p
                continue
            }
            if (prev.teachers.isEmpty()) prev.teachers.addAll(c.teachers)
            if (prev.rooms.isEmpty()) prev.rooms.addAll(c.rooms)
            if (prev.building.isEmpty()) prev.building = c.building
            if (prev.weeks.isEmpty()) prev.weeks = c.weeks
            if (prev.periodList == null) prev.periodList = c.periodList
            if (periods[c.name]?.first == null) periods[c.name] = p
        }
        // 合并后重算字符串字段（title 路径的字段在集合里）；按行回退路径的字段
        // 直接在字符串上（集合为空），保留原值不动
        for (g in merged.values) {
            if (g.teachers.isNotEmpty()) g.teacher = g.teachers.joinToString(",")
            if (g.rooms.isNotEmpty() || g.building.isNotEmpty()) {
                g.location = normalizeLocation(g.building, g.rooms.joinToString("；"))
            }
        }
        return merged.map { (k, c) -> c to (periods[k] ?: (null to null)) }
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

    /**
     * 规范化教室显示：把"教学楼 + 教室"合成一个干净地点，去掉中间的破折号。
     *  - 在线课（教室含"在线"）：只留教室文本（如"新庄校区在线教学"）
     *  - 教学楼为空 / 是"其他"：只留教室
     *  - 其余：教学楼 + 空格 + 教室（如"教五楼 50320"）
     * 与 web/js/parser.js 的 normalizeLocation 保持同步。
     */
    private fun normalizeLocation(building: String, room: String): String {
        val b = building.trim().trim('【', '】').trim()
        val r = room.trim()
        if (r.isEmpty()) return b
        if (r.contains("在线")) return r
        // 教室文本统一去掉中间的破折号（"教五楼-50320" → "教五楼 50320"）
        val rClean = r.replace("-", " ").replace("　", " ").replace(Regex("\\s+"), " ").trim()
        if (b.isEmpty() || b == "其他") return rClean
        if (rClean.contains(b) || rClean.startsWith("教")) return rClean
        return "$b $rClean"
    }

    private fun isWeekLine(l: String) =
        l.any { it.isDigit() } && l.contains("周") &&
                !Regex("上课|地点|老师|教师").containsMatchIn(l)

    private fun parseWeeks(text: String): List<Int> =
        parseWeekNumbers(text, text.contains("单周"), text.contains("双周"))

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

