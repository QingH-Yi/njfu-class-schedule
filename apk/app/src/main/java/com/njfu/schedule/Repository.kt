package com.njfu.schedule

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import org.json.JSONObject
import java.util.UUID

/**
 * 本地存储：课表、账号密码、设置。全部仅存本机。
 */
object Repository {
    private const val FILE = "njfu_schedule"
    private const val K_SCHEDULE = "schedule"
    private const val K_USER = "user"
    private const val K_PASS = "pass"
    private const val K_AUTO = "auto_update"
    private const val K_SAVED_PASS = "saved_pass"

    private val gson = Gson()
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    // JSON 里取字符串：缺失 / null / 类型不对 一律回退默认值。
    // 旧版本或异常数据混进来时在这里统一兜底，不让脏数据进界面。
    private fun jStr(o: JSONObject, key: String): String {
        val v = o.opt(key) ?: return ""
        return if (v == JSONObject.NULL) "" else v.toString()
    }

    // ---- 课表 ----
    fun loadSchedule(): Schedule {
        val json = sp.getString(K_SCHEDULE, null) ?: return Schedule()
        return try {
            val obj = JSONObject(json)
            val arr = obj.optJSONArray("courses")
            val seenIds = HashSet<String>()
            val courses = buildList {
                for (i in 0 until arr.length()) {
                    val r = arr.optJSONObject(i) ?: continue
                    val name = jStr(r, "name").trim()
                    if (name.isEmpty()) continue
                    val start = r.optInt("startPeriod", 1).coerceIn(1, 11)
                    val id0 = jStr(r, "id").ifBlank { UUID.randomUUID().toString().take(8) }
                    add(
                        Course(
                            id = if (seenIds.add(id0)) id0 else UUID.randomUUID().toString().take(8),
                            name = name,
                            teacher = jStr(r, "teacher"),
                            location = jStr(r, "location"),
                            day = r.optInt("day", 0).coerceIn(0, 7),
                            startPeriod = start,
                            endPeriod = r.optInt("endPeriod", start).coerceIn(1, 11).coerceAtLeast(start),
                            weeks = r.optJSONArray("weeks")?.let { w ->
                                (0 until w.length()).mapNotNull { k ->
                                    val v = w.optInt(k, -1)
                                    if (v in 1..53) v else null
                                }.distinct().sorted()
                            }.orEmpty(),
                            startTime = jStr(r, "startTime"),
                            endTime = jStr(r, "endTime"),
                            note = jStr(r, "note"),
                            credit = jStr(r, "credit")
                        )
                    )
                }
            }
            Schedule(
                term = jStr(obj, "term"),
                termStartMonday = jStr(obj, "termStartMonday").ifBlank { Schedule().termStartMonday },
                campus = jStr(obj, "campus").ifBlank { "xz" },
                courses = courses
            )
        } catch (e: Exception) {
            Schedule()
        }
    }

    fun saveSchedule(s: Schedule) {
        sp.edit().putString(K_SCHEDULE, gson.toJson(s)).apply()
    }

    fun addCourses(newCourses: List<Course>, term: String): Int {
        val cur = loadSchedule()
        val list = cur.courses.toMutableList()
        var added = 0
        for (nc in newCourses) {
            val dup = list.firstOrNull {
                it.name == nc.name && it.day == nc.day && it.startPeriod == nc.startPeriod
            }
            if (dup == null) { list.add(nc); added++ }
        }
        val newTerm = if (term.isNotEmpty() && cur.term.isEmpty()) term else cur.term
        saveSchedule(cur.copy(term = newTerm, courses = list))
        return added
    }

    fun replaceCourses(newCourses: List<Course>, term: String) {
        val cur = loadSchedule()
        // 整表覆盖时保留用户写的备忘录和学分（按 名称|星期|起始节 匹配旧课）
        val meta = cur.courses.associate {
            val k = "${it.name}|${it.day}|${it.startPeriod}"
            k to ((it.note ?: "") to (it.credit ?: ""))
        }
        val merged = newCourses.map {
            val m = meta["${it.name}|${it.day}|${it.startPeriod}"]
            it.copy(note = m?.first ?: "", credit = m?.second ?: "")
        }
        saveSchedule(
            cur.copy(
                term = if (term.isNotEmpty()) term else cur.term,
                courses = merged
            )
        )
    }

    /** 更新某门课的备忘录与学分 */
    fun updateCourseMeta(id: String, note: String, credit: String) {
        val cur = loadSchedule()
        saveSchedule(cur.copy(courses = cur.courses.map {
            if (it.id == id) it.copy(note = note, credit = credit) else it
        }))
    }

