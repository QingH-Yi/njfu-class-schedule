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

/** 课程的存储位置键：名称|星期|起始节（与 replaceCourses 的保留/匹配键一致） */
fun courseKeyOf(c: Course): String = "${c.name}|${c.day}|${c.startPeriod}"

/**
 * 数据修复：同一位置（名称/星期/起止节/老师/教室/类型 全同）出现多条课时，合并成一条、周次取并集。
 *
 * 背景："仅本周"编辑/删除会在同位置拆出多条不同周次的课；旧版本 bug 或异常数据也可能产生
 * 重复条目。重复条目在课表网格里只会显示一条（另一条被重叠裁剪悄悄吞掉），看起来就是
 * "课程有时有、有时没有"。入库/加载时并成一条，显示层就稳定了。
 * 老师或教室不同的不算重复（可能真是两门并排课），不动。
 */
fun unionSameSlotCourses(courses: List<Course>): List<Course> {
    if (courses.size < 2) return courses
    val out = ArrayList<Course>(courses.size)
    val index = HashMap<String, Int>()
    for (c in courses) {
        val key = "${c.name.trim()}|${c.day}|${c.startPeriod}|${c.endPeriod}|" +
                "${c.teacher.trim()}|${c.location.trim()}|${c.kind}"
        val at = index[key]
        if (at == null) {
            index[key] = out.size
            out.add(c)
        } else {
            val prev = out[at]
            out[at] = prev.copy(
                weeks = if (prev.weeks == c.weeks) prev.weeks
                else (prev.weeks + c.weeks).distinct().sorted(),
                edited = prev.edited || c.edited,
                note = prev.note?.takeIf { it.isNotEmpty() } ?: c.note,
                credit = prev.credit?.takeIf { it.isNotEmpty() } ?: c.credit
            )
        }
    }
    return out
}


