package com.njfu.schedule

import java.time.LocalDate
import java.time.LocalTime

/**
 * 桌面小组件用的纯逻辑：算"当前周 / 今天还剩哪些课 / 下一节课"。
 * 全是纯函数（不碰 Android / SharedPreferences），方便单测——沙箱跑不了模拟器，
 * 小组件能不能算对全靠这里挡住。
 */

/** 下一节课 / 今日某节课的小组件视图模型 */
data class NextClass(
    val name: String,
    val location: String,
    val teacher: String,
    val startTime: String,   // "HH:mm"
    val endTime: String,
    val startPeriod: Int,
    val day: Int             // 1=周一..7=周日
)

/** 某节课在指定校区的起止时间（页面没给 startTime 时按校区作息表补） */
private fun courseTimes(c: Course, campus: String): Pair<String, String> {
    val times = CampusTimes.times(campus)
    val s = c.startTime.ifEmpty { times[c.startPeriod]?.first ?: "" }
    val e = c.endTime.ifEmpty { times[c.endPeriod]?.second ?: "" }
    return s to e
}

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

/**
 * 下一节课：从"现在"往后第一门还没开始的课（今天之内）。
 * now 传 null 或今天没课 → 返回 null（调用方显示"今天没课了"）。
 * 只看今天：跨天"明天第一节课"不做——小组件空间小，显示今天即可。
 */
fun nextClass(schedule: Schedule, week: Int, today: Int, now: LocalTime?): NextClass? {
    if (now == null) return null
    val todayList = todayCourses(schedule, week, today)
    val next = todayList.firstOrNull { c ->
        val (s, _) = courseTimes(c, schedule.campus)
        val start = runCatching { LocalTime.parse(s) }.getOrNull()
        start != null && !now.isAfter(start)   // start >= now
    } ?: return null
    val (s, e) = courseTimes(next, schedule.campus)
    return NextClass(
        name = next.name,
        location = next.location,
        teacher = next.teacher,
        startTime = s,
        endTime = e,
        startPeriod = next.startPeriod,
        day = next.day
    )
}

/** 今天还没开始（含正在上）的课数——小组件小卡片显示"今日还剩 N 节" */
fun remainingToday(schedule: Schedule, week: Int, today: Int, now: LocalTime?): Int {
    if (now == null) return todayCourses(schedule, week, today).size
    return todayCourses(schedule, week, today).count { c ->
        val (s, _) = courseTimes(c, schedule.campus)
        val start = runCatching { LocalTime.parse(s) }.getOrNull()
        start == null || !now.isAfter(start)   // 没有时间的（整周安排）也算"还没结束"
    }
}

/** 把 LocalDate 转成"星期几"1..7（周一=1） */
fun isoDay(date: LocalDate): Int = date.dayOfWeek.value
