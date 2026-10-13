package com.njfu.schedule

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
val PALETTES = listOf(MACARON, SEA, SAKURA, MATCHA)
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

/* ───────────────────────── 设置界面统一组件 ─────────────────────────
 * 目标：整齐、规律、精致——所有设置项都用同一套「图标 + 标题(+副标题) + 右侧控件」
 * 的行式排版，行高/间距/圆角/分隔线全部一致；分组用圆角卡片归拢。
 */

/** 设置图标的柔和配色（与马卡龙主题协调），按分组取用，让界面更有层次 */
val SETTING_TINTS = listOf(
    Color(0xFF7C9BE8), // 蓝
    Color(0xFFE88AA5), // 粉
    Color(0xFF6FBF9B), // 绿
    Color(0xFFE0A55C), // 橙
    Color(0xFF9B86D9), // 紫
    Color(0xFF57B6C4)  // 青
)

/** 设置分组标题：强调色小竖条 + 图标 + 标题，统一节奏（比纯文字更有层次） */
@Composable
fun SectionTitle(text: String, color: Color, icon: String = "") {
    Row(
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp).padding(start = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Spacer(Modifier.width(7.dp))
        if (icon.isNotEmpty()) {
            Text(icon, fontSize = 13.sp)
            Spacer(Modifier.width(5.dp))
        }
        Text(text, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

/** 设置分组卡片：圆角浅底 + 细边框，把一组行归拢成块（不再平铺） */
@Composable
fun SettingsCard(th: AppColors, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = th.bg.copy(alpha = if (th.dark) 0.55f else 0.65f),
        border = BorderStroke(1.dp, th.divider),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(content = content)
    }
}

/** 设置行：左侧彩色圆角图标 + 标题(+副标题)，右侧放控件/箭头。整行可点（onClick 非空时）。 */
@Composable
fun SettingsRow(
    th: AppColors,
    icon: String,
    iconColor: Color,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val base = Modifier.fillMaxWidth()
    val row = if (onClick != null) base.clickable { onClick() } else base
    Row(
        modifier = row.padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(iconColor.copy(alpha = if (th.dark) 0.26f else 0.18f)),
            contentAlignment = Alignment.Center
        ) { Text(icon, fontSize = 15.sp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = th.textPrimary)
            if (!subtitle.isNullOrEmpty()) {
                Spacer(Modifier.height(1.dp))
                Text(subtitle, fontSize = 11.sp, lineHeight = 15.sp, color = th.textSecondary)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        }
    }
}

/** 分组内的细分隔线：左侧让出图标位，视觉更整齐（最后一行后不画，由调用方控制） */
@Composable
fun SettingsDivider(th: AppColors) {
    HorizontalDivider(
        color = th.divider,
        thickness = 1.dp,
        modifier = Modifier.padding(start = 58.dp, end = 14.dp)
    )
}

/** 开关行：图标 + 标题(+副标题) + 右侧 Switch（整行可点切换） */
@Composable
fun SettingsSwitchRow(
    th: AppColors,
    icon: String,
    iconColor: Color,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null
) {
    SettingsRow(th, icon, iconColor, title, subtitle, onClick = { onCheckedChange(!checked) }) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = iconColor)
        )
    }
}

/** 右侧值 + 箭头（表示"点进去还能改"） */
@Composable
fun SettingsChevron(th: AppColors) {
    Text("›", fontSize = 18.sp, color = th.textSecondary.copy(alpha = 0.7f))
}

/** 设置里的输入框：左侧图标 + 统一圆角/边框，与设置行同宽同边距，整体更整齐 */
@Composable
fun SettingField(
    th: AppColors,
    icon: String,
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    padded: Boolean = true,
    keyboardType: androidx.compose.ui.text.input.KeyboardType =
        androidx.compose.ui.text.input.KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        leadingIcon = { Text(icon, fontSize = 15.sp) },
        singleLine = singleLine,
        shape = RoundedCornerShape(12.dp),
        textStyle = androidx.compose.material3.LocalTextStyle.current.copy(
            fontSize = 14.sp, color = th.textPrimary
        ),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = th.accent,
            unfocusedBorderColor = th.divider,
            focusedLabelColor = th.accent,
            unfocusedLabelColor = th.textSecondary,
            cursorColor = th.accent
        ),
        modifier = modifier
            .fillMaxWidth()
            .then(if (padded) Modifier.padding(horizontal = 14.dp, vertical = 6.dp) else Modifier)
    )
}

/** 弹窗标题：可选图标 + 标题，统一各弹窗的顶部排版 */
@Composable
fun DialogHeader(th: AppColors, title: String, icon: String = "") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon.isNotEmpty()) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(th.accent.copy(alpha = if (th.dark) 0.26f else 0.16f)),
                contentAlignment = Alignment.Center
            ) { Text(icon, fontSize = 15.sp) }
            Spacer(Modifier.width(10.dp))
        }
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = th.textPrimary)
    }
}

/**
 * 分段选择器：一排等宽选项，选中项用强调色实底 + 白字。
 * 比一排 FilterChip 更整齐、更像现代 App 的设置项（选项少、互斥时用）。
 */
@Composable
fun SegmentedControl(
    th: AppColors,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(11.dp),
        color = th.divider.copy(alpha = if (th.dark) 0.5f else 0.6f),
        modifier = modifier
    ) {
        Row(Modifier.padding(3.dp)) {
            options.forEachIndexed { i, label ->
                val on = i == selectedIndex
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (on) th.accent else Color.Transparent)
                        .clickable { onSelect(i) }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        fontSize = 12.5.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (on) Color.White else th.textPrimary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * 色系选择行：每个色系画成一组小圆点（用该色系的卡片底色），选中的描一圈强调色。
 * 比文字 chip 更直观，也更精致。
 */@Composable
fun PaletteSwatchRow(
    th: AppColors,
    palettes: List<List<Pair<Color, Color>>>,
    names: List<String>,
    selectedIndex: Int,       // -1 = 自定义
    onSelect: (Int) -> Unit,
    onCustom: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        palettes.forEachIndexed { i, pal ->
            val on = selectedIndex == i
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                    .clickable { onSelect(i) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(Modifier.clip(RoundedCornerShape(20.dp)).padding(2.dp)) {
                    listOf(0, 2, 4).forEach { k ->
                        Box(
                            Modifier
                                .size(11.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(pal[k % pal.size].first)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    names[i], fontSize = 10.5.sp, maxLines = 1,
                    color = if (on) th.accent else th.textSecondary,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
        // 自定义：一个彩虹渐变似的圆点
        val on = selectedIndex == -1
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                .clickable { onCustom() }
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.size(35.dp).clip(RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Row {
                    listOf(0f, 60f, 120f, 180f, 240f, 300f).forEach { h ->
                        Box(
                            Modifier.size(6.dp).background(Color.hsl(h, 0.6f, 0.7f))
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "自定义", fontSize = 10.5.sp, maxLines = 1,
                color = if (on) th.accent else th.textSecondary,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

