package com.njfu.schedule

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson

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

    // ---- 课表 ----
    fun loadSchedule(): Schedule {
        val json = sp.getString(K_SCHEDULE, null) ?: return Schedule()
        return try { gson.fromJson(json, Schedule::class.java) ?: Schedule() } catch (e: Exception) { Schedule() }
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
        // 整表覆盖时保留用户写的备忘录（按 名称|星期|起始节 匹配旧课）
        val notes = cur.courses.associate { "${it.name}|${it.day}|${it.startPeriod}" to it.note }
        val merged = newCourses.map {
            it.copy(note = notes["${it.name}|${it.day}|${it.startPeriod}"] ?: "")
        }
        saveSchedule(
            cur.copy(
                term = if (term.isNotEmpty()) term else cur.term,
                courses = merged
            )
        )
    }

    /** 更新某门课的备忘录 */
    fun updateNote(id: String, note: String) {
        val cur = loadSchedule()
        saveSchedule(cur.copy(courses = cur.courses.map {
            if (it.id == id) it.copy(note = note) else it
        }))
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

    fun hasCredentials(): Boolean = user.isNotEmpty() && savedPass && pass.isNotEmpty()
}
