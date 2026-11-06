package com.njfu.schedule

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * WakeUp 课程表「分享口令」互通。
 *
 * 口令本质：一串 32 位十六进制的 key，指向 WakeUp 服务器（i.wakeup.fun）上存的一份
 * 「wakeup_schedule」文本（课表本体），有效期约 30 分钟。App 里「从分享口令导入」是把
 * 那条消息整段粘进文本框，由 App 自己从中取出 key。
 *
 * 两个接口（2025-11 实测可用，纯明文表单，不需要 antispam 签名 / 不需要 native DES）：
 *   POST https://i.wakeup.fun/share_schedule        schedule=<wakeup_schedule 全文>
 *        → {"status":1,"message":"success","data":"<32 位 hex key>"}
 *   GET  https://i.wakeup.fun/share_schedule/get?key=<32 位 hex>
 *        → {"status":1,"data":"<wakeup_schedule 全文>"}
 *   参数名只能是 key；key 长度不是 32 时服务端回 status 5000004「当前版本过低，请升级」。
 *
 * wakeup_schedule 文件：6 行、\n 分隔
 *   0 表配置  {"courseLen":50,"id":1,"name":..,"sameBreakLen":false,"sameLen":true,"theBreakLen":10}
 *   1 时间表  []
 *   2 表属性  {"background":"","id":1,...,"maxWeek":20,"nodes":11,...,"startDate":"2026-8-31",...}
 *   3 课程    [{"color":"#FF6B6B","courseName":..,"credit":0.0,"id":1,"note":"","tableId":1}, ...]
 *   4 上课时间[{"day":1,"endWeek":8,"id":1,"room":..,"startNode":1,"startWeek":1,"step":2,"teacher":..}, ...]
 *   5 空行
 * WakeUp 的时间段只有 startWeek/endWeek 连续区间，没有「间断周」概念，所以本 App 的
 * weeks=1,3,5 会按连续段拆成多条记录；day==0 的整周实习/实验没有可落位的格子，不导出。
 */
object WakeUpShare {

    /** WakeUp 服务器地址（明文接口，仅支持 HTTPS） */
    const val HOST = "https://i.wakeup.fun"

    /**
     * 导出文件的扩展名。WakeUp 的「导出文件」写出的就是这种文件，实测它就是
     * wakeup_schedule 文本。发给好友后用 WakeUp 打开即可导入，不依赖口令和联网。
     */
    const val FILE_EXT = "wakeup_schedule"

    /** 导出时最多保留的周数，与 WakeUp 默认 maxWeek 一致 */
    private const val MAX_WEEK = 20

    /** 一周可安排的节数，与 WakeUp 默认 nodes 一致 */
    private const val NODES = 11

    /** 课程配色，照搬 WakeUp 自带色板，导入后格子不会全是同色 */
    private val COLORS = listOf(
        "#FF6B6B", "#FF9F43", "#FFC048", "#FFD93D", "#6BCB77", "#38A169",
        "#4ECDC4", "#1ABC9C", "#3498DB", "#2C82C9", "#6A5ACD", "#9B59B6",
        "#D980FA", "#E84393", "#FF7675", "#A29BFE", "#00B894", "#0984E3"
    )

    // ---------------- 本 App 课表 → wakeup_schedule 文本 ----------------

