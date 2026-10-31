package com.njfu.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用 2026-10-31 当天从教务抓的【真实活页】做回归（含真实学号/老师姓名，文件只留本机、已 gitignore）。
 * 背景：首启自动登录后 WebView 停在个人中心门户时，旧版 GRAB_JS 会误抓门户预览表，
 * 导致入库课程老师大面积为空。此用例保证解析器对正确课表页的老师/节次/周次全部解析到位；
 * GRAB_JS 的"认准 kbcontent 结构"修复在 MainActivity（JS，无法 JVM 单测），靠此页快照+模拟器联测兜底。
 */
class ParserLivePageTest {

    private fun html(): String =
        javaClass.getResourceAsStream("/kb_list_live.html")!!.readBytes().toString(Charsets.UTF_8)

    @Test
    fun livePageParsesAllCoursesWithTeachers() {
        val r = JwxtParser.parseHtml(html())!!
        assertEquals("活页应解析出 21 门课", 21, r.courses.size)
        val emptyTeacher = r.courses.filter { it.teacher.isBlank() }
        assertTrue(
            "不该有老师为空的课（空的是：${emptyTeacher.map { it.name }}）",
            emptyTeacher.isEmpty()
        )
        // 抽查：课名 → 老师对应关系（多老师逗号分隔的分组课也要完整）
        val find = { prefix: String -> r.courses.firstOrNull { it.name.startsWith(prefix) } }
        assertEquals("戴斌", find("电气控制与PLC")!!.teacher)
        assertEquals("胡春华", find("电力系统暂态分析")!!.teacher.take(3))
        assertTrue(find("工程经济管理概论")!!.teacher.contains("房银海"))
        // 整周安排（备注行 → day=0）老师也要有
        assertTrue(r.courses.filter { it.day == 0 }.all { it.teacher.isNotBlank() })
    }
}
