package com.njfu.schedule

import java.util.UUID

/**
 * WakeUp 课表标准 CSV 格式（国内课表 App 生态通用交换格式）：
 *   课程名称,星期,开始节数,结束节数,老师,地点,周数
 * 规则（wakeup.fun/doc/import_from_csv.html）：
 *   - 星期/节次为数字（1=周一…7=周日），一门课一行；
 *   - 老师或地点没有写「无」；
 *   - 周数间断用中文顿号「、」分隔，全单周/全双周在末尾写「单」/「双」（如 1-5、7-11单）。
 * 支持 导出（可导入 WakeUp 课表等 App）和 导入（从其它课表 App 迁移过来）。
 */
object WakeCsv {

    val HEADER = listOf("课程名称", "星期", "开始节数", "结束节数", "老师", "地点", "周数")

    /** 周次列表 → WakeUp 周数文本："1-5、7-11单" 风格。空周次（=全学期）导出为 1-20 */
    fun weeksToWakeText(weeks: List<Int>): String {
        if (weeks.isEmpty()) return "1-20"
        val w = weeks.sorted()
        val base = formatWeeks(w).replace(",", "、")
        return when {
            w.all { it % 2 == 1 } -> "${base}单"
            w.all { it % 2 == 0 } -> "${base}双"
            else -> base
        }
    }

    /** 生成 CSV 文本（UTF-8 BOM 开头 + CRLF，Excel/WakeUp 都能正确识别中文） */
    fun buildCsv(schedule: Schedule): String {
        val sb = StringBuilder("\uFEFF")
        sb.append(HEADER.joinToString(",")).append("\r\n")
        // WakeUp 格式是"一格一时刻"的网格交换格式，整周安排（day=0）不占具体时段，导不进去，跳过
        val courses = schedule.courses.filter { it.day != 0 }
            .sortedWith(compareBy({ it.day }, { it.startPeriod }, { it.name }))
        for (c in courses) {
            sb.append(
                listOf(
                    c.name,
                    c.day.toString(),
                    c.startPeriod.toString(),
                    c.endPeriod.toString(),
                    c.teacher.ifBlank { "无" },
                    c.location.ifBlank { "无" },
                    weeksToWakeText(c.weeks)
                ).joinToString(",") { field(it) }
            ).append("\r\n")
        }
        return sb.toString()
    }

    /** 解析 WakeUp CSV → (课程列表, 跳过的无效行数) */
    fun parseCsv(text: String): Pair<List<Course>, Int> {
        val rows = splitRows(text)
        var skipped = 0
        val out = ArrayList<Course>()
        for (r in rows.drop(1)) {   // 第一行是表头
            if (r.all { it.isBlank() }) continue
            val f = r.map { it.trim() }
            val name = f.getOrNull(0).orEmpty()
            val day = f.getOrNull(1)?.toIntOrNull() ?: -1
            val sp = f.getOrNull(2)?.toIntOrNull() ?: -1
            val ep0 = f.getOrNull(3)?.toIntOrNull() ?: -1
            val weeksText = f.getOrNull(6).orEmpty()
            val weeks = parseWeekNumbers(weeksText, weeksText.contains("单"), weeksText.contains("双"))
            val ep = if (ep0 < sp) sp else ep0
            if (name.isEmpty() || day !in 1..7 || sp !in 1..11 || ep !in 1..11 || weeks.isEmpty()) {
                skipped++
                continue
            }
            out.add(
                Course(
                    id = UUID.randomUUID().toString().take(8),
                    name = name,
                    teacher = unWake(f.getOrNull(4)),
                    location = unWake(f.getOrNull(5)),
                    day = day, startPeriod = sp, endPeriod = ep,
                    weeks = weeks
                )
            )
        }
        return out to skipped
    }

    /** WakeUp 的「无」= 没有数据，转成空串（不编造，进 App 后显「——」可自己填） */
    private fun unWake(s: String?): String = s?.trim()?.takeUnless { it.isEmpty() || it == "无" } ?: ""

    // ---- CSV 基础（带引号转义的字段解析，兼容 WPS/Excel 导出的文件） ----

    private fun field(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
            "\"${s.replace("\"", "\"\"")}\"" else s

    private fun splitRows(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        val fieldBuf = StringBuilder()
        val row = ArrayList<String>()
        var inQuotes = false
        var i = 0
        val t = text.removePrefix("\uFEFF")
        while (i < t.length) {
            val c = t[i]
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < t.length && t[i + 1] == '"' -> { fieldBuf.append('"'); i++ }
                    c == '"' -> inQuotes = false
                    else -> fieldBuf.append(c)
                }
                c == '"' -> inQuotes = true
                c == ',' -> { row.add(fieldBuf.toString()); fieldBuf.clear() }
                c == '\r' -> {}
                c == '\n' -> { row.add(fieldBuf.toString()); fieldBuf.clear(); rows.add(row.toList()); row.clear() }
                else -> fieldBuf.append(c)
            }
            i++
        }
        if (fieldBuf.isNotEmpty() || row.isNotEmpty()) {
            row.add(fieldBuf.toString())
            rows.add(row.toList())
        }
        return rows
    }
}