    /**
     * 生成 wakeup_schedule 文件全文。
     * @param tableName 显示在 WakeUp 里的课表名
     * @return 6 行文本（末尾带一个换行）
     */
    fun buildScheduleFile(schedule: Schedule, tableName: String = ""): String {
        val name = tableName.ifBlank { defaultTableName(schedule) }
        // WakeUp 自己写出的就是紧凑 JSON（键值之间没有空格），这里保持一致，
        // 免得某些版本的解析器对空白敏感。
        val gson = GsonBuilder().disableHtmlEscaping().create()
        val sb = StringBuilder()

        // 0 表配置
        val conf = JsonObject().apply {
            addProperty("courseLen", 50)
            addProperty("id", 1)
            addProperty("name", name)
            addProperty("sameBreakLen", false)
            addProperty("sameLen", true)
            addProperty("theBreakLen", 10)
        }
        sb.append(gson.toJson(conf)).append('\n')

        // 1 时间表：留空，全部沿用本 App 的 hh:mm（写进 line4 的 startTime/endTime 里更直观）
        sb.append("[]").append('\n')

        // 2 表属性
        val maxWeek = schedule.courses.flatMap { it.weeks }.maxOrNull()?.coerceAtLeast(1) ?: MAX_WEEK
        val attr = JsonObject().apply {
            addProperty("background", "")
            addProperty("id", 1)
            addProperty("itemAlpha", 60)
            addProperty("itemHeight", 64)
            addProperty("itemTextSize", 12)
            addProperty("maxWeek", maxWeek.coerceAtMost(60))
            addProperty("nodes", NODES)
            addProperty("showOtherSat", true)
            addProperty("showSat", true)
            addProperty("showSun", true)
            addProperty("showTime", false)
            // WakeUp 用不补零的 YYYY-M-D
            addProperty("startDate", wakeDate(schedule.termStartMonday))
            addProperty("tableName", name)
            addProperty("timeTable", 1)
            addProperty("type", 0)
        }
        sb.append(gson.toJson(attr)).append('\n')

        // 3 课程信息：同名课合并成一条（WakeUp 里一门课可以有多段时间）
        val courses = schedule.courses
            .filter { it.day in 1..7 && it.name.isNotBlank() }
            .sortedWith(compareBy({ it.name }, { it.day }, { it.startPeriod }))
        val nameToId = LinkedHashMap<String, Int>()
        for (c in courses) nameToId.getOrPut(c.name) { nameToId.size + 1 }

        val infoArr = JsonArray()
        for ((courseName, id) in nameToId) {
            val info = JsonObject().apply {
                addProperty("color", COLORS[(id - 1) % COLORS.size])
                addProperty("courseName", courseName)
                addProperty("credit", 0.0)
                addProperty("id", id)
                addProperty("note", "")
                addProperty("tableId", 1)
            }
            infoArr.add(info)
        }
        sb.append(gson.toJson(infoArr)).append('\n')

        // 4 上课时间：间断周按连续段拆成多条记录
        val timeArr = JsonArray()
        val campusTimes = CampusTimes.times(schedule.campus)
        for (c in courses) {
            val id = nameToId[c.name] ?: continue
            val startNode = c.startPeriod.coerceIn(1, NODES)
            val endNode = c.endPeriod.coerceIn(startNode, NODES)
            val weeks = if (c.weeks.isEmpty()) (1..maxWeek).toList() else c.weeks.sorted()
            for (seg in weekSegments(weeks)) {
                val time = JsonObject().apply {
                    addProperty("day", c.day)
                    addProperty("endTime", c.endTime.ifBlank { campusTimes[c.endPeriod]?.second ?: "" })
                    addProperty("endWeek", seg.last())
                    addProperty("id", id)
                    addProperty("level", 0)
                    addProperty("ownTime", false)
                    addProperty("room", c.location)
                    addProperty("startNode", startNode)
                    addProperty("startTime", c.startTime.ifBlank { campusTimes[c.startPeriod]?.first ?: "" })
                    addProperty("startWeek", seg.first())
                    addProperty("step", endNode - startNode + 1)
                    addProperty("tableId", 1)
                    addProperty("teacher", c.teacher)
                    addProperty("type", 0)
                }
                timeArr.add(time)
            }
        }
        sb.append(gson.toJson(timeArr)).append('\n')

        // 5 结尾空行（WakeUp 导出的文件就是这个形状）
        sb.append('\n')
        return sb.toString()
    }

