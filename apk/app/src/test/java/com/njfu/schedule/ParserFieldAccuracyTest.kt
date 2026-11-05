package com.njfu.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全字段准确性回归（用户明确要求：爬完每个格子都要和教务系统一模一样，不是"有内容"就行）。
 * 真值逐格转录自真实页面快照 kb_list_live.html / kb_loadkb_live.html（含悬停浮层学分）。
 * 规格：
 *  - 在线授课 → 教室栏（如"新庄校区在线教学"）
 *  - 没有老师/教室的课 → 对应栏留空（绝不猜、绝不编造）
 *  - 多老师课（如"电气工程学科前沿"四位老师）一个不落
 *  - 学分来自悬停浮层（div.item-box 的「学分：X」），按课名精确匹配
 */
class ParserFieldAccuracyTest {

    private fun res(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes().toString(Charsets.UTF_8)

    /** 真值表：key=(day*100+sp) → (课名, 老师, 教室, 周次) */
    /** 真值表：(day, 课名) → (老师, 教室, 周次)，逐格转录自真实页面快照 */
    private val truth: Map<Pair<Int, String>, List<String>> = mapOf(
        (0 to "专业实习") to listOf("蒋小川,张颖", "", "9"),
        (0 to "电力电子综合设计") to listOf("蒋小川,徐磊", "", "17"),
        (0 to "电力系统设计") to listOf("卫宏,郑晨一", "", "15-16"),
        (0 to "电气控制与PLC设计") to listOf("孙海阳", "", "14"),
        (1 to "电气控制与PLC") to listOf("戴斌", "教五楼 50320", "3-7,10-13,18"),
        (1 to "电气设备") to listOf("宋芸", "教五楼 50118", "1-8,10-12"),
        (2 to "电力系统暂态分析") to listOf("胡春华", "教五楼 50218", "1-8,10-13"),
        (2 to "电力电子技术") to listOf("高娴", "教五楼 50112", "1-8,10-13"),
        (2 to "电气工程专业英语") to listOf("张颖", "教五楼 50109", "3-7,10-13,18"),
        (2 to "人类生态学[绿色文明类]") to listOf("殷文莉", "教学主楼 0327", "1-11"),
        (3 to "信号与系统分析") to listOf("焦万果", "教五楼 50320", "1-8,10-12"),
        (3 to "动物与人类[科学精神类]") to listOf("殷文莉", "教五楼 50601", "1-11"),
        (3 to "电气控制与PLC") to listOf("戴斌", "教五楼 50213", "8"),
        (4 to "电力系统暂态分析") to listOf("胡春华", "教五楼 50317", "1-8,10-13"),
        (4 to "电气工程学科前沿") to listOf("高娴,卢乙,孙永腾,张娅楠", "教九楼 9A207", "1-7,10-12"),
        (4 to "英语国家留学指南及文书写作") to listOf("黄强", "教五楼 50102", "1-8,11-13"),
        (4 to "电气工程专业英语") to listOf("张颖", "教五楼 50601", "8"),
        (5 to "信号与系统分析") to listOf("焦万果", "教五楼 50308", "7-8,10-12"),
        (5 to "电气工程学科前沿") to listOf("高娴,卢乙,孙永腾,张娅楠", "教九楼 9A207", "12"),
        (5 to "电力电子技术") to listOf("高娴", "教五楼 50320", "1-8,10-13"),
        (5 to "体育(5) (舞龙)") to listOf("任路遥", "", "1-5,10-12"),
        (6 to "工程经济管理概论[社会认知类] (分组01)") to listOf("房银海,马欣,汪海燕,王妹,殷婧钰", "新庄校区在线教学", "6-10"),
        (7 to "形势与政策（5）") to listOf("杨振华", "教五楼 阶4", "11-12")
    )

    private val credits = mapOf(
        "电气控制与PLC" to "2", "电力系统暂态分析" to "3", "信号与系统分析" to "2",
        "工程经济管理概论[社会认知类]（分组01）" to "1", "电力电子技术" to "3",
        "电气工程学科前沿" to "2", "电气设备" to "2", "电气工程专业英语" to "2",
        "形势与政策（5）" to "0.25", "体育(5)（舞龙）" to "0.5",
        "人类生态学[绿色文明类]" to "2", "动物与人类[科学精神类]" to "2",
        "英语国家留学指南及文书写作" to "2"
    )

    @Test
    fun tmpDumpRows() {
        val r = JwxtParser.parseHtml(res("kb_list_live.html"))!!
        println("DUMPCOUNT " + r.courses.size)
        r.courses.sortedWith(compareBy({ it.day }, { it.startPeriod }, { it.name })).forEach {
            println("ROW|day=${it.day}|sp=${it.startPeriod}|ep=${it.endPeriod}|name=${it.name}|teacher=${it.teacher}|room=${it.location}|weeks=${formatWeeks(it.weeks)}")
        }
    }

    @Test
    fun everyCellMatchesTheRealPage() {
        val r = JwxtParser.parseHtml(res("kb_list_live.html"))!!
        val byKey = r.courses.associateBy { it.day to it.name }
        assertEquals("课的门数要和教务页面一致（4 门整周 + 19 门理论格，含 2 门补课）", truth.size, r.courses.size)
        for ((key, t) in truth) {
            val c = byKey[key]
            assertTrue("缺课：$key 期望 ${t[0]}", c != null)
            c!!
            assertEquals("[$key] 老师", t[0], c.teacher)
            assertEquals("[$key] 教室", t[1], c.location)
            assertEquals("[$key] 周次", t[2], formatWeeks(c.weeks))
        }
    }

    @Test
    fun creditsMatchHoverPopups() {
        val parsed = JwxtParser.parseCredits(res("kb_loadkb_live.html"))
        assertEquals(credits, parsed)
        // 学分按课名并进课表：理论课全有学分；整周实习教务本就没有学分数据（不编造）
        val r = JwxtParser.parseHtml(res("kb_list_live.html"))!!
        val merged = Repository.applyCreditsForTest(parsed, Schedule(courses = r.courses))
        val theory = merged.filter { it.day != 0 }
        assertEquals("理论课学分一门不少", theory.size, theory.count { !it.credit.isNullOrEmpty() })
        assertEquals("电气工程学科前沿 学分 2", "2", merged.first { it.name == "电气工程学科前沿" && it.day == 4 }.credit)
        assertTrue("整周实习不编学分", merged.filter { it.day == 0 }.all { it.credit.isNullOrEmpty() })
    }

    /** 合成格子回归：多个 title='教师' 字体全部保留；没有老师/教室就留空 */
    @Test
    fun syntheticCellsWithMultipleTeacherFontsAndEmptyFields() {
        val html = """
        <table><tr><td>周一</td><td>周二</td><td>周三</td><td>周四</td><td>周五</td><td>周六</td><td>周日</td></tr>
        <tr><td>1</td><td></td><td></td><td></td><td></td><td></td><td></td></tr>
        <tr><td>3</td>
        <td><div class="kbcontent1">
          <font>多老师课</font><br>
          <font title="教师">甲乙</font><br>
          <font title="教师">丙丁</font><br>
          <font title="教室">教1楼 101</font><br>
          <font title="周次(节次)">1-4(周)[01-02节]</font>
        </div></td>
        <td></td><td></td><td></td><td></td><td></td></tr>
        <tr><td>5</td><td></td>
        <td><div class="kbcontent1">
          <font>无师无教室课</font><br>
          <font title="周次(节次)">1-16(周)[03-04节]</font>
        </div></td>
        <td></td><td></td><td></td><td></td></tr>
        </table>""".trimIndent()
        val r = JwxtParser.parseHtml(html)!!
        val multi = r.courses.first { it.name == "多老师课" }
        assertEquals("甲乙,丙丁", multi.teacher)
        assertEquals("教1楼 101", multi.location)
        val empty = r.courses.first { it.name == "无师无教室课" }
        assertEquals("没老师就留空（不编造）", "", empty.teacher)
        assertEquals("没教室就留空（不编造）", "", empty.location)
    }
}
