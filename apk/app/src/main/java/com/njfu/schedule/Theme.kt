package com.njfu.schedule

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/**
 * 应用配色：奶油底 + 马卡龙系卡片，支持深色/反色/多色系/自定义色相。
 * 全部颜色集中在这里，界面组件只认 AppColors。
 */
data class AppColors(
    val dark: Boolean,
    val bg: Color,          // 页面背景
    val surface: Color,     // 顶栏/弹窗
    val textPrimary: Color,
    val textSecondary: Color,
    val divider: Color,
    val accent: Color,      // 强调色（胶囊/滑块/高亮）
    val todayTint: Color,   // 今天那一列的底色
    val cardColors: List<Pair<Color, Color>> // 课程卡片 (底色, 文字)
) {
    fun cardColor(name: String): Pair<Color, Color> {
        var h = 0
        for (ch in name) h = h * 31 + ch.code
        return cardColors[((h % cardColors.size) + cardColors.size) % cardColors.size]
    }
}

// 色系卡片 (底色, 深文字)
private val MACARON = listOf(
    Color(0xFFFFD6E0) to Color(0xFFB44D62), // 草莓粉
    Color(0xFFDDE8C2) to Color(0xFF71812F), // 抹茶
    Color(0xFFFFEBB8) to Color(0xFFA87E1D), // 奶黄
    Color(0xFFE4D7F9) to Color(0xFF7B5EA7), // 香芋紫
    Color(0xFFCDE6F8) to Color(0xFF4A7FB5), // 海盐蓝
    Color(0xFFFFDCC8) to Color(0xFFC4693B), // 蜜桃橘
    Color(0xFFC9EBDD) to Color(0xFF2E8B6E), // 薄荷绿
    Color(0xFFFFE0F0) to Color(0xFFC2558F), // 棉花糖粉
    Color(0xFFF2E3C6) to Color(0xFF8A6B3D), // 焦糖
    Color(0xFFD8E0F0) to Color(0xFF5B6FA8)  // 雾霭蓝
)
private val SEA = listOf(
    Color(0xFFB3DFF5) to Color(0xFF2E6E8E),
    Color(0xFFA8E0E8) to Color(0xFF1F7A78),
    Color(0xFFBFD4F2) to Color(0xFF3D5E96),
    Color(0xFFC5E8D5) to Color(0xFF2F7D55),
    Color(0xFFD5CFF2) to Color(0xFF5E55A0),
    Color(0xFFB8E5E0) to Color(0xFF20776D),
    Color(0xFFCBD9F5) to Color(0xFF46629E),
    Color(0xFFC2E9DC) to Color(0xFF2F8468),
    Color(0xFFD0E4F0) to Color(0xFF3A6B8A),
    Color(0xFFC8F0E4) to Color(0xFF237A62)
)
private val SAKURA = listOf(
    Color(0xFFFAD2E1) to Color(0xFF9E4E71),
    Color(0xFFF6CBE8) to Color(0xFF8E4A9E),
    Color(0xFFF3C6D8) to Color(0xFF9E4E62),
    Color(0xFFEFD5F5) to Color(0xFF7E4E9E),
    Color(0xFFFBD9E8) to Color(0xFF9E4E7E),
    Color(0xFFF5CFE0) to Color(0xFF965677),
    Color(0xFFF8D8EC) to Color(0xFF8E568E),
    Color(0xFFF2CFE5) to Color(0xFF965686),
    Color(0xFFFADCE8) to Color(0xFF9E5E71),
    Color(0xFFF5D2F0) to Color(0xFF8E4E86)
)
private val MATCHA = listOf(
    Color(0xFFD8E8C0) to Color(0xFF5E7A2E),
    Color(0xFFE4EFC8) to Color(0xFF6E8A2E),
    Color(0xFFD0E5B8) to Color(0xFF4E7A2E),
    Color(0xFFE0EDD0) to Color(0xFF55853E),
    Color(0xFFD5E5B0) to Color(0xFF5E7A2E),
    Color(0xFFE8F0D0) to Color(0xFF6E8A3E),
    Color(0xFFCBE5B0) to Color(0xFF4E7A2E),
    Color(0xFFDCE8C5) to Color(0xFF62813A),
    Color(0xFFE2EFC8) to Color(0xFF5E852E),
    Color(0xFFD2E8BE) to Color(0xFF53782E)
)

