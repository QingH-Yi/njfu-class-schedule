package com.njfu.schedule

import java.time.format.DateTimeFormatter

/**
 * 各 Activity / 小组件共用的纯工具：JS 转义、课表签名、星期名、周次格式化。
 * 原先这些在两个 Activity 里各写了一份，容易改一处漏一处，统一到这里。
 */

/** 学期起始日等日期统一用这个格式（顶层常量，避免每次解析都重新编译 formatter） */
val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/** 把字符串安全地嵌入页面 JS 的单引号字面量（登录脚本、抓取脚本共用） */
fun jsStr(s: String): String = s
    .replace("\\", "\\\\").replace("'", "\\'")
    .replace("\n", "\\n").replace("\r", "")

/** 课表规范化签名：三轮结果签名一致才算没抓错 */
fun tableSignature(r: ParseResult): String = buildString {
    append(r.term)
    r.courses.sortedWith(compareBy({ it.day }, { it.startPeriod }, { it.name })).forEach {
        append("|${it.name}#${it.day}#${it.startPeriod}#${it.endPeriod}#${it.teacher}#${it.location}#${it.weeks.joinToString(",")}")
    }
}

// 星期名表（下标 1..7 = 周一..周日，下标 0 占位）
private val DAY_FULL = listOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")
/** 日期条用的单字星期名（下标 0..6 = 周一..周日） */
val DAY_SHORT = listOf("一", "二", "三", "四", "五", "六", "日")

/** "周一"（越界/0 一律夹到周一，与旧行为一致） */
fun dayName(d: Int): String = DAY_FULL[d.coerceIn(1, 7)]

/** 日期条用的"一"（单字）；输入 1..7 = 周一..周日 */
fun dayNameShort(d: Int): String = DAY_SHORT[d.coerceIn(1, 7) - 1]

/**
 * 把周次列表压缩成 "1-3,9-10" 这样的文本。
 * suffix="周" 时得到 "1-3,9-10周"（展示用）；空列表时用 empty 兜底。
 */
fun formatWeeks(weeks: List<Int>, suffix: String = "", empty: String = ""): String {
    if (weeks.isEmpty()) return empty
    val w = weeks.sorted()
    val ranges = mutableListOf<Pair<Int, Int>>()
    var s = w[0]; var prev = w[0]
    for (i in 1 until w.size) {
        if (w[i] == prev + 1) prev = w[i] else { ranges.add(s to prev); s = w[i]; prev = w[i] }
    }
    ranges.add(s to prev)
    return ranges.joinToString(",") { (a, b) -> if (a == b) "$a" else "$a-$b" } + suffix
}

/** 展示用："1-3,9-10周"；空=全学期 */
fun weekLabel(weeks: List<Int>): String = formatWeeks(weeks, suffix = "周", empty = "全学期")

/** 编辑框用："1-16,18"；空="" */
fun weeksToText(weeks: List<Int>): String = formatWeeks(weeks)
