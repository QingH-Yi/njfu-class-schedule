package com.njfu.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用南林教务系统真实课表页面（/jsxsd/xskb/xskb_list.do，2026-2027-1 学期，
 * 学号 2450803130）验证 JwxtParser：期望结果由逐格核对得出（17 门，去重后）。
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
    fun parseEmptyPageReturnsNull() {
        assertEquals(null, JwxtParser.parseHtml("<html><body><p>无课表</p></body></html>"))
    }
}
