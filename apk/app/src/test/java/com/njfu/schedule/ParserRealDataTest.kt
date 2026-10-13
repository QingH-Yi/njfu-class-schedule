package com.njfu.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用南林教务系统真实课表页面（/jsxsd/xskb/xskb_list.do，2026-2027-1 学期）的脱敏快照
 * 验证 JwxtParser：期望结果由逐格核对得出。
 */
class ParserRealDataTest {

    private fun html(): String =
        javaClass.getResourceAsStream("/kb_list.html")!!
            .readBytes().toString(Charsets.UTF_8)

    private fun loadKbHtml(): String =
        javaClass.getResourceAsStream("/kb_loadkb.html")!!
            .readBytes().toString(Charsets.UTF_8)

    @Test
    fun parseCreditsFromPersonalCenterPage() {
        val credits = JwxtParser.parseCredits(loadKbHtml())
        println("学分条目: $credits")
        // 个人中心课表悬停浮层里的 13 门课，学分齐全（含小数）
        assertEquals(13, credits.size)
        assertEquals("2", credits["电气控制与PLC"])
        assertEquals("0.25", credits["形势与政策（5）"])
        assertEquals("0.5", credits["体育(5)（舞龙）"])
        assertEquals("3", credits["电力系统暂态分析"])
        assertEquals("1", credits["工程经济管理概论[社会认知类]（分组01）"])

        // 三轮一致合并：只有三轮都一致的键才保留
        val r1 = credits
        val r2 = credits - "体育(5)（舞龙）"
        val r3 = credits
        val merged = Repository.mergeCreditsRounds(listOf(r1, r2, r3))
        assertTrue("不一致的键应被剔除", "体育(5)（舞龙）" !in merged)
        assertEquals("2", merged["电气控制与PLC"])
    }

