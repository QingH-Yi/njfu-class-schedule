package com.njfu.schedule

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 课表 → ICS 日历（算法借鉴开源项目 ZFClassTable2ICS 的"按周次×节次时间展开"思路）：
 * 每门课 × 每个上课周 生成一个 VEVENT（时间由三校区作息表换算），
 * 导入系统日历 / Google 日历后自动按周出现上课提醒。
 * 整周安排（实习/实验，day=0）没有具体上课时间，不生成事件。
 */
object IcsExport {

    private val UTC_FMT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    private val LOCAL_FMT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    fun suggestedFileName(schedule: Schedule): String =
        "南林课程表-" + schedule.term.replace(Regex("[\\\\/:*?\"<>|]"), "").ifBlank { "未命名学期" } + ".ics"

    /** 生成 ICS 文本（CRLF 行尾；floating local time，系统日历按本地时区解释） */
    fun buildIcs(
        schedule: Schedule,
        campus: String,
        nowUtc: ZonedDateTime = ZonedDateTime.now(ZoneId.of("UTC"))
    ): String {
        val startMonday = try {
            LocalDate.parse(schedule.termStartMonday, DATE_FMT)
        } catch (e: Exception) {
            return ""
        }
        val times = CampusTimes.times(campus)
        val sb = StringBuilder()
        sb.append("BEGIN:VCALENDAR\r\n")
            .append("VERSION:2.0\r\n")
            .append("PRODID:-//njfu-class-schedule//v2.5//CN\r\n")
            .append("CALSCALE:GREGORIAN\r\n")
        val stamp = nowUtc.format(UTC_FMT)
        val courses = schedule.courses.filter { it.day != 0 }
        for (c in courses) {
            val weeks = if (c.weeks.isEmpty()) (1..20).toList() else c.weeks
            for (w in weeks) {
                val date = startMonday.plusDays(((w - 1) * 7 + (c.day - 1)).toLong())
                val t1 = c.startTime.ifEmpty { times[c.startPeriod]?.first ?: "" }
                val t2 = c.endTime.ifEmpty { times[c.endPeriod]?.second ?: "" }
                if (t1.isEmpty() || t2.isEmpty()) continue
                val start = try { LocalDateTime.of(date, java.time.LocalTime.parse(t1)) } catch (e: Exception) { continue }
                val end = try { LocalDateTime.of(date, java.time.LocalTime.parse(t2)) } catch (e: Exception) { continue }
                if (!end.isAfter(start)) continue
                sb.append("BEGIN:VEVENT\r\n")
                    .append("UID:${c.id}-w$w@njfu-class-schedule\r\n")
                    .append("DTSTAMP:").append(stamp).append("\r\n")
                    .append("DTSTART:").append(start.format(LOCAL_FMT)).append("\r\n")
                    .append("DTEND:").append(end.format(LOCAL_FMT)).append("\r\n")
                    .append("SUMMARY:").append(icsEscape(c.name)).append("\r\n")
                if (c.location.isNotEmpty()) sb.append("LOCATION:").append(icsEscape(c.location)).append("\r\n")
                val desc = buildList {
                    if (c.teacher.isNotBlank()) add("老师：${c.teacher}")
                    add("第${c.startPeriod}-${c.endPeriod}节 · ${weekLabel(c.weeks)}")
                    if (!c.note.isNullOrEmpty()) add(c.note)
                }.joinToString("\n")
                sb.append("DESCRIPTION:").append(icsEscape(desc)).append("\r\n")
                    .append("END:VEVENT\r\n")
            }
        }
        sb.append("END:VCALENDAR\r\n")
        return sb.toString()
    }

    /** ICS 文本转义：反斜杠/分号/逗号/换行 */
    private fun icsEscape(s: String): String = s
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\n", "\\n")
}