    /**
     * 生成要发给好友的整段口令文案（和 WakeUp 自己复制出来的措辞一致，对方直接粘贴即可）。
     */
    fun shareMessage(key: String): String =
        "这是来自「WakeUp课程表」的课表分享，30分钟内有效哦，如果失效请朋友再分享一遍叭。" +
            "为了保护隐私我们选择不监听你的剪贴板，请复制这条消息后，打开App的主界面，" +
            "右上角第二个按钮 -> 从分享口令导入，按操作提示即可完成导入~分享口令为「$key」"

    // ---------------- wakeup_schedule 文本 → 本 App 课表 ----------------

    /**
     * 解析 wakeup_schedule 文件全文。容忍行数不足、字段缺失、颜色等附加字段。
     * @return (课程列表, 跳过的无效记录数)
     */
    fun parseScheduleFile(text: String): Pair<List<Course>, Int> {
        val lines = text.replace("\r\n", "\n").split('\n')
        if (lines.size < 5) return emptyList<Course>() to 0
        var skipped = 0

        // 3 课程信息：id → (课程名, 备注)
        val infoById = HashMap<Int, String>()
        runCatching {
            JsonParser.parseString(lines[3]).asJsonArray.forEach { el ->
                val o = el.asJsonObject
                val id = o.get("id")?.takeIf { it.isJsonPrimitive }?.asInt ?: return@forEach
                val nm = o.get("courseName")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                if (nm.isNotBlank()) infoById[id] = nm.trim()
            }
        }
        if (infoById.isEmpty()) return emptyList<Course>() to 0

        val out = ArrayList<Course>()
        runCatching {
            JsonParser.parseString(lines[4]).asJsonArray.forEach { el ->
                val o = el.asJsonObject
                val id = o.get("id")?.takeIf { it.isJsonPrimitive }?.asInt ?: -1
                val courseName = infoById[id]
                val day = o.intOf("day")
                val startNode = o.intOf("startNode")
                val step = o.intOf("step")
                val startWeek = o.intOf("startWeek")
                val endWeek = o.intOf("endWeek")
                if (courseName == null || day !in 1..7 || startNode !in 1..NODES ||
                    step < 1 || startWeek < 1 || endWeek < startWeek
                ) {
                    skipped++
                    return@forEach
                }
                val endNode = (startNode + step - 1).coerceAtMost(NODES)
                out.add(
                    Course(
                        id = UUID.randomUUID().toString().take(8),
                        name = courseName,
                        teacher = o.strOf("teacher"),
                        location = o.strOf("room"),
                        day = day,
                        startPeriod = startNode,
                        endPeriod = endNode,
                        weeks = (startWeek..endWeek).toList(),
                        startTime = o.strOf("startTime"),
                        endTime = o.strOf("endTime"),
                        edited = true
                    )
                )
            }
        }
        // 同一门课在 WakeUp 里被拆成多段的间断周，合回本 App 的一条（名称/星期/节次/老师/地点相同）
        return mergeSegments(out) to skipped
    }

    /**
     * 把「同课名+同星期+同节次+同老师+同地点」的多条记录合成一条，周次取并集。
     * WakeUp 的间断周就是靠多条连续段拼出来的，不合并会在课表上叠出一堆同位置卡片。
     */
    fun mergeSegments(courses: List<Course>): List<Course> {
        val merged = LinkedHashMap<String, Course>()
        for (c in courses) {
            val k = listOf(c.name, c.day, c.startPeriod, c.endPeriod, c.teacher, c.location).joinToString("\u0001")
            val prev = merged[k]
            merged[k] = if (prev == null) c
            else prev.copy(weeks = (prev.weeks + c.weeks).distinct().sorted())
        }
        return merged.values.toList()
    }

    // ---------------- 网络：上传 / 下载 ----------------

    /** 上传失败原因 */
    class ShareException(message: String) : IOException(message)