val PALETTE_NAMES = listOf("马卡龙", "海盐", "樱花", "抹茶")
private val PALETTES = listOf(MACARON, SEA, SAKURA, MATCHA)
private val PALETTE_ACCENTS = listOf(
    Color(0xFFE8919E), Color(0xFF2E8FA8), Color(0xFFB4548A), Color(0xFF5E8A3A)
)

/** 由色相生成一套自定义的柔和配色（自定义颜色用） */
fun paletteFromHue(hue: Float): List<Pair<Color, Color>> =
    (0..9).map { i ->
        val h = (hue + i * 20) % 360f
        Color.hsl(h, 0.52f, 0.84f) to Color.hsl(h, 0.5f, 0.33f)
    }

fun appColors(dark: Boolean, paletteId: Int, customHue: Int): AppColors {
    val useCustom = customHue in 0..359
    val idx = paletteId.coerceIn(0, PALETTES.lastIndex)
    val cardColors = if (useCustom) paletteFromHue(customHue.toFloat()) else PALETTES[idx]
    val accent = if (useCustom) Color.hsl(customHue.toFloat(), 0.55f, 0.5f) else PALETTE_ACCENTS[idx]
    return if (dark) {
        AppColors(
            dark = true,
            bg = Color(0xFF211D19), surface = Color(0xFF2C2721),
            textPrimary = Color(0xFFEFE6DB), textSecondary = Color(0xFFB3A493),
            divider = Color(0xFF3B342C), accent = accent,
            todayTint = accent.copy(alpha = 0.16f), cardColors = cardColors
        )
    } else {
        AppColors(
            dark = false,
            bg = Color(0xFFFDF8F1), surface = Color(0xFFFFF7EE),
            textPrimary = Color(0xFF6B584C), textSecondary = Color(0xFFA08D7C),
            divider = Color(0xFFF0E5D8), accent = accent,
            todayTint = accent.copy(alpha = 0.13f), cardColors = cardColors
        )
    }
}

/** 当前应使用的配色（设置项 + 系统深色状态 → AppColors） */
fun currentAppColors(context: android.content.Context): AppColors {
    val sysDark = (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    val base = when (Repository.themeMode) {
        "light" -> false
        "dark" -> true
        else -> sysDark
    }
    return appColors(if (Repository.invert) !base else base, Repository.paletteId, Repository.customHue)
}

/**
 * 带缩放+淡入淡出动画的弹窗：圆角大卡片风格。
 * 进入用 spring（轻微回弹，收尾自然），退出用 tween 快速收走；
 * 纵向比横向略微"拉伸"一点（scaleY 略大于 scaleX），像被轻轻撑开，比等比缩放更有手感。
 * content 里的按钮调用 close() 触发退出动画后再真正关闭。
 */
@Composable
fun MacDialog(
    color: Color,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    var exiting by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    if (exiting) {
        LaunchedEffect(Unit) {
            delay(200)
            onDismissRequest()
        }
    }
    val close = { if (!exiting) { exiting = true; visible = false } }

    // 0=收起 1=展开；进入走 spring（带回弹），退出走 tween
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = if (visible)
            spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)
        else
            tween(durationMillis = 150),
        label = "dialogProgress"
    )

    Dialog(
        onDismissRequest = { close() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 22.dp), contentAlignment = Alignment.Center) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = color,
                shadowElevation = 8.dp,
                modifier = Modifier.graphicsLayer {
                    // 进入时纵向先"撑开"再回落（scaleY 略大于 scaleX），退出时同步收拢
                    scaleX = 0.90f + 0.10f * progress
                    scaleY = 0.86f + 0.14f * progress
                    alpha = progress
                }
            ) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                    content(close)
                }
            }
        }
    }
}

/** 设置页的分类小标题 */
@Composable
fun SectionTitle(text: String, color: Color) {
    Text(
        text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
    )
}