    @Test
    fun parseRealTimetablePage() {
        val r = JwxtParser.parseHtml(html())
        assertTrue("应能解析出课表", r != null)
        r!!
        println("term = ${r.term}, 共 ${r.courses.size} 门")
        r.courses.sortedWith(compareBy({ it.day }, { it.startPeriod }))
            .forEach {
                println("周${it.day} 第${it.startPeriod}-${it.endPeriod}节 ${it.name} | ${it.teacher} | ${it.location} | ${it.weeks}")
            }

        // 学期：页面学期下拉选中文本为 "2026-2027-1"
        assertEquals("2026-2027-1", r.term)
        // 与逐格核对数量一致（跨大节重复格按 名字|星期|起始节 去重）+ 4 门备注行实习
        assertEquals(21, r.courses.size)

        // 备注行的整周实习/课程设计（day=0，横跨周一~周五）
        val intern = r.courses.filter { it.day == 0 }
        assertEquals(4, intern.size)
        val sx = intern.first { it.name == "专业实习" }
        assertEquals(listOf(9), sx.weeks)
        assertEquals("蒋小川,张颖", sx.teacher)
        assertEquals(1, sx.startPeriod)
        assertEquals(11, sx.endPeriod)
        assertTrue(intern.any { it.name == "电力系统设计" && it.weeks == listOf(15, 16) && it.teacher == "卫宏,郑晨一" })
        assertTrue(intern.any { it.name == "电力电子综合设计" && it.weeks == listOf(17) && it.teacher == "蒋小川,徐磊" })
        assertTrue(intern.any { it.name == "电气控制与PLC设计" && it.weeks == listOf(14) && it.teacher == "孙海阳" })

        // 抽查 1：电气控制与PLC 周一 1-3 节（单元格 [01-02-03节]）
        val plc = r.courses.first { it.name == "电气控制与PLC" }
        assertEquals(1, plc.day)
        assertEquals(1, plc.startPeriod)
        assertEquals(3, plc.endPeriod)
        assertEquals("戴斌", plc.teacher)
        assertEquals("50320", plc.location)
        assertEquals(listOf(3, 4, 5, 6, 7, 8, 10, 11, 12, 13, 18), plc.weeks)

        // 抽查 2：跨大节格子（该课在第一大节/第二大节两行重复出现，应只剩一条）
        assertEquals(1, r.courses.count { it.name == "电气控制与PLC" && it.day == 1 })

        // 抽查 3：人类生态学 周二 9-11 节（单元格 [09-10-11节]），纯中文行识别为老师
        val eco = r.courses.first { it.name.startsWith("人类生态学") }
        assertEquals(2, eco.day)
        assertEquals(9, eco.startPeriod)
        assertEquals(11, eco.endPeriod)
        assertEquals("殷文莉", eco.teacher)
        assertEquals("0327", eco.location)

        // 抽查 4：周五 信号与系统分析 1-2 节，周次 7-8,10-12
        val sig = r.courses.first { it.name == "信号与系统分析" && it.day == 5 }
        assertEquals(1, sig.startPeriod)
        assertEquals(2, sig.endPeriod)
        assertEquals(listOf(7, 8, 10, 11, 12), sig.weeks)

        // 抽查 5：周六在线教学课 1-4 节（两个相邻大节格子合并为 1-4）
        val online = r.courses.first { it.name.startsWith("工程经济管理概论") }
        assertEquals(6, online.day)
        assertEquals(1, online.startPeriod)
        assertEquals(4, online.endPeriod)

        // 抽查 6（重复 div 回归）：电气设备 真老师是宋芸；重复的课程名行不能顶掉老师
        val dev = r.courses.first { it.name == "电气设备" }
        assertEquals(1, dev.day)
        assertEquals(5, dev.startPeriod)
        assertEquals(7, dev.endPeriod)
        assertEquals("宋芸", dev.teacher)
        assertEquals("50118", dev.location)

        // 抽查 7：体育(5) 的括号备注/通知单/分隔线全部丢弃，老师=任路遥
        val pe = r.courses.first { it.name == "体育(5)" }
        assertEquals("任路遥", pe.teacher)
        assertEquals("", pe.location)
        assertEquals(7, pe.startPeriod)
        assertEquals(8, pe.endPeriod)

        // 杂质行不进字段
        val bad = r.courses.filter {
            it.name.contains("通知单") || it.teacher.contains("通知单") ||
                it.location.contains("通知单") || it.name.contains("讲课") ||
                it.teacher.contains("讲课") || it.location.contains("讲课")
        }
        assertTrue("杂质不应混入字段: $bad", bad.isEmpty())

        // 周次行不得漏进地点（重复 div 回归）
        val leak = r.courses.filter { it.location.contains("(周)") || Regex("^\\d+-\\d+").containsMatchIn(it.location) }
        assertTrue("周次行漏进地点: $leak", leak.isEmpty())
    }

    @Test
    fun parsePersonalCenterTableKeepsFieldsSeparate() {
        // 个人中心课表页（mainV_index_loadkb）：卡片名被服务端截断、悬停浮层里有"学分：X"。
        // 曾出现：两个相邻 span 被拼成一行，"学分：201~02~03节" 混进了「教室」字段。
        val r = JwxtParser.parseHtml(loadKbHtml())
        assertTrue("个人中心课表也应能解析", r != null)
        r!!
        assertTrue("应解析出课程", r.courses.isNotEmpty())
        assertTrue(
            "教室不应混进学分: " + r.courses.filter { it.location.contains("学分") },
            r.courses.none { it.location.contains("学分") }
        )
        assertTrue(
            "课名不应被截断成 ...: " + r.courses.filter { it.name.endsWith("...") },
            r.courses.none { it.name.endsWith("...") }
        )
        val plc = r.courses.first { it.name == "电气控制与PLC" }
        assertTrue("教室应含 50320，实际=" + plc.location, plc.location.contains("50320"))
        assertEquals(1, plc.startPeriod)
        assertEquals(3, plc.endPeriod)
        assertEquals(listOf(3, 4, 5, 6, 7, 8, 10, 11, 12, 13, 18), plc.weeks)
    }

