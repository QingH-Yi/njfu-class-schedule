package com.njfu.schedule

import org.junit.Test
import java.io.File

/**
 * 【交叉校验用，非回归测试】把四份真实快照的解析结果导出为 JSON 文本，
 * 供外部脚本与网页版 parser.js、Python 独立提取基准逐字段比对。
 * 不用 org.json（单测里是 Android stub，未 mock），手写 JSON 转义。
 */
class CrossCheckDumpTest {

    // 快照含真实信息、仅本机（已 gitignore）；缺失时跳过对应 dump，避免别的机器上挂测试
    private fun resOrNull(name: String): String? =
        javaClass.getResourceAsStream("/$name")?.readBytes()?.toString(Charsets.UTF_8)

    private fun esc(s: String?): String =
        (s ?: "").replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "")

    private fun dump(name: String, result: ParseResult?) {
        val items = result?.courses?.joinToString(",") { c ->
            """{"name":"${esc(c.name)}","teacher":"${esc(c.teacher)}","location":"${esc(c.location)}",""" +
                """"day":${c.day},"startPeriod":${c.startPeriod},"endPeriod":${c.endPeriod},""" +
                """"weeks":${c.weeks.toString().replace(" ", "")},"kind":"${esc(c.kind)}"}"""
        } ?: ""
        val json = """{"term":"${esc(result?.term)}","courses":[$items]}"""
        File("build/crosscheck").apply { mkdirs() }
            .resolve("kotlin_$name.json").writeText(json)
        println("CROSSCHECK $name: ${result?.courses?.size} courses, term=${result?.term}")
    }

    @Test
    fun dumpAllFixtures() {
        listOf("kb_list.html", "kb_list_live.html", "kb_loadkb.html", "kb_loadkb_live.html", "kb_portal_live.html")
            .forEach { resOrNull(it)?.let { html -> dump(it.removeSuffix(".html"), JwxtParser.parseHtml(html)) } }

        listOf("kb_loadkb.html", "kb_loadkb_live.html").forEach { name ->
            val credits = JwxtParser.parseCredits(resOrNull(name) ?: return@forEach)
            val json = credits.entries.joinToString(",", "{", "}") {
                "\"${esc(it.key)}\":\"${esc(it.value)}\""
            }
            File("build/crosscheck").apply { mkdirs() }
                .resolve("kotlin_credits_${name.removeSuffix(".html")}.json").writeText(json)
            println("CROSSCHECK credits_$name: ${credits.size} entries")
        }
    }

    /** 导出层 dump：网格/清单/ICS（两种校区），供 Python 读回逐行核对 */
    @Test
    fun dumpExports() {
        val parsed = JwxtParser.parseHtml(resOrNull("kb_list_live.html") ?: return) ?: return
        val credits = JwxtParser.parseCredits(resOrNull("kb_loadkb_live.html") ?: "")
        val courses = parsed.courses.map { c ->
            if (c.credit == null || c.credit.isEmpty()) c.copy(credit = credits[c.name]) else c
        }
        val schedule = Schedule(term = parsed.term, termStartMonday = "2026-08-31",
            campus = "xz", courses = courses)
        val dir = File("build/crosscheck").apply { mkdirs() }

        fun dumpRows(fn: String, rows: List<List<String>>) {
            val j = rows.joinToString(",", "[", "]") { r ->
                r.joinToString(",", "[", "]") { cell -> "\"" + esc(cell) + "\"" }
            }
            dir.resolve(fn).writeText(j)
        }
        dumpRows("kotlin_export_grid_xz.json", ExcelExport.gridRows(schedule, CampusTimes.times("xz")))
        dumpRows("kotlin_export_grid_bm.json", ExcelExport.gridRows(schedule, CampusTimes.times("bm")))
        dumpRows("kotlin_export_list.json", ExcelExport.listRows(schedule))
        dir.resolve("kotlin_export_ics_xz.txt")
            .writeText(IcsExport.buildIcs(schedule, "xz", java.time.ZonedDateTime.of(2026, 11, 7, 0, 0, 0, 0, java.time.ZoneId.of("UTC"))))
        dir.resolve("kotlin_export_ics_bm.txt")
            .writeText(IcsExport.buildIcs(schedule, "bm", java.time.ZonedDateTime.of(2026, 11, 7, 0, 0, 0, 0, java.time.ZoneId.of("UTC"))))
        // 课程 JSON 也带学分导出一份（核对学分写入）
        dump("kb_list_live_withcredits", parsed.copy(courses = courses))
        println("CROSSCHECK exports dumped")
    }
}