    /**
     * 把课表文本传到 WakeUp 服务器，返回 32 位 hex 口令。
     * @throws ShareException 网络或服务端出错
     */
    fun upload(scheduleFile: String): String {
        val body = "schedule=" + URLEncoder.encode(scheduleFile, "UTF-8")
        val resp = post("$HOST/share_schedule", body)
        val obj = JsonParser.parseString(resp).asJsonObject
        val status = obj.get("status")?.takeIf { it.isJsonPrimitive }?.asInt ?: -1
        if (status != 1) {
            throw ShareException(obj.get("message")?.takeIf { it.isJsonPrimitive }?.asString ?: "上传失败")
        }
        val key = obj.get("data")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        if (key.length != 32) throw ShareException("服务端返回的口令不正确（$key）")
        return key
    }

    /**
     * 用口令换回课表文本。整段分享消息也能直接传进来（内部取出 32 位 key）。
     * @throws ShareException 口令失效（过期 / 不存在）或网络出错
     */
    fun download(text: String): String {
        val key = extractKey(text) ?: throw ShareException("没有找到有效的分享口令（应当是一串 32 位字符）")
        val resp = get("$HOST/share_schedule/get?key=$key")
        val obj = JsonParser.parseString(resp).asJsonObject
        val status = obj.get("status")?.takeIf { it.isJsonPrimitive }?.asInt ?: -1
        if (status != 1) {
            val msg = obj.get("message")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            throw ShareException(
                when {
                    status == 5000004 -> "口令不正确哦，请检查是否复制完整"
                    msg.contains("版本") -> "口令可能已过期（有效期 30 分钟），让对方重新分享一次"
                    msg.isNotBlank() -> msg
                    else -> "数据读取失败，可能是分享口令已经过期了哦"
                }
            )
        }
        return obj.get("data")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    }

    /** 从一段文本里挑出 32 位 hex 口令；支持整段 WakeUp 分享消息、纯口令、带空白的粘贴 */
    fun extractKey(text: String): String? {
        // 优先取「」里的内容（WakeUp 分享消息的口令就在里面）
        Regex("「([0-9a-fA-F]{32})」").find(text)?.let { return it.groupValues[1].lowercase() }
        Regex("[0-9a-fA-F]{32}").find(text)?.let { return it.value.lowercase() }
        return null
    }

    private fun post(url: String, body: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("User-Agent", "okhttp/3.14.9")
            setRequestProperty("version", "180")
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            readOrThrow(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("User-Agent", "okhttp/3.14.9")
            setRequestProperty("version", "180")
        }
        return try {
            readOrThrow(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun readOrThrow(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            ?: throw ShareException("服务器无响应（HTTP $code）")
        val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
        if (code !in 200..299) throw ShareException("服务器出错（HTTP $code）")
        return text
    }

    // ---------------- 小工具 ----------------

    /** 把有序周次切成连续段：1,2,3,9,10 → [[1,2,3],[9,10]] */
    fun weekSegments(weeks: List<Int>): List<List<Int>> {
        if (weeks.isEmpty()) return emptyList()
        val out = ArrayList<List<Int>>()
        var cur = mutableListOf(weeks.first())
        for (w in weeks.drop(1)) {
            if (w == cur.last() + 1) cur.add(w) else { out.add(cur); cur = mutableListOf(w) }
        }
        out.add(cur)
        return out
    }

    /** 2026-08-31 → 2026-8-31（WakeUp 的 startDate 不补零） */
    fun wakeDate(isoDate: String): String {
        val m = Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$").find(isoDate.trim()) ?: return isoDate
        return "${m.groupValues[1]}-${m.groupValues[2].toInt()}-${m.groupValues[3].toInt()}"
    }

    private fun defaultTableName(schedule: Schedule): String =
        schedule.term.ifBlank { "南林课程表" }

    private fun JsonObject.intOf(key: String): Int =
        get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() } ?: -1

    private fun JsonObject.strOf(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
}