    @Test
    fun parseEmptyPageReturnsNull() {
        assertEquals(null, JwxtParser.parseHtml("<html><body><p>无课表</p></body></html>"))
    }
    private fun cellBg(name: String, weeks: String, periods: String, day: Int? = null, group: Int = 1): String {
        // 复刻 jsxsd 真实格子：隐藏 input(name=jx0415zbdiv_N, value="HASH-<星期列>-<组号>") + 课名/周次/节次多行
        val inp = if (day == null) "" else "<input type=" + Q + "hidden" + Q + " name=" + Q + "jx0415zbdiv_01" + Q + " value=" + Q + "ABC123-" + day + "-" + group + Q + ">"
        return "<td>" + inp + "<div class=" + Q + "kbcontent1" + Q + ">" + name + "<br>" + weeks + "(周)<br>" + periods + "</div></td>"
    }

    @Test
    fun parseRowspanSpanningBigRowsMapsToMergedPeriods() {
        val td = cellBg("工程制图", "1-16", "[01-02-03-04节]")
        val rowspanTd = td.replace("<td>", "<td rowspan=" + Q + "2" + Q + ">")
        val empty = "<td><div class=" + Q + "kbcontent1" + Q + ">&nbsp;</div></td>"
        val table = "<html><body><table>" +
            "<tr><th>节次</th><th>星期一</th><th>星期二</th><th>星期三</th><th>星期四</th><th>星期五</th></tr>" +
            "<tr><th>第一大节</th>" + rowspanTd + empty + empty + empty + empty + "</tr>" +
            "<tr><th>第二大节</th>" + empty + empty + empty + empty + "</tr>" +
            "</table></body></html>"
        val r = JwxtParser.parseHtml(table)
        assertTrue("应能解析", r != null)
        r!!
        println("rowspan 用例课程: " + r.courses.map { "周" + it.day + ":" + it.name + ":" + it.startPeriod + "-" + it.endPeriod })
        val c = r.courses.firstOrNull { it.name == "工程制图" }
        assertTrue("rowspan 跨节的课不应丢: " + r.courses.map { it.name }, c != null)
        assertEquals(1, c!!.day)
        assertEquals(1, c.startPeriod)
        assertEquals(4, c.endPeriod)
    }

    @Test
    fun parseMergedHeaderKeepsFollowingColumns() {
        val names = listOf(
            "高等数学" to 1, "大学英语" to 2, "线性代数" to 3, "数据结构" to 4,
            "操作系统" to 5, "计算机网络" to 6, "体育" to 7
        )
        var dataRow = "<tr><th>第一大节</th>"
        for ((n, d) in names) dataRow += cellBg(n, "1-16", "[01-02节]", d)
        // 星期六/日两列表头合并成一格 colspan=2；数据行仍是 7 列
        val table = "<html><body><table>" +
            "<tr><th>节次</th><th>星期一</th><th>星期二</th><th>星期三</th><th>星期四</th><th>星期五</th><th colspan=" + Q + "2" + Q + ">星期六</th></tr>" +
            dataRow +
            "</table></body></html>"
        val r = JwxtParser.parseHtml(table)
        assertTrue("应能解析", r != null)
        r!!
        println("merged-header 课程: " + r.courses.map { "周" + it.day + ":" + it.name })
        for ((n, d) in names) {
            assertTrue("课 " + n + " 应在周" + d + ": " + r.courses.map { "周" + it.day + ":" + it.name },
                r.courses.any { it.name == n && it.day == d })
        }
    }

