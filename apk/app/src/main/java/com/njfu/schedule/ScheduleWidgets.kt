package com.njfu.schedule

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/**
 * 桌面小组件用的共享数据读取 + 渲染块。
 * 小组件与主 App 同进程同包，直接读同一份 SharedPreferences（不需要 iOS 那种 App Group）。
 */
object WidgetData {
    /** 读课表（同一份存储，宽松解析兜底） */
    fun load(context: Context): Schedule {
        Repository.init(context)
        return Repository.loadSchedule()
    }

    /** 当前周次：优先手动指定，否则按日期推算（与主界面同源） */
    fun currentWeek(schedule: Schedule): Int = resolveCurrentWeek(schedule.termStartMonday)

    fun now(): LocalTime? = runCatching { LocalTime.now() }.getOrNull()
    fun todayIso(): Int = LocalDate.now().dayOfWeek.value
}

/** 主界面课程卡片用的同款柔和色（这里取一组马卡龙底色，避免依赖 Theme 的 Compose 状态） */
private val WIDGET_CARD_COLORS = listOf(
    Color(0xFFFFD6E0), Color(0xFFDDE8C2), Color(0xFFFFEBB8), Color(0xFFE4D7F9), Color(0xFFCDE6F8),
    Color(0xFFFFDCC8), Color(0xFFC9EBDD), Color(0xFFFFE0F0), Color(0xFFF2E3C6), Color(0xFFD8E0F0)
)
private val WIDGET_TEXT_ON_CARD = Color(0xFF4A3B30)

private fun cardColor(name: String): Color {
    var h = 0
    for (ch in name) h = h * 31 + ch.code
    return WIDGET_CARD_COLORS[((h % WIDGET_CARD_COLORS.size) + WIDGET_CARD_COLORS.size) % WIDGET_CARD_COLORS.size]
}

/** 点击整块小组件 → 打开 App */
private fun openApp() = actionStartActivity<MainActivity>()

/** 小卡片：下一门课 + 今日还剩几节 */
@Composable
fun NextClassContent(context: Context) {
    val schedule = WidgetData.load(context)
    val week = WidgetData.currentWeek(schedule)
    val today = WidgetData.todayIso()
    val next = nextClass(schedule, week, today, WidgetData.now())
    val remaining = remainingToday(schedule, week, today, WidgetData.now())

    Column(
        modifier = GlanceModifier.fillMaxSize().background(Color(0xFFFDF8F1))
            .padding(12.dp).clickable(openApp())
    ) {
        Text("第 $week 周 · ${dayName(today)}", style = TextStyle(color = ColorProvider(Color(0xFFA08D7C)), fontSize = 11.sp))
        Spacer(GlanceModifier.height(6.dp))
        if (next == null) {
            Text(
                if (remaining == 0) "今天没课了 🎉" else "今天没课",
                style = TextStyle(color = ColorProvider(WIDGET_TEXT_ON_CARD), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            )
        } else {
            Text(
                next.name,
                style = TextStyle(color = ColorProvider(WIDGET_TEXT_ON_CARD), fontSize = 15.sp, fontWeight = FontWeight.Bold),
                maxLines = 2
            )
            Spacer(GlanceModifier.height(3.dp))
            Text(
                "⏰ ${next.startTime}-${next.endTime}  第${next.startPeriod}节",
                style = TextStyle(color = ColorProvider(Color(0xFF6B584C)), fontSize = 12.sp)
            )
            if (next.location.isNotEmpty()) {
                Text("📍 ${next.location}", style = TextStyle(color = ColorProvider(Color(0xFFA08D7C)), fontSize = 12.sp))
            }
        }
        Spacer(GlanceModifier.height(6.dp))
        Text(
            if (remaining > 0) "今日还剩 $remaining 节" else "今日课程已结束",
            style = TextStyle(color = ColorProvider(Color(0xFFA08D7C)), fontSize = 11.sp)
        )
    }
}

/** 大卡片：整周课表缩略（周一~周日 × 节次，按课程时间排） */
@Composable
fun WeekContent(context: Context) {
    val schedule = WidgetData.load(context)
    val week = WidgetData.currentWeek(schedule)
    val today = WidgetData.todayIso()

    val byDay = (1..7).associateWith { d ->
        schedule.courses.filter { it.day == d && courseInWeek(it, week) }
            .sortedBy { it.startPeriod }
    }

    Column(
        modifier = GlanceModifier.fillMaxSize().background(Color(0xFFFDF8F1))
            .padding(10.dp).clickable(openApp())
    ) {
        Text(
            "第 $week 周 · ${schedule.term.ifEmpty { "本学期" }}",
            style = TextStyle(color = ColorProvider(Color(0xFF6B584C)), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        )
        Spacer(GlanceModifier.height(6.dp))
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            for (d in 1..7) {
                Column(modifier = GlanceModifier.defaultWeight().padding(horizontal = 1.dp)) {
                    Text(
                        dayNameShort(d),
                        style = TextStyle(
                            color = ColorProvider(if (d == today) Color(0xFFB4548A) else Color(0xFFA08D7C)),
                            fontSize = 10.sp, fontWeight = FontWeight.Bold
                        )
                    )
                    Spacer(GlanceModifier.height(3.dp))
                    val list = byDay[d].orEmpty()
                    if (list.isEmpty()) {
                        Text("·", style = TextStyle(color = ColorProvider(Color(0xFFD8CDBF)), fontSize = 10.sp))
                    } else {
                        for (c in list) {
                            Text(
                                c.name,
                                modifier = GlanceModifier.fillMaxWidth().padding(vertical = 1.dp)
                                    .background(cardColor(c.name)).padding(3.dp),
                                style = TextStyle(color = ColorProvider(WIDGET_TEXT_ON_CARD), fontSize = 9.sp),
                                maxLines = 2
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 小卡片小组件（下一门课） */
class NextClassWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { NextClassContent(context) }
    }
}

/** 大卡片小组件（整周课表） */
class WeekWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { WeekContent(context) }
    }
}

class NextClassWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextClassWidget()
}

class WeekWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeekWidget()
}

/** 课表变化 / 打开 App / 时间变化后刷新小组件 */
object WidgetUpdater {
    suspend fun refreshAll(context: Context) {
        runCatching { NextClassWidget().updateAll(context) }
        runCatching { WeekWidget().updateAll(context) }
    }

    /** 非协程上下文（Activity 里）用的便捷入口：后台线程刷新，失败静默 */
    fun refreshAllAsync(context: Context) {
        CoroutineScope(Dispatchers.Default).launch { refreshAll(context) }
    }
}
