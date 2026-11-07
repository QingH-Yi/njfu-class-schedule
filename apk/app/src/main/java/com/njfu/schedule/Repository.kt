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
    private const val K_DELETED = "deleted_keys"
    private const val K_DELETED_META = "deleted_meta"

    private val gson = Gson()
    private lateinit var sp: SharedPreferences
    // 最近一次成功解析的课表。存储 JSON 损坏时用它兜底：
    // 若直接返回空 Schedule，后续任何 saveSchedule（切校区/存设置）都会把空课表写回 = 清库。
    private var lastGoodSchedule: Schedule? = null

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
        val parsed = try {
            parseScheduleJson(json)
        } catch (e: Exception) {
            null
        }
        if (parsed == null) {
            // 坏 JSON 留底供找回；返回最近一次成功解析的课表，绝不让"空课表"流进界面
            sp.edit().putString("schedule_corrupt", json).apply()
            return lastGoodSchedule ?: Schedule()
        }
        lastGoodSchedule = parsed
        return parsed
    }

    private fun parseScheduleJson(json: String): Schedule {
        val obj = JSONObject(json)
        val arr = obj.optJSONArray("courses") ?: throw IllegalStateException("no courses array")
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
                        credit = jStr(r, "credit"),
                        kind = jStr(r, "kind"),
                        edited = r.optBoolean("edited", false)
                    )
                )
            }
        }
        return Schedule(
            term = jStr(obj, "term"),
            termStartMonday = jStr(obj, "termStartMonday").ifBlank { Schedule().termStartMonday },
            campus = jStr(obj, "campus").ifBlank { "xz" },
            // 同位置重复的课合并周次：重复条目在网格里只显示一条、另一条被吞，
            // 表现为"课程时有时无"。加载时修一次，旧数据里的历史重复也随之消失。
            courses = unionSameSlotCourses(courses)
        )
    }

    /** 按 id 找课；找不到（后台重抓会重新生成 id，弹窗开着时数据可能被覆盖）按 名称|星期|起始节 兜底 */
    private fun findCourseIndex(courses: List<Course>, course: Course): Int {
        val byId = courses.indexOfFirst { it.id == course.id }
        if (byId >= 0) return byId
        return courses.indexOfFirst { courseKeyOf(it) == courseKeyOf(course) }
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
            if (dup == null) {
                // 手动加的课视为"用户编辑过"：重抓/自动更新时默认保留，不会被教务数据悄悄覆盖掉
                list.add(nc.copy(edited = true)); added++
                // 同位置的"已删除"记录一并撤销（删了又手动加回来 = 用户要它）
                forgetDeletedKeys(listOf(courseKeyOf(nc)))
            }
        }
        val newTerm = if (term.isNotEmpty() && cur.term.isEmpty()) term else cur.term
        saveSchedule(cur.copy(term = newTerm, courses = list))
        return added
    }

    fun replaceCourses(newCourses: List<Course>, term: String, keepEdited: Boolean = false) {
        val cur = loadSchedule()
        // 整表覆盖时保留用户写的备忘录和学分（按 名称|星期|起始节 匹配旧课）
        val meta = cur.courses.associate {
            val k = "${it.name}|${it.day}|${it.startPeriod}"
            k to ((it.note ?: "") to (it.credit ?: ""))
        }
        var merged = newCourses.map {
            val m = meta["${it.name}|${it.day}|${it.startPeriod}"]
            it.copy(note = m?.first ?: "", credit = m?.second ?: "")
        }
        if (keepEdited) {
            // 保留用户手动改过的课：教务里被某条手改课"占据"的课不导入，再把手改的追加回去。
            // 占据 = 同名 + 同星期 + 周次有交集（同一门课的同一批周次）。
            // 不能只按 名称|星期|起始节 键挡：挪过节次的课会漏（教务旧位置"幽灵课"被导回）；
            // 也不能要求时段重叠：挪槽后新旧时段本来就不重叠。周次不交集的同名课（单双周
            // 不同老师）不受影响。
            val edited = cur.courses.filter { it.edited }
            if (edited.isNotEmpty()) {
                merged = merged.filter { nw ->
                    edited.none { e ->
                        e.name.trim() == nw.name.trim() && e.day == nw.day &&
                            (e.weeks.isEmpty() || nw.weeks.isEmpty() ||
                                e.weeks.intersect(nw.weeks.toSet()).isNotEmpty())
                    }
                } + edited
            }
            // 用户整学期删掉的课：抓回来的同位置课不再导入，否则"删了又回来"
            val dk = deletedKeys()
            if (dk.isNotEmpty()) merged = merged.filter { courseKeyOf(it) !in dk }
        } else {
            // 按教务数据重置：删过的课若选择恢复，把墓碑存档的备忘录/学分原样带回来
            val meta = deletedMeta()
            if (meta.isNotEmpty()) {
                merged = merged.map { c ->
                    meta[courseKeyOf(c)]?.let { (n, cr) ->
                        c.copy(
                            note = n.ifEmpty { c.note },
                            credit = cr.ifEmpty { c.credit }
                        )
                    } ?: c
                }
            }
            // 已删记录一并清掉
            clearDeletedKeys()
            sp.edit().remove(K_DELETED_META).apply()
        }
        saveSchedule(
            cur.copy(
                term = if (term.isNotEmpty()) term else cur.term,
                courses = merged
            )
        )
    }

    /** 被用户手动编辑过的课程 */
    fun editedCourses(): List<Course> = loadSchedule().courses.filter { it.edited }

    /** 更新某门课的备忘录与学分（id 找不到时按 名称|星期|起始节 兜底，防重抓换 id 后保存静默丢失） */
    fun updateCourseMeta(course: Course, note: String, credit: String) {
        val cur = loadSchedule()
        val idx = findCourseIndex(cur.courses, course)
        if (idx < 0) return
        saveSchedule(cur.copy(courses = cur.courses.mapIndexed { i, c ->
            if (i == idx) c.copy(note = note, credit = credit) else c
        }))
    }

    /**
     * 编辑课程（名称/老师/教室/节次/周次）。
     * onlyThisWeek=false：整学期都改（直接改这门课）。
     * onlyThisWeek=true ：只改本周 —— 把这周从原课周次里拆出来，单独生成一条改了内容的课。
     * 改过之后标记 edited=true，重抓时好询问用户是否保留。
     */
    private fun normSlots(slots: List<Triple<Int, Int, Int>>): List<Triple<Int, Int, Int>> =
        slots.map {
            Triple(it.first.coerceIn(1, 7), minOf(it.second, it.third).coerceIn(1, 11), maxOf(it.second, it.third).coerceIn(1, 11))
        }.distinct()

    fun editCourse(
        course: Course, name: String, teacher: String, location: String, credit: String? = null,
        slots: List<Triple<Int, Int, Int>>, weeks: List<Int>, onlyThisWeek: Boolean, currentWeek: Int
    ) {
        val cur = loadSchedule()
        val idx = findCourseIndex(cur.courses, course)
        if (idx < 0) return
        val nm = name.trim()
        val tc = teacher.trim()   // 留空 = 保留原值（下方 per-course 兜底）
        val lo = location.trim()
        // 学分：credit=null（未提供）= 保留原值；传空串 = 清掉学分（编辑框清空保存）
        val cr0 = credit?.trim()
        val ws = weeks.filter { it in 1..53 }.distinct().sorted()
        val norm = normSlots(slots)
        val out = ArrayList<Course>()
        cur.courses.forEachIndexed { i, c ->
            if (i != idx) { out.add(c); return@forEachIndexed }
            val nm2 = nm.ifEmpty { c.name }
            val tc2 = tc.ifEmpty { c.teacher }
            val lo2 = lo.ifEmpty { c.location }
            val cr2 = if (cr0 == null) c.credit else cr0.ifEmpty { null }
            val isWholeWeek = c.day == 0 && norm.isEmpty()   // 整周课且没选时间 → 保持整周
            when {
                isWholeWeek -> out.add(c.copy(name = nm2, teacher = tc2, location = lo2, credit = cr2, weeks = ws, edited = true))
                norm.isEmpty() -> out.add(c)
                !onlyThisWeek -> {
                    val f = norm.first()
                    out.add(c.copy(name = nm2, teacher = tc2, location = lo2, credit = cr2, day = f.first,
                        startPeriod = f.second, endPeriod = f.third, weeks = ws, edited = true))
                    for (sl in norm.drop(1)) out.add(c.copy(id = UUID.randomUUID().toString().take(8),
                        name = nm2, teacher = tc2, location = lo2, credit = cr2, day = sl.first,
                        startPeriod = sl.second, endPeriod = sl.third, weeks = ws, edited = true))
                }
                else -> {
                    val origWeeks = if (c.weeks.isEmpty()) (1..53).toList() else c.weeks
                    val remain = origWeeks.filter { it != currentWeek }
                    // 其余周次保留的那条也要标 edited：不标的话重抓/自动更新会把完整周次覆盖回来，
                    // 本周"拆出去"的课和它重叠 → 课程显示时有时无（用户报的显示混乱根因之一）
                    if (remain.isNotEmpty()) out.add(c.copy(weeks = remain.sorted(), edited = true))
                    for (sl in norm) out.add(c.copy(id = UUID.randomUUID().toString().take(8),
                        name = nm2, teacher = tc2, location = lo2, credit = cr2, day = sl.first,
                        startPeriod = sl.second, endPeriod = sl.third, weeks = listOf(currentWeek), edited = true))
                }
            }
        }
        saveSchedule(cur.copy(courses = out))
    }

    /**
     * 同名课程同步修改：本周内所有同名课当作"同一门课"一起改。
     * 用户在任意一节上点编辑 → 改名称/老师/教室/时段，本周内其余同名课同步更新。
     *
     * - 名称/老师/教室：组内所有课一起改。
     * - 时段（slots）：作为整门课的新时段，套用到组内所有课（多节 → 生成多条，day/节次来自 slots）。
     * - onlyThisWeek=true：其它周次保留（同步改名/师/室），只把本周换成新时段。
     * - slots 为空（仅整周安排会这样）：只改名称/老师/教室，不动时段。
     *
     * 组内课程 id 会被替换（anchor 复用原 id，其余新建），全部标 edited=true。
     */
    fun editCourseSameName(
        course: Course, name: String, teacher: String, location: String, credit: String? = null,
        slots: List<Triple<Int, Int, Int>>, weeks: List<Int>, onlyThisWeek: Boolean, currentWeek: Int
    ) {
        val cur = loadSchedule()
        val group = sameNameCoursesThisWeek(cur, course, currentWeek)
        if (group.size <= 1) {
            editCourse(course, name, teacher, location, credit, slots, weeks, onlyThisWeek, currentWeek)
            return
        }
        val groupIds = group.map { it.id }.toSet()
        val anchor = group.firstOrNull { it.id == course.id } ?: group.first()
        val nm = name.trim().ifEmpty { anchor.name }
        // 老师/教室留空 = 保留原值（组里取第一个非空的），防止编辑时误把已有老师/教室清掉
        val tc = teacher.trim().ifEmpty { group.firstOrNull { it.teacher.isNotBlank() }?.teacher ?: "" }
        val lo = location.trim().ifEmpty { group.firstOrNull { it.location.isNotBlank() }?.location ?: "" }
        // 学分：null=未提供（保留各组原值）；非 null 按新值（空串=清空）
        val cr0 = credit?.trim()
        val ws = weeks.filter { it in 1..53 }.distinct().sorted()
        val norm = normSlots(slots)

        val replacements = ArrayList<Course>()
        when {
            norm.isEmpty() -> {
                // 只改名称/老师/教室/学分，不动时段
                group.forEach {
                    replacements.add(
                        it.copy(
                            name = nm, teacher = tc, location = lo,
                            credit = if (cr0 == null) it.credit else cr0.ifEmpty { null },
                            weeks = if (it.day == 0) ws else it.weeks, edited = true
                        )
                    )
                }
            }
            !onlyThisWeek -> {
                norm.forEachIndexed { i, sl ->
                    replacements.add(
                        Course(
                            id = if (i == 0) anchor.id else UUID.randomUUID().toString().take(8),
                            name = nm, teacher = tc, location = lo,
                            credit = if (cr0 == null) anchor.credit else cr0.ifEmpty { null },
                            day = sl.first, startPeriod = sl.second, endPeriod = sl.third,
                            weeks = ws, edited = true
                        )
                    )
                }
            }
            else -> {
                // 仅本周：其它周次保留（同步改名/师/室），本周换成新时段
                group.forEach { g ->
                    val origWeeks = if (g.weeks.isEmpty()) (1..53).toList() else g.weeks
                    val remain = origWeeks.filter { it != currentWeek }
                    if (remain.isNotEmpty()) {
                        replacements.add(
                            g.copy(
                                name = nm, teacher = tc, location = lo,
                                credit = if (cr0 == null) g.credit else cr0.ifEmpty { null },
                                weeks = remain.sorted(), edited = true
                            )
                        )
                    }
                }
                norm.forEach { sl ->
                    replacements.add(
                        Course(
                            id = UUID.randomUUID().toString().take(8),
                            name = nm, teacher = tc, location = lo,
                            credit = if (cr0 == null) anchor.credit else cr0.ifEmpty { null },
                            day = sl.first, startPeriod = sl.second, endPeriod = sl.third,
                            weeks = listOf(currentWeek), edited = true
                        )
                    )
                }
            }
        }
        val out = cur.courses.filter { it.id !in groupIds } + replacements
        saveSchedule(cur.copy(courses = out))
    }
    /**
     * 删除课程。
     * onlyThisWeek=false：整学期删除 —— 直接移除，并记入"已删除"位置，重抓/自动更新不再导回
     *                     （用户在手动导入时选"用教务的"重置，才会恢复）。
     * onlyThisWeek=true ：只删本周 —— 把这一周从周次里拆掉并标 edited；周次删空则整门移除。
     * syncSameName=true ：本周内所有同名课一起删。
     */
    fun deleteCourse(course: Course, onlyThisWeek: Boolean, currentWeek: Int, syncSameName: Boolean) {
        val cur = loadSchedule()
        val selfIdx = findCourseIndex(cur.courses, course)
        val self = if (selfIdx >= 0) cur.courses[selfIdx] else course
        val targets = if (syncSameName) {
            val group = sameNameCoursesThisWeek(cur, self, currentWeek)
            if (group.any { it.id == self.id }) group else group + self
        } else {
            listOf(self)
        }
        if (targets.isEmpty()) return
        val ids = targets.map { it.id }.toSet()
        val out = ArrayList<Course>(cur.courses.size)
        if (!onlyThisWeek) {
            rememberDeletedKeys(targets.map { courseKeyOf(it) })
            // 备忘录/学分随墓碑存档：之后选"恢复导入"时能原样带回来（备忘录是用户手写的，丢了找不回）
            val meta = deletedMeta()
            targets.forEach { meta[courseKeyOf(it)] = (it.note ?: "") to (it.credit ?: "") }
            saveDeletedMeta(meta)
            for (c in cur.courses) if (c.id !in ids) out.add(c)
        } else {
            for (c in cur.courses) {
                if (c.id !in ids) { out.add(c); continue }
                val origWeeks = if (c.weeks.isEmpty()) (1..53).toList() else c.weeks
                val remain = origWeeks.filter { it != currentWeek }
                if (remain.isNotEmpty()) out.add(c.copy(weeks = remain.sorted(), edited = true))
                // 周次删空 = 这门课只有这一周 → 整门移除
            }
        }
        saveSchedule(cur.copy(courses = out))
    }

    // ---- 已删除课程（防止重抓导回）----
    /** 用户整学期删除的课程位置（名称|星期|起始节）。重抓课表时这些位置的课不再导入。 */
    fun deletedKeys(): Set<String> =
        sp.getStringSet(K_DELETED, emptySet())?.toSet() ?: emptySet()

    fun rememberDeletedKeys(keys: Collection<String>) {
        val add = keys.filter { it.isNotBlank() }.toSet()
        if (add.isEmpty()) return
        val cur = deletedKeys().toMutableSet()
        cur.addAll(add)
        sp.edit().putStringSet(K_DELETED, cur).apply()
    }

    fun forgetDeletedKeys(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val cur = deletedKeys().toMutableSet()
        cur.removeAll(keys.toSet())
        sp.edit().putStringSet(K_DELETED, cur).apply()
        val meta = deletedMeta()
        if (keys.any { meta.containsKey(it) }) {
            keys.forEach { meta.remove(it) }
            saveDeletedMeta(meta)
        }
    }

    // 墓碑随带的备忘录/学分存档（JSON：key -> {note, credit}）
    private fun deletedMeta(): MutableMap<String, Pair<String, String>> {
        val raw = sp.getString(K_DELETED_META, null) ?: return mutableMapOf()
        return try {
            val obj = JSONObject(raw)
            val m = mutableMapOf<String, Pair<String, String>>()
            for (k in obj.keys()) {
                val o = obj.optJSONObject(k) ?: continue
                m[k] = jStr(o, "note") to jStr(o, "credit")
            }
            m
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private fun saveDeletedMeta(m: Map<String, Pair<String, String>>) {
        if (m.isEmpty()) {
            sp.edit().remove(K_DELETED_META).apply()
            return
        }
        val obj = JSONObject()
        m.forEach { (k, v) ->
            val o = JSONObject()
            o.put("note", v.first)
            o.put("credit", v.second)
            obj.put(k, o)
        }
        sp.edit().putString(K_DELETED_META, obj.toString()).apply()
    }

    /** 本次抓到的课里，落在"用户整学期删除过"位置上的课名（去重）。
     *  非空时导入前要弹窗问用户：恢复这些课，还是维持删除。 */
    fun tombstonedCoursesIn(newCourses: List<Course>): List<String> {
        val dk = deletedKeys()
        if (dk.isEmpty() || newCourses.isEmpty()) return emptyList()
        return newCourses.filter { courseKeyOf(it) in dk }.map { it.name.trim() }.distinct()
    }

    private fun clearDeletedKeys() {
        sp.edit().putStringSet(K_DELETED, emptySet()).apply()
    }

    /** 多轮学分合并（每轮查缺补漏）：见 CourseMerge.mergeCreditRounds。保留旧名字作为入口 */
    fun mergeCreditsRounds(rounds: List<Map<String, String>>): Map<String, String> =
        mergeCreditRounds(rounds)

    /** 测试专用：不落盘地把学分按课名并进一份课表（生产路径走 applyCredits） */
    fun applyCreditsForTest(credits: Map<String, String>, cur: Schedule): List<Course> {
        if (credits.isEmpty()) return cur.courses
        val norm = { s: String -> s.replace(" ", "").replace("\u00A0", "") }
        val tail = Regex("[（(][^（()）]*[)）]\\s*$")
        val byNorm = HashMap<String, String>()
        for ((name, v) in credits) {
            var n = name
            byNorm.putIfAbsent(norm(n), v)
            while (true) {
                val m = tail.find(n) ?: break
                n = n.substring(0, m.range.first).trim()
                if (n.isEmpty()) break
                byNorm.putIfAbsent(norm(n), v)
            }
        }
        return cur.courses.map { c ->
            var v = byNorm[norm(c.name)]
            if (v == null) {
                var n2 = c.name
                while (v == null) {
                    val m = tail.find(n2) ?: break
                    n2 = n2.substring(0, m.range.first).trim()
                    if (n2.isEmpty()) break
                    v = byNorm[norm(n2)]
                }
            }
            if (v != null && v.isNotEmpty()) c.copy(credit = v) else c
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
            var v = byNorm[norm(c.name)]
            if (v == null) {
                // 课名尾部分组括号两边可能全角/半角不一致（课表"(分组01)" vs 学分页"（分组01）"）：
                // 逐层剥掉课名尾部的括号段再匹配（学分页侧已做过同样的剥层）
                var cur2 = c.name
                while (v == null) {
                    val mm = tail.find(cur2) ?: break
                    cur2 = cur2.substring(0, mm.range.first).trim()
                    if (cur2.isEmpty()) break
                    v = byNorm[norm(cur2)]
                }
            }
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
        get() = sp.getBoolean(K_AUTO, false)   // 默认关闭（用户要求）
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

    /** 非本周课程显示：false=不显示（默认），true=半透明显示在原时段 */
    var showOtherWeekCourses: Boolean
        get() = sp.getBoolean("show_other_week", false)
        set(v) { sp.edit().putBoolean("show_other_week", v).apply() }

    /** 自定义背景图片：本机图片的 content:// URI 字符串；空=不使用（纯色背景）。
     *  仅存本机，不上传。用户可在设置里上传/更换/清除。 */
    var backgroundImageUri: String
        get() = sp.getString("bg_image_uri", "") ?: ""
        set(v) { sp.edit().putString("bg_image_uri", v).apply() }

    /** 已展示过"更新公告"的版本号（同一版本只自动弹一次；用户可在设置里再看） */
    var announcedTag: String
        get() = sp.getString("announced_tag", "") ?: ""
        set(v) { sp.edit().putString("announced_tag", v).apply() }

    /** 背景图片明暗遮罩强度 0-100（越高越淡，保证课表文字看得清）；默认 60 */
    var backgroundDim: Int
        get() = sp.getInt("bg_dim", 60)
        set(v) { sp.edit().putInt("bg_dim", v.coerceIn(0, 100)).apply() }

    fun hasCredentials(): Boolean = user.isNotEmpty() && savedPass && pass.isNotEmpty()

    /** HTTP 抓取器的会话 Cookie（跨启动持久化，命中时 0.2s 直接抓） */
    var httpCookies: String
        get() = sp.getString("http_cookies", "") ?: ""
        set(value) = sp.edit().putString("http_cookies", value).apply()

    // ---- 课程自定义颜色（按课程名存储色相，同名课共用）----
    private const val K_COURSE_COLOR_PREFIX = "course_color_"

    /** 取课程自定义色相（-1 = 跟随色系） */
    fun courseColorHue(name: String): Int =
        sp.getInt(K_COURSE_COLOR_PREFIX + name.hashCode(), -1)

    /** 设置课程自定义色相（-1 = 重置为跟随色系） */
    fun setCourseColorHue(name: String, hue: Int) {
        if (hue < 0) sp.edit().remove(K_COURSE_COLOR_PREFIX + name.hashCode()).apply()
        else sp.edit().putInt(K_COURSE_COLOR_PREFIX + name.hashCode(), hue).apply()
    }

    // ---- 覆盖安装处理 ----
    /** 上次运行过的 versionCode（0=首次安装）。覆盖安装后据此清残留、并强制抓取一次 */
    var appVersion: Int
        get() = sp.getInt("app_version", 0)
        set(v) { sp.edit().putInt("app_version", v).apply() }

    /** 清掉本地课表数据（保留账号/外观/学期起始日等设置），用于覆盖安装后清残留 */
    fun clearCourses() {
        val cur = loadSchedule()
        saveSchedule(cur.copy(term = "", courses = emptyList()))
    }
}