    // 一格两门课：复刻强智同格塞"单周课+双周课"的真实结构（两个 div.kbcontent1）
    private fun cellBgTwo(a: String, aw: String, ap: String, b: String, bw: String, bp: String, day: Int): String {
        val inp = "<input type=" + Q + "hidden" + Q + " name=" + Q + "jx0415zbdiv_01" + Q +
                " value=" + Q + "ABC123-" + day + "-1" + Q + ">"
        val one = "<div class=" + Q + "kbcontent1" + Q + ">" + a + "<br>" + aw + "(周)<br>" + ap + "</div>"
        val two = "<div class=" + Q + "kbcontent1" + Q + ">" + b + "<br>" + bw + "(周)<br>" + bp + "</div>"
        return "<td>" + inp + one + two + "</td>"
    }
    private fun oneCellTable(td: String): String =
        "<html><body><table>" +
        "<tr><th>节次</th><th>星期一</th><th>星期二</th><th>星期三</th><th>星期四</th><th>星期五</th></tr>" +
        "<tr><th>第一大节</th>" + td +
        "<td><div class=" + Q + "kbcontent1" + Q + ">&nbsp;</div></td>" +
        "<td><div class=" + Q + "kbcontent1" + Q + ">&nbsp;</div></td>" +
        "<td><div class=" + Q + "kbcontent1" + Q + ">&nbsp;</div></td>" +
        "<td><div class=" + Q + "kbcontent1" + Q + ">&nbsp;</div></td>" +
        "</tr></table></body></html>"
    @Test
    fun parseTwoCoursesInOneCellSingleDoubleWeek() {
        // 根因 A：一个 td 塞两门课（单周+双周），旧解析只取第一门 → 整门丢失
        val td = cellBgTwo("形势与政策", "1-16(单周)", "[01-02节]", "大学英语", "1-16(双周)", "[01-02节]", 1)
        val r = JwxtParser.parseHtml(oneCellTable(td))!!
        val names = r.courses.map { it.name }.toSet()
        assertTrue("两门课都应解析出来: " + r.courses.map { it.name + it.weeks }, names.containsAll(setOf("形势与政策", "大学英语")))
        val dan = r.courses.first { it.name == "形势与政策" }
        val shuang = r.courses.first { it.name == "大学英语" }
        assertTrue("单周课周次应只剩奇数: " + dan.weeks, dan.weeks.all { it % 2 == 1 } && dan.weeks.isNotEmpty())
        assertTrue("双周课周次应只剩偶数: " + shuang.weeks, shuang.weeks.all { it % 2 == 0 } && shuang.weeks.isNotEmpty())
    }
    @Test
    fun parseSameNameDifferentWeeksNotMerged() {
        // 根因 B：去重键过弱（名字|星期|起始节）会把同名不同周次的两门课误合并
        val td = cellBgTwo("体育", "1-8", "[03-04节]", "体育", "9-16", "[03-04节]", 2)
        val r = JwxtParser.parseHtml(oneCellTable(td))!!
        val sports = r.courses.filter { it.name == "体育" }
        assertEquals("同名不同周次应保留两门: " + r.courses.map { it.name + it.weeks }, 2, sports.size)
    }
    @Test
    fun parseInputDayWinsOverMergedHeader() {
        // 根因 C：表头周六合并 colspan=2 时，被覆盖列的课不能串天——靠格子自带 input 的星期列纠正
        val names = listOf("高等数学" to 1, "大学英语" to 2, "线性代数" to 3, "数据结构" to 4, "操作系统" to 5, "计算机网络" to 6, "体育" to 7)
        var dataRow = "<tr><th>第一大节</th>"
        for ((n, d) in names) dataRow += cellBg(n, "1-16", "[01-02节]", d)
        dataRow += "</tr>"
        val table = "<html><body><table>" +
            "<tr><th>节次</th><th>星期一</th><th>星期二</th><th>星期三</th><th>星期四</th><th>星期五</th><th colspan=" + Q + "2" + Q + ">星期六</th></tr>" +
            dataRow + "</table></body></html>"
        val r = JwxtParser.parseHtml(table)!!
        val ti = r.courses.firstOrNull { it.name == "体育" }
        assertTrue("体育应在周7（input 纠正 colspan 串天）: " + r.courses.map { "周" + it.day + ":" + it.name }, ti != null && ti.day == 7)
    }
    private val Q = "\""
}
