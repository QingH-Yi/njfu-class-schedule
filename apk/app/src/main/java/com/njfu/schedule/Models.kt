package com.njfu.schedule

/**
 * 一门课。字段与网页版/教务解析保持一致。
 */
data class Course(
    val id: String,
    val name: String,
    val teacher: String,
    val location: String,
    val day: Int,          // 1=周一 … 7=周日，0=整周（实习/实验，见 kind）
    val startPeriod: Int,
    val endPeriod: Int,
    val weeks: List<Int>,  // 上课周次
    val startTime: String = "",
    val endTime: String = "",
    val note: String? = null,  // 用户备忘录（仅存本机；旧数据无此字段，Gson 会填 null）
    val credit: String? = null, // 学分（用户自填）
    // day=0 时区分："" 或 "intern"=实习（盖周一~周五第1-8节大卡片）；"lab"=实验（第11节下方加一行）
    val kind: String = "",
    // 是否被用户手动编辑过（重抓时据此询问是否保留）
    val edited: Boolean = false
) {
    val isIntern: Boolean get() = day == 0 && kind != "lab"
    val isLab: Boolean get() = day == 0 && kind == "lab"
}

/** 一学期课表 + 元信息 */
data class Schedule(
    val term: String = "",
    val termStartMonday: String = "2026-08-31",
    val campus: String = "xz",   // xz=新庄/ha=淮安/bm=白马
    val courses: List<Course> = emptyList()
)

/** 解析结果 */
data class ParseResult(val term: String, val courses: List<Course>)

/** 三校区作息时间（"节次" -> "HH:mm"） */
object CampusTimes {
    val XZ = mapOf(
        1 to ("08:00" to "08:45"), 2 to ("08:55" to "09:40"),
        3 to ("10:00" to "10:45"), 4 to ("10:55" to "11:40"),
        5 to ("14:00" to "14:45"), 6 to ("14:50" to "15:35"),
        7 to ("15:55" to "16:40"), 8 to ("16:45" to "17:30"),
        9 to ("18:30" to "19:15"), 10 to ("19:20" to "20:05"),
        11 to ("20:10" to "20:55")
    )
    val BM = mapOf(
        1 to ("08:30" to "09:15"), 2 to ("09:20" to "10:05"),
        3 to ("10:25" to "11:10"), 4 to ("11:15" to "12:00"),
        5 to ("14:00" to "14:45"), 6 to ("14:50" to "15:35"),
        7 to ("15:55" to "16:40"), 8 to ("16:45" to "17:30"),
        9 to ("18:30" to "19:15"), 10 to ("19:20" to "20:05"),
        11 to ("20:10" to "20:55")
    )
    // 淮安与新庄作息相同
    fun times(campus: String): Map<Int, Pair<String, String>> =
        if (campus == "bm") BM else XZ

    val labels = mapOf("xz" to "新庄(本部)", "ha" to "淮安", "bm" to "白马")
}
