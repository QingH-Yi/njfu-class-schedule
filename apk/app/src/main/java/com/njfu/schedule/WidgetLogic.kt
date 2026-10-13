package com.njfu.schedule

import java.time.LocalDate
import java.time.LocalTime

/**
 * 桌面小组件用的纯逻辑：算"当前周 / 今天还剩哪些课 / 正在上的课 / 下一节课 / 下次刷新时刻"。
 * 全是纯函数（不碰 Android / SharedPreferences），方便单测——小组件能不能算对全靠这里挡住。
 */

/** 正在上 / 即将上的某节课的小组件视图模型 */
data class NextClass(
    val name: String,
    val location: String,
    val teacher: String,
    val startTime: String,   // "HH:mm"
    val endTime: String,
    val startPeriod: Int,
    val endPeriod: Int,
    val day: Int,            // 1=周一..7=周日
    val key: String,         // 点卡片跳详情用的稳定标识（名称|星期|起始节）
    val ongoing: Boolean     // true=此刻正在上
)

/** 某节课在指定校区的起止时间（页面没给 startTime 时按校区作息表补） */
private fun courseTimes(c: Course, campus: String): Pair<String, String> {
    val times = CampusTimes.times(campus)
    val s = c.startTime.ifEmpty { times[c.startPeriod]?.first ?: "" }
    val e = c.endTime.ifEmpty { times[c.endPeriod]?.second ?: "" }
    return s to e
}

/** 解析 "HH:mm"；失败返回 null（脏数据不让小组件崩） */
internal fun parseTime(s: String): LocalTime? = runCatching { LocalTime.parse(s) }.getOrNull()

/** 点卡片跳详情用的稳定标识：整表覆盖会重排 id，所以用 名称|星期|起始节 匹配 */
fun courseKey(c: Course): String = "${c.name}|${c.day}|${c.startPeriod}"

/** 某门课是否在给定周次上课（空 weeks = 全学期） */
fun courseInWeek(c: Course, week: Int): Boolean = c.weeks.isEmpty() || week in c.weeks

/**
 * 今天（day 1..7）要上的课，按开始时间升序；含整周安排（day=0）——它们不占具体时段，
 * 放在"今天"列表里靠后（无具体时间）。
 */
fun todayCourses(schedule: Schedule, week: Int, today: Int): List<Course> =
    schedule.courses
        .filter { it.day == today && courseInWeek(it, week) }
        .sortedWith(compareBy({ it.startPeriod }, { it.name }))

/** 把一门课转成小组件视图模型（含 ongoing 标记） */
private fun toNextClass(c: Course, campus: String, ongoing: Boolean): NextClass {
    val (s, e) = courseTimes(c, campus)
    return NextClass(
        name = c.name,
        location = c.location,
        teacher = c.teacher,
        startTime = s,
        endTime = e,
        startPeriod = c.startPeriod,
        endPeriod = c.endPeriod,
        day = c.day,
        key = courseKey(c),
        ongoing = ongoing
    )
}

/**
 * 此刻正在上的课（start <= now <= end）。多门重叠时取最早开始的。
 * now 为空 / 今天没课 / 已下课 → null。
 */
fun currentClass(schedule: Schedule, week: Int, today: Int, now: LocalTime?): NextClass? {
    if (now == null) return null
    return todayCourses(schedule, week, today)
        .firstOrNull { c ->
            val (s, e) = courseTimes(c, schedule.campus)
            val start = parseTime(s)
            val end = parseTime(e)
            start != null && end != null && !now.isBefore(start) && !now.isAfter(end)
        }
        ?.let { toNextClass(it, schedule.campus, ongoing = true) }
}

/**
 * 下一节课：从"现在"往后第一门还没开始的课（今天之内）。
 * 若此刻正在上课，则返回"正在上"的那门（ongoing=true），方便小卡片显示"正在上"；
 * now 传 null 或今天没课 → 返回 null（调用方显示"今天没课了"）。
 * 只看今天：跨天"明天第一节课"不做——小组件空间小，显示今天即可。
 */
fun nextClass(schedule: Schedule, week: Int, today: Int, now: LocalTime?): NextClass? {
    if (now == null) return null
    currentClass(schedule, week, today, now)?.let { return it }
    val todayList = todayCourses(schedule, week, today)
    val next = todayList.firstOrNull { c ->
        val (s, _) = courseTimes(c, schedule.campus)
        val start = parseTime(s)
        start != null && now.isBefore(start)   // start > now
    } ?: return null
    return toNextClass(next, schedule.campus, ongoing = false)
}

/** 今天还没开始（含正在上）的课数——小组件小卡片显示"今日还剩 N 节" */
fun remainingToday(schedule: Schedule, week: Int, today: Int, now: LocalTime?): Int {
    if (now == null) return todayCourses(schedule, week, today).size
    return todayCourses(schedule, week, today).count { c ->
        val (_, e) = courseTimes(c, schedule.campus)
        val end = parseTime(e)
        end == null || !now.isAfter(end)   // 还没结束的（含正在上/无时间的）都算
    }
}

/**
 * 小组件下次该刷新的时刻（今天之内），用于精准刷新"到点自动切换"。
 * 返回未来最近的边界：下一节课开始 / 当前课结束。
 * 今天没有更多边界 → null（调用方改排"次日 0 点"兜底）。
 */
fun nextRefreshTime(schedule: Schedule, week: Int, today: Int, now: LocalTime?): LocalTime? {
    if (now == null) return null
    val boundaries = ArrayList<LocalTime>()
    for (c in todayCourses(schedule, week, today)) {
        val (s, e) = courseTimes(c, schedule.campus)
        parseTime(s)?.let { if (it.isAfter(now)) boundaries.add(it) }
        parseTime(e)?.let { if (it.isAfter(now)) boundaries.add(it) }
    }
    return boundaries.minOrNull()
}

/** 把 LocalDate 转成"星期几"1..7（周一=1） */
fun isoDay(date: LocalDate): Int = date.dayOfWeek.value