    /** 学分三轮抓取合并：仅采纳三轮结果完全一致的课名（防爬错） */
    fun mergeCreditsRounds(rounds: List<Map<String, String>>): Map<String, String> {
        if (rounds.size != 3) return emptyMap()
        val first = rounds[0]
        if (first.isEmpty()) return emptyMap()
        return first.filter { (k, v) ->
            v.isNotEmpty() && rounds.all { it[k] == v }
        }
    }

    /** 把爬到的学分按课名写入课表，返回写入条数。
     *  课名匹配：先精确（去空格），再把 loadkb 课名尾部的分组括号逐层剥掉兜底
     *  （如「体育(5)（舞龙）」可匹配课表里的「体育(5)」「体育」） */
    fun applyCredits(credits: Map<String, String>): Int {
        if (credits.isEmpty()) return 0
        val cur = loadSchedule()
        val norm = { s: String -> s.replace(" ", "").replace("\u00A0", "") }
        val tail = Regex("[（(][^（()）]*[)）]\\s*$")
        val byNorm = HashMap<String, String>()
        for ((name, v) in credits) {
            var cur = name
            byNorm.putIfAbsent(norm(cur), v)
            while (true) {
                val m = tail.find(cur) ?: break
                cur = cur.substring(0, m.range.first).trim()
                if (cur.isEmpty()) break
                byNorm.putIfAbsent(norm(cur), v)
            }
        }
        var n = 0
        val list = cur.courses.map { c ->
            val v = byNorm[norm(c.name)]
            if (v != null && v.isNotEmpty()) { n++; c.copy(credit = v) } else c
        }
        if (n > 0) saveSchedule(cur.copy(courses = list))
        return n
    }

    // ---- 账号 ----
    var user: String
        get() = sp.getString(K_USER, "") ?: ""
        set(v) { sp.edit().putString(K_USER, v).apply() }
    var pass: String
        get() = sp.getString(K_PASS, "") ?: ""
        set(v) { sp.edit().putString(K_PASS, v).apply() }
    var savedPass: Boolean
        get() = sp.getBoolean(K_SAVED_PASS, false)
        set(v) { sp.edit().putBoolean(K_SAVED_PASS, v).apply() }
    var autoUpdate: Boolean
        get() = sp.getBoolean(K_AUTO, true)
        set(v) { sp.edit().putBoolean(K_AUTO, v).apply() }

    /** 手动指定的当前教学周，0 = 自动按日期推算 */
    var weekOverride: Int
        get() = sp.getInt("week_override", 0)
        set(v) { sp.edit().putInt("week_override", v).apply() }

    // ---- 外观/主题 ----
    /** auto=跟随系统 light=浅色 dark=深色 */
    var themeMode: String
        get() = sp.getString("theme_mode", "auto") ?: "auto"
        set(v) { sp.edit().putString("theme_mode", v).apply() }

    /** 色系编号：0马卡龙 1海盐 2樱花 3抹茶 */
    var paletteId: Int
        get() = sp.getInt("palette_id", 0)
        set(v) { sp.edit().putInt("palette_id", v).apply() }

    /** 自定义色相 0-359，-1=不使用（用内置色系） */
    var customHue: Int
        get() = sp.getInt("custom_hue", -1)
        set(v) { sp.edit().putInt("custom_hue", v).apply() }

    /** 反色：在当前明暗基础上翻转 */
    var invert: Boolean
        get() = sp.getBoolean("invert", false)
        set(v) { sp.edit().putBoolean("invert", v).apply() }

    /** 一周起点：false=周一（默认），true=周日 */
    var weekStartSunday: Boolean
        get() = sp.getBoolean("week_start_sunday", false)
        set(v) { sp.edit().putBoolean("week_start_sunday", v).apply() }

    /** 教务地址（默认空=用内置课表页地址，可在设置里改） */
    var jwUrl: String
        get() = sp.getString("jw_url", "") ?: ""
        set(v) { sp.edit().putString("jw_url", v).apply() }

    /** 首次使用教程：true=下次启动显示 */
    var showTutorial: Boolean
        get() = sp.getBoolean("show_tutorial", true)
        set(v) { sp.edit().putBoolean("show_tutorial", v).apply() }

    /** 打开时后台检查新版本（GitHub Release），失败静默 */
    var checkUpdate: Boolean
        get() = sp.getBoolean("check_update", true)
        set(v) { sp.edit().putBoolean("check_update", v).apply() }

    fun hasCredentials(): Boolean = user.isNotEmpty() && savedPass && pass.isNotEmpty()
}
