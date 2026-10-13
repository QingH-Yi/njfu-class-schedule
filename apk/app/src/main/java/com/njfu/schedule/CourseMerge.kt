package com.njfu.schedule

/**
 * 同名课程的纯逻辑：① 竖向相邻合并（显示用，不改数据）；② 本周同名课查找（编辑联动用）。
 * 全部不碰 Android / SharedPreferences，方便单测。
 */

/**
 * 把一天里"同名且上下相邻（上一节结束正好接下一节开始）"的课合并成一个跨节大格。
 * 仅用于【显示】；不改动存储数据。返回的 Course 取第一段的名称/老师/教室，节次跨到末段。
 *
 * 例：高数 1-2 节 + 高数 3-4 节（同名、紧挨着）→ 合成一个 1-4 节的大格。
 * 中间有空档（如 1-2 与 5-6）或名字不同则不合。
 */
fun mergeSameNameVertical(dayCourses: List<Course>): List<Course> {
    if (dayCourses.size < 2) return dayCourses
    val sorted = dayCourses.sortedWith(compareBy({ it.startPeriod }, { it.endPeriod }))
    val out = ArrayList<Course>(sorted.size)
    for (c in sorted) {
        val last = out.lastOrNull()
        if (last != null &&
            last.day == c.day &&
            last.name.trim() == c.name.trim() &&
            last.endPeriod + 1 == c.startPeriod
        ) {
            out[out.size - 1] = last.copy(endPeriod = maxOf(last.endPeriod, c.endPeriod))
        } else {
            out.add(c)
        }
    }
    return out
}

/**
 * 本周内与 target 同名的所有课（同名判定：去首尾空格后完全相等）。
 * 用于编辑联动：改一节 → 本周所有同名课一起改。
 * 含整周安排（day=0）；周次为空的课视为全学期、总是命中。
 */
fun sameNameCoursesThisWeek(schedule: Schedule, target: Course, week: Int): List<Course> {
    val key = target.name.trim()
    if (key.isEmpty()) return emptyList()
    return schedule.courses.filter {
        it.name.trim() == key && (it.weeks.isEmpty() || week in it.weeks)
    }
}

/**
 * 一组课程在本周的"完整时段集合"（用于联动编辑时把整门课的时段预填进选择器）。
 * 只取普通课（day != 0）；整周安排不占具体时段，忽略。
 */
fun unionSlots(courses: List<Course>): Set<Pair<Int, Int>> =
    courses.filter { it.day != 0 }
        .flatMap { c -> (c.startPeriod..c.endPeriod).map { c.day to it } }
        .toSet()


