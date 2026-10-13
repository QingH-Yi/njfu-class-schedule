package com.njfu.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 小组件"到点精准刷新"：在课程开始/结束、以及次日 0 点各排一次闹钟，
 * 让"正在上的课高亮 / 下一节课"能在换课时自动切过去，而不是依赖 30 分钟的 updatePeriodMillis。
 *
 * 纯逻辑（下次刷新时刻的计算）抽成 [nextRefreshAt]，方便单测；
 * AlarmManager 只是把这套计算的结果注册成精确闹钟。
 */
object WidgetRefreshScheduler {

    /** 纯逻辑：相对于 now，小组件下次应该刷新的绝对时刻。
     *  优先取今天之内最近的一个边界（某节课开始 / 结束）；
     *  今天没有更多边界就排到"明天 0 点"，用于跨天切换"今天没课了 → 明天第一节"。
     */
    fun nextRefreshAt(
        schedule: Schedule,
        week: Int,
        today: Int,
        now: LocalDateTime
    ): LocalDateTime {
        val boundary = nextRefreshTime(schedule, week, today, now.toLocalTime())
        if (boundary != null && boundary.isAfter(now.toLocalTime())) {
            return LocalDateTime.of(now.toLocalDate(), boundary)
        }
        val midnight = now.toLocalDate().plusDays(1).atStartOfDay()
        // 兜底：至少 1 分钟后再响，避免刚过边界时在同一点上死循环
        val earliest = now.plusMinutes(1)
        return if (midnight.isAfter(earliest)) midnight else earliest
    }

    /** 明天 0 点时，周次可能 +1（周一）。仅用于展示时用的"明天那门课的周次"判断——这里保持简单，
     *  到点后由刷新逻辑重新按当前日期计算，所以这里返回的 week 只在单元测试里用；实盘 nextRefreshAt
     *  只负责"何时唤醒"，唤醒后 WidgetUpdater 会重新读当前日期/周次。 */

    /** 注册（或重排）下一次精准刷新闹钟；设备重启 / 时间变化后也调用它 */
    fun schedule(context: Context) {
        val schedule = runCatching { WidgetData.load(context) }.getOrNull() ?: return
        val week = WidgetData.currentWeek(schedule)
        val today = WidgetData.todayIso()
        val now = runCatching { LocalDateTime.now() }.getOrNull() ?: return
        val at = nextRefreshAt(schedule, week, today, now)
        setAlarm(context, at)
    }

    private fun setAlarm(context: Context, at: LocalDateTime) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pendingIntent(context)
        val millis = at.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        runCatching {
            // S+ 需要 SCHEDULE_EXACT_ALARM 权限；没有就退化成非精确闹钟（到点可能晚一些，但不崩）
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            }
        }
    }

    /** 用于单测/调试：下一次闹钟的毫秒时间戳（无则 -1） */
    fun nextAlarmMillis(context: Context): Long {
        val pi = pendingIntent(context)
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return -1
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.nextAlarmClock?.triggerTime ?: -1
        } else -1
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WidgetRefreshReceiver::class.java).apply {
            action = ACTION_WIDGET_REFRESH
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    const val ACTION_WIDGET_REFRESH = "com.njfu.schedule.action.WIDGET_REFRESH"
    private const val REQUEST_CODE = 0x5752

    /** 取消已排的闹钟（清空课表时用） */
    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { am.cancel(pendingIntent(context)) }
    }
}

/**
 * 闹钟到点 → 刷新所有小组件，并重新排下一次闹钟。
 * 同时处理 BOOT_COMPLETED / TIME_SET / TIMEZONE_CHANGED：重启或改时间后闹钟会丢，需要重排。
 */
class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                WidgetRefreshScheduler.schedule(context)
                WidgetUpdater.refreshAllAsync(context)
            }
            else -> {
                // 普通到点刷新：先刷新（切到"正在上"或"下一节"），再排下一次
                WidgetUpdater.refreshAllAsync(context)
                WidgetRefreshScheduler.schedule(context)
            }
        }
    }
}
