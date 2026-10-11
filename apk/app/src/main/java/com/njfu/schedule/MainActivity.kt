package com.njfu.schedule

import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.Gravity
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.launch
import java.time.temporal.ChronoUnit
import java.util.Calendar

class MainActivity : ComponentActivity() {

    private val refreshSignal = mutableStateOf(0)
    private var bgWebView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Repository.init(this)
        setContent { ScheduleApp(refreshSignal) }
        // 打开时自动更新课表（尽力而为，依赖已保存的登录会话）
        if (Repository.autoUpdate && Repository.hasCredentials()) {
            runBackgroundUpdate()
        }
    }

    /** jsStr: 把字符串安全地嵌入页面 JS 的单引号字面量 */
    private fun jsStr(s: String) = s
        .replace("\\", "\\\\").replace("'", "\\'")
        .replace("\n", "\\n").replace("\r", "")

    /**
     * 后台无界面 WebView 抓取更新：加载教务主页（含课表预览），
     * 若会话有效则直接抓；会话过期则尝试用保存的账号自动登录后再抓。
     * 成功才覆盖本地课表，失败保持原样。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun runBackgroundUpdate() {
        try {
            val wv = WebView(this)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.userAgentString = DESKTOP_UA
            wv.addJavascriptInterface(BgGrab(), "AndroidGrab")
            bgWebView = wv

            // 挂到 1x1 不可见容器，保证 JS 定时器能运行
            val holder = FrameLayout(this).apply { alpha = 0f }
            holder.addView(wv, FrameLayout.LayoutParams(1, 1))
            addContentView(holder, FrameLayout.LayoutParams(1, 1))

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    // 登录页的验证码框/节流检查是异步的，分批多试几次自动登录；
                    // 统一认证手机版模板会常驻渲染验证码输入框（未必真的要求填写），
                    // 所以不因它存在而跳过——若真需要验证码，提交失败无害，用户手动接管。
                    for (d in longArrayOf(300, 3000, 8000)) {
                        wv.postDelayed({ autoLoginOnce(wv) }, d)
                    }
                    // 登录跳转+课表渲染耗时不确定，分批多试几次抓取
                    for (d in longArrayOf(5000, 10000, 16000)) {
                        wv.postDelayed({
                            try {
                                wv.evaluateJavascript(GRAB_JS, null)
                                CookieManager.getInstance().flush()
                            } catch (_: Exception) {}
                        }, d)
                    }
                }
            }
            // 会话过期时该页会跳统一认证（service 带完整回跳地址），登录后回到课表页。
            // 注意：不能加载教务主页 xsMainV.jsp——主页没有课表网格，后台更新会永远抓空。
            wv.loadUrl(Repository.jwUrl.ifEmpty { "https://jwxt.njfu.edu.cn/jsxsd/xskb/xskb_list.do" })
            // 兜底释放
            wv.postDelayed({ destroyBgWebView() }, 25000L)
        } catch (e: Exception) {
            // 忽略：仍是上次保存的课表
        }
    }

    /** 在当前页面尝试一次自动登录：只在登录输入框为空时填写，避免覆盖用户正在输入的内容 */
    private fun autoLoginOnce(wv: WebView) {
        val u = Repository.user
        val p = Repository.pass
        if (u.isEmpty() || p.isEmpty()) return
        val js = "(function(){try{" +
                "var u=document.querySelector('input[name=username],input#username,input[name=user],input[name=yh]');" +
                "var pw=document.querySelector('input[type=password]');" +
                "if(!u||!pw)return 'nofield';" +
                "if(u.value&&pw.value)return 'filled';" +
                "u.value='${jsStr(u)}';pw.value='${jsStr(p)}';" +
                "var b=document.querySelector('button[type=submit],input[type=submit],#loginBtn,.loginBtn');" +
                "if(b){b.click();return 'submitted';}return 'nofield';" +
                "}catch(e){return 'err';}})();"
        wv.evaluateJavascript(js, null)
    }

    private fun destroyBgWebView() {
        val wv = bgWebView ?: return
        bgWebView = null
        try {
            (wv.parent as? FrameLayout)?.let { it.removeAllViews() }
            wv.destroy()
        } catch (_: Exception) {}
    }

    inner class BgGrab {
        @JavascriptInterface
        fun receive(html: String) {
            val result = JwxtParser.parseHtml(html)
            if (result != null && result.courses.isNotEmpty()) {
                Repository.replaceCourses(result.courses, result.term)
                runOnUiThread {
                    refreshSignal.value++
                    destroyBgWebView()
                }
            }
        }
    }

    companion object {
        /** 桌面 UA：统一认证/教务会给手机 UA 返回另一套页面模板，自动登录只适配桌面版 */
        const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        /** 抓取当前页面（含同源 iframe）里最像课表的表格 HTML */
        const val GRAB_JS = """(function(){function find(doc){var ts=doc.querySelectorAll('table');
for(var i=0;i<ts.length;i++){var tx=ts[i].textContent||'';
if(/周\s*期|星期|Week/.test(tx)&&ts[i].querySelectorAll('td').length>15)return true;}return false;}
function walk(win,d){try{if(d>4)return null;if(find(win.document))return win.document.documentElement.outerHTML;
for(var i=0;i<win.frames.length;i++){var r=walk(win.frames[i],d+1);if(r)return r;}}catch(e){}return null;}
var h=walk(window,0)||document.documentElement.outerHTML;AndroidGrab.receive(h);})();"""
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleApp(refreshState: MutableState<Int>) {
    val context = LocalContext.current
    var schedule by remember { mutableStateOf(Repository.loadSchedule()) }
    var showSettings by remember { mutableStateOf(false) }
    var gridView by remember { mutableStateOf(true) }
    var detail by remember { mutableStateOf<Course?>(null) }
    var addReq by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) } // 星期,节次,查看的周

    // 后台自动更新完成后刷新界面
    LaunchedEffect(refreshState.value) { schedule = Repository.loadSchedule() }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { schedule = Repository.loadSchedule() }

    val currentWeek = remember(schedule.termStartMonday, refreshState.value) {
        resolveCurrentWeek(schedule.termStartMonday)
    }
    val th = remember(refreshState.value) { currentAppColors(context) }

    MaterialTheme(
        colorScheme = if (th.dark)
            darkColorScheme(primary = th.accent, background = th.bg, surface = th.surface)
        else
            lightColorScheme(primary = th.accent, background = th.bg, surface = th.surface)
    ) {
    Scaffold(
        containerColor = th.bg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("南林课程表", fontWeight = FontWeight.Bold)
                        Text(
                            "第 $currentWeek 周 · ${CampusTimes.labels[schedule.campus] ?: "新庄"}",
                            fontSize = 12.sp, color = th.textSecondary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { gridView = !gridView }) {
                        Icon(
                            if (gridView) Icons.Default.ViewList else Icons.Default.Apps,
                            contentDescription = "切换课表/列表视图"
                        )
                    }
                    var expanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { expanded = true }) {
                        Icon(Icons.Default.Place, "切换校区")
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        CampusTimes.labels.forEach { (key, label) ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(label)
                                        if (schedule.campus == key) {
                                            Spacer(Modifier.width(8.dp))
                                            Text("✓", color = th.accent)
                                        }
                                    }
                                },
                                onClick = {
                                    Repository.saveSchedule(schedule.copy(campus = key))
                                    schedule = Repository.loadSchedule()
                                    expanded = false
                                }
                            )
                        }
                    }
                    IconButton(onClick = {
                        launcher.launch(Intent(context, ImportActivity::class.java))
                    }) { Icon(Icons.Default.Refresh, "更新课表") }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = th.surface,
                    titleContentColor = th.textPrimary,
                    actionIconContentColor = th.textSecondary
                )
            )
        }
    ) { pad ->
        if (schedule.courses.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有课表", color = th.textSecondary)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        launcher.launch(Intent(context, ImportActivity::class.java))
                    }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("去导入课表")
                    }
                }
            }
        } else if (gridView) {
            Box(Modifier.padding(pad)) {
                TimetableView(
                    schedule, currentWeek, th,
                    onCourseClick = { detail = it },
                    onAddCourse = { d, p, w -> addReq = Triple(d, p, w) }
                )
            }
        } else {
            val byDay = schedule.courses.groupBy { it.day }
            LazyColumn(
                Modifier.padding(pad).fillMaxSize(),
                contentPadding = PaddingValues(12.dp)
            ) {
                for (day in 1..7) {
                    val list = byDay[day] ?: continue
                    item(key = "h$day") {
                        Text(
                            dayName(day),
                            fontWeight = FontWeight.Bold, fontSize = 16.sp,
                            color = th.accent,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }
                    items(list.sortedBy { it.startPeriod }, key = { it.id }) { c ->
                        CourseCard(c, schedule.campus, th)
                    }
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(schedule, th, onDone = {
            showSettings = false
            schedule = Repository.loadSchedule()
            refreshState.value++
        })
    }

    detail?.let { c0 ->
        // 保存备忘后从存储里取最新（含 note 字段）
        val c = schedule.courses.firstOrNull { it.id == c0.id } ?: c0
        val times = CampusTimes.times(schedule.campus)
        val t1 = c.startTime.ifEmpty { times[c.startPeriod]?.first ?: "" }
        val t2 = c.endTime.ifEmpty { times[c.endPeriod]?.second ?: "" }
        val color = th.cardColor(c.name)
        var note by remember(c.id) { mutableStateOf(c.note ?: "") }
        val noteDirty = note.trim() != (c.note ?: "")
        MacDialog(color = th.surface, onDismissRequest = { detail = null }) { close ->
            Column(Modifier.fillMaxWidth()) {
                // 课程色头部：课名 + 时间
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(color.first)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                c.name, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                                color = color.second, modifier = Modifier.weight(1f, fill = false)
                            )
                            if (c.day == 0) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "实习", fontSize = 9.sp, color = color.second,
                                    modifier = Modifier
                                        .background(Color.White.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                val timeText = if (c.day == 0) "周一至周五 · 第1-8节"
                else "${dayName(c.day)} 第${c.startPeriod}-${c.endPeriod}节" +
                        if (t1.isNotEmpty() && t2.isNotEmpty()) " · $t1-$t2" else ""
                InfoRow("⏰", "节次", timeText, th)
                InfoRow("📍", "教室", c.location, th)
                InfoRow("👨‍🏫", "老师", c.teacher, th)
                InfoRow("🔁", "周次", weekLabel(c.weeks), th)
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = th.divider, thickness = 1.dp)
                Spacer(Modifier.height(10.dp))
                Text("📝 备忘录", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = th.textSecondary)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    note, { note = it },
                    placeholder = {
                        Text(
                            "记点什么…（作业、考试、带书）",
                            fontSize = 12.sp, color = th.textSecondary.copy(alpha = 0.7f)
                        )
                    },
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = th.textPrimary),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 74.dp)
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (noteDirty) {
                        Text("未保存", fontSize = 11.sp, color = th.textSecondary)
                        Spacer(Modifier.width(8.dp))
                    }
                    TextButton(
                        enabled = noteDirty,
                        onClick = {
                            Repository.updateNote(c.id, note.trim())
                            schedule = Repository.loadSchedule()
                            detail = schedule.courses.firstOrNull { it.id == c.id } ?: c0
                        }
                    ) { Text("保存备忘", color = if (noteDirty) th.accent else th.textSecondary) }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = close) { Text("关闭", color = th.textSecondary) }
                }
            }
        }
    }

    addReq?.let { (d, p, w) ->
        AddCourseDialog(
            day = d, period = p, viewedWeek = w, th = th,
            onDismiss = { addReq = null },
            onConfirm = { c ->
                Repository.addCourses(listOf(c), "")
                schedule = Repository.loadSchedule()
                addReq = null
            }
        )
    }
    }
}

@Composable
fun CourseCard(c: Course, campus: String, th: AppColors) {
    val times = CampusTimes.times(campus)
    val t1 = c.startTime.ifEmpty { times[c.startPeriod]?.first ?: "" }
    val t2 = c.endTime.ifEmpty { times[c.endPeriod]?.second ?: "" }
    val span = if (c.startPeriod == c.endPeriod) "第${c.startPeriod}节"
    else "第${c.startPeriod}-${c.endPeriod}节"
    Card(
        Modifier.padding(bottom = 8.dp).fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = th.surface)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(c.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = th.textPrimary)
            Spacer(Modifier.height(4.dp))
            val info = buildList {
                add("⏰ $span" + if (t1.isNotEmpty() && t2.isNotEmpty()) "（$t1-$t2）" else "")
                if (c.location.isNotEmpty()) add("📍 ${c.location}")
                if (c.teacher.isNotEmpty()) add("👨‍🏫 ${c.teacher}")
                add("✅ ${weekLabel(c.weeks)}")
            }
            info.forEach { Text(it, fontSize = 12.sp, color = th.textSecondary) }
            if (!c.note.isNullOrEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("📝 ${c.note}", fontSize = 12.sp, color = th.textPrimary)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableView(
    schedule: Schedule,
    currentWeek: Int,
    th: AppColors,
    onCourseClick: (Course) -> Unit,
    onAddCourse: (day: Int, period: Int, viewedWeek: Int) -> Unit
) {
    val today = remember { LocalDate.now() }
    val pagerState = rememberPagerState(
        initialPage = (currentWeek - 1).coerceIn(0, 52),
        pageCount = { 53 }
    )
    val viewedWeek by remember { derivedStateOf { pagerState.currentPage + 1 } }
    val scope = rememberCoroutineScope()
    val slotH = 58.dp
    var selectedId by remember { mutableStateOf<String?>(null) }
    // 翻周时清除选中高亮
    LaunchedEffect(pagerState.currentPage) { selectedId = null }
    // 一周起点可设为周日：列顺序变化，日期按 周日=该周最后一天 重排
    val dayCols = if (Repository.weekStartSunday) listOf(7, 1, 2, 3, 4, 5, 6) else (1..7).toList()
    val dateIdx: (Int) -> Int = if (Repository.weekStartSunday)
        { i -> if (i == 0) 6 else i - 1 } else { i -> i }

    Column(Modifier.fillMaxSize()) {
        // 表头：全年日期条（53 周），可横向滚动；松手后按可见日期判定周次并对齐
        val headerState = rememberLazyListState()
        val headerDragging by headerState.interactionSource.collectIsDraggedAsState()
        val stripStart = remember(schedule.termStartMonday) {
            try {
                LocalDate.parse(schedule.termStartMonday, DateTimeFormatter.ofPattern("yyyy-MM-dd")).minusDays(7)
            } catch (e: Exception) {
                null
            }
        }
        var headerInitiated by remember { mutableStateOf(false) }
        // 日期条拖动松手：判定周次 → 对齐 pager 与日期条（唯一会写 pager 的路径）
        LaunchedEffect(headerDragging) {
            if (headerDragging) {
                headerInitiated = true
            } else if (headerInitiated && stripStart != null) {
                val w = weekOfDate(
                    schedule.termStartMonday,
                    stripStart.plusDays(headerState.firstVisibleItemIndex.toLong()), 53
                )
                if (pagerState.currentPage != w - 1) pagerState.animateScrollToPage(w - 1)
                headerState.animateScrollToItem(7 + (w - 1) * 7)
                headerInitiated = false
            }
        }
        // pager 翻页时只同步日期条位置，绝不写 pager（避免滑动中被拽回）
        LaunchedEffect(viewedWeek) {
            if (!headerInitiated && !headerDragging && stripStart != null) {
                headerState.animateScrollToItem(7 + (viewedWeek - 1) * 7)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(46.dp), contentAlignment = Alignment.Center) {
                Text(
                    "第${viewedWeek}周", fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    color = th.accent,
                    modifier = Modifier
                        .background(th.accent.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
            LazyRow(state = headerState, modifier = Modifier.weight(1f)) {
            items(count = 53 * 7) { i ->
                val date = stripStart?.plusDays(i.toLong())
                val w = if (date != null) weekOfDate(schedule.termStartMonday, date, 53) else viewedWeek
                val inViewed = w == viewedWeek
                val isToday = date == today
                Column(
                    Modifier
                        .width(46.dp)
                        .height(44.dp)
                        .background(if (inViewed) th.todayTint else Color.Transparent)
                        .clickable {
                            if (date != null) {
                                val ww = weekOfDate(schedule.termStartMonday, date, 53)
                                scope.launch {
                                    pagerState.animateScrollToPage(ww - 1)
                                    headerState.animateScrollToItem(7 + (ww - 1) * 7)
                                }
                            }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        if (date == null) "" else if (isToday) "今天" else DAY_SHORT[date.dayOfWeek.value - 1],
                        fontSize = 11.sp, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                        color = if (isToday || inViewed) th.accent else th.textPrimary
                    )
                    Text(
                        date?.let { "${it.monthValue}/${it.dayOfMonth}" } ?: "",
                        fontSize = 9.sp, color = if (isToday) th.accent else th.textSecondary
                    )
                }
            }
            }
        }
        HorizontalDivider(color = th.divider, thickness = 1.dp)

        // 滑到别的周时提示"回到本周"
        AnimatedVisibility(visible = viewedWeek != currentWeek) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "回到第 $currentWeek 周（本周）",
                    fontSize = 11.sp, color = th.accent,
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .background(th.accent.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
                        .clickable { scope.launch { pagerState.animateScrollToPage((currentWeek - 1).coerceIn(0, 52)) } }
                        .padding(horizontal = 12.dp, vertical = 3.dp)
                )
            }
        }

        Row(Modifier.weight(1f).fillMaxWidth()) {
            // 左列：节次 + 上课时间（固定不随翻页滚动）
            Column(Modifier.width(46.dp)) {
                for (p in 1..11) {
                    val nowP = viewedWeek == currentWeek && nowPeriod(schedule.campus, p)
                    Box(Modifier.height(slotH).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "$p", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                color = if (nowP) th.accent else th.textSecondary
                            )
                            val t = CampusTimes.times(schedule.campus)[p]
                            Text(
                                t?.first ?: "", fontSize = 8.sp,
                                color = if (nowP) th.accent else th.textSecondary
                            )
                            Text(
                                t?.second ?: "", fontSize = 8.sp,
                                color = if (nowP) th.accent else th.textSecondary
                            )
                        }
                    }
                }
            }
            // 左右滑动切换周次，每页一周的 7 列课程
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                beyondViewportPageCount = 1
            ) { page ->
                val week = page + 1
                val weekCourses = remember(schedule.courses, week) {
                    schedule.courses.filter { it.weeks.isEmpty() || week in it.weeks }
                }
                val byDay = weekCourses.groupBy { it.day }
                val internships = weekCourses.filter { it.day == 0 }
                val dates = weekDates(schedule.termStartMonday, week)
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Box(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth()) {
                            for ((i, d) in dayCols.withIndex()) {
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .height(slotH * 11)
                                        .background(if (dates.getOrNull(dateIdx(i)) == today) th.todayTint else Color.Transparent)
                                ) {
                                    for (seg in remember(week, schedule.courses) { daySegments(byDay[d] ?: emptyList()) }) {
                                        val c = seg.course
                                        if (c != null) {
                                            val color = th.cardColor(c.name)
                                            Box(
                                                Modifier
                                                    .padding(horizontal = 1.dp, vertical = 2.dp)
                                                    .height(slotH * seg.span - 4.dp)
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(color.first)
                                                    .then(
                                                        if (selectedId == c.id)
                                                            Modifier.border(2.dp, th.accent, RoundedCornerShape(10.dp))
                                                        else Modifier
                                                    )
                                                    .pointerInput(c.id) {
                                                        detectTapGestures(
                                                            onTap = {
                                                                selectedId = if (selectedId == c.id) null else c.id
                                                            },
                                                            onDoubleTap = { onCourseClick(c) }
                                                        )
                                                    }
                                            ) {
                                                Column(Modifier.padding(4.dp)) {
                                                    Text(
                                                        c.name, fontSize = 10.sp, lineHeight = 12.sp,
                                                        fontWeight = FontWeight.SemiBold, color = color.second
                                                    )
                                                    if (c.location.isNotEmpty()) {
                                                        Text(
                                                            "@${c.location}", fontSize = 9.sp, lineHeight = 11.sp,
                                                            color = color.second.copy(alpha = 0.75f)
                                                        )
                                                    }
                                                    if (c.teacher.isNotEmpty()) {
                                                        Text(
                                                            c.teacher, fontSize = 9.sp, lineHeight = 11.sp,
                                                            color = color.second.copy(alpha = 0.75f)
                                                        )
                                                    }
                                                }
                                                if (!c.note.isNullOrEmpty()) {
                                                    Text(
                                                        "📝", fontSize = 9.sp,
                                                        modifier = Modifier.align(Alignment.TopEnd).padding(3.dp)
                                                    )
                                                }
                                            }
                                        } else {
                                            // 空白格：点按手动添加课程
                                            Box(
                                                Modifier
                                                    .padding(horizontal = 1.dp, vertical = 1.dp)
                                                    .height(slotH * seg.span - 2.dp)
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable { onAddCourse(d, seg.start, week) },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text("+", fontSize = 15.sp, color = th.textSecondary.copy(alpha = 0.25f))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // 整周实习/课程设计：盖在周一~周五 × 第1-8节上的大卡片
                        if (internships.isNotEmpty()) {
                            Column(
                                Modifier
                                    .fillMaxWidth(5f / 7f)
                                    .height(slotH * 8)
                                    .padding(horizontal = 3.dp, vertical = 3.dp)
                            ) {
                                for (c in internships) {
                                    val color = th.cardColor(c.name)
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .weight(1f)
                                            .padding(vertical = 3.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(color.first)
                                            .clickable { onCourseClick(c) }
                                    ) {
                                        Column(Modifier.padding(10.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    "实习", fontSize = 9.sp, color = color.second,
                                                    modifier = Modifier
                                                        .background(Color.White.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    "周一至周五 · 第1-8节", fontSize = 9.sp,
                                                    color = color.second.copy(alpha = 0.75f)
                                                )
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Text(c.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color.second)
                                            if (c.teacher.isNotEmpty()) {
                                                Spacer(Modifier.height(2.dp))
                                                Text("👨‍🏫 ${c.teacher}", fontSize = 10.sp, color = color.second.copy(alpha = 0.8f))
                                            }
                                        }
                                        Text(
                                            weekLabel(c.weeks), fontSize = 10.sp,
                                            color = color.second.copy(alpha = 0.75f),
                                            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 一天 11 节的布局段：课程块 + 可点击的空白块（重叠时裁剪保证不越界） */
private data class GridSeg(val course: Course?, val start: Int, val span: Int)

private fun daySegments(list: List<Course>): List<GridSeg> {
    val segs = mutableListOf<GridSeg>()
    var cursor = 1
    for (c in list.sortedBy { it.startPeriod }) {
        val start = maxOf(c.startPeriod, cursor)
        val end = minOf(c.endPeriod, 11)
        if (end < start) continue
        if (start > cursor) segs.add(GridSeg(null, cursor, start - cursor))
        segs.add(GridSeg(c, start, end - start + 1))
        cursor = end + 1
    }
    if (cursor <= 11) segs.add(GridSeg(null, cursor, 11 - cursor + 1))
    return segs
}

/** 马卡龙配色：浅色底 + 同系深色字，按课名稳定取色 */
private val MACARON_COLORS = listOf(
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

fun macaronColor(name: String): Pair<Color, Color> {
    var h = 0
    for (ch in name) h = h * 31 + ch.code
    return MACARON_COLORS[((h % MACARON_COLORS.size) + MACARON_COLORS.size) % MACARON_COLORS.size]
}

/** 空白格点开的"手动添加课程"对话框：星期/起始节次已按点按位置预填 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCourseDialog(
    day: Int,
    period: Int,
    viewedWeek: Int,
    th: AppColors,
    onDismiss: () -> Unit,
    onConfirm: (Course) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var teacher by remember { mutableStateOf("") }
    var loc by remember { mutableStateOf("") }
    var startP by remember { mutableStateOf(period) }
    var endP by remember { mutableStateOf(period) }
    var mode by remember { mutableStateOf(0) } // 0每周 1单周 2双周 3仅本周

    val valid = name.isNotBlank() && startP in 1..11 && endP in startP..11
    MacDialog(color = th.surface, onDismissRequest = onDismiss) { close ->
        Column {
            Text(
                "添加课程 · ${dayName(day)}第${period}节",
                fontSize = 17.sp, fontWeight = FontWeight.Bold, color = th.textPrimary
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(name, { name = it },
                label = { Text("课程名（必填）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(teacher, { teacher = it },
                    label = { Text("老师") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(loc, { loc = it },
                    label = { Text("教室") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("第", fontSize = 13.sp, color = th.textSecondary)
                OutlinedTextField(
                    startP.toString(),
                    { s -> s.filter { it.isDigit() }.take(2).toIntOrNull()?.let { if (it in 1..11) startP = it } },
                    singleLine = true, modifier = Modifier.width(58.dp).padding(horizontal = 4.dp)
                )
                Text("节 到", fontSize = 13.sp, color = th.textSecondary)
                OutlinedTextField(
                    endP.toString(),
                    { s -> s.filter { it.isDigit() }.take(2).toIntOrNull()?.let { if (it in 1..11) endP = it } },
                    singleLine = true, modifier = Modifier.width(58.dp).padding(horizontal = 4.dp)
                )
                Text("节", fontSize = 13.sp, color = th.textSecondary)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val chipLabel = @Composable { t: String ->
                    Text(t, fontSize = 12.sp, maxLines = 1)
                }
                FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { chipLabel("每周") })
                FilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { chipLabel("单周") })
                FilterChip(selected = mode == 2, onClick = { mode = 2 }, label = { chipLabel("双周") })
                FilterChip(selected = mode == 3, onClick = { mode = 3 }, label = { chipLabel("仅本周") })
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = close) { Text("取消", color = th.textSecondary) }
                Spacer(Modifier.width(4.dp))
                Button(enabled = valid, onClick = {
                    val weeks = when (mode) {
                        0 -> (1..20).toList()
                        1 -> (1..20).filter { it % 2 == 1 }
                        2 -> (1..20).filter { it % 2 == 0 }
                        else -> listOf(viewedWeek)
                    }
                    onConfirm(
                        Course(
                            id = UUID.randomUUID().toString().take(8),
                            name = name.trim(),
                            teacher = teacher.trim(),
                            location = loc.trim(),
                            day = day,
                            startPeriod = startP,
                            endPeriod = endP,
                            weeks = weeks
                        )
                    )
                }) { Text("保存") }
            }
        }
    }
}

/** 某日期属于第几教学周（按周一为周首），夹在 1..maxWeek */
fun weekOfDate(termStartMonday: String, date: LocalDate, maxWeek: Int): Int = try {
    val s = LocalDate.parse(termStartMonday, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
    ((ChronoUnit.DAYS.between(s, date)) / 7 + 1).toInt().coerceIn(1, maxWeek)
} catch (e: Exception) {
    1
}

private val DAY_SHORT = listOf("一", "二", "三", "四", "五", "六", "日")

/** 某教学周 7 天的日期（周一~周日），解析失败返回 null 列表 */
fun weekDates(startMonday: String, week: Int): List<LocalDate?> = try {
    val s = LocalDate.parse(startMonday, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
    val monday = s.plusDays(((week - 1) * 7).toLong())
    (0..6).map { monday.plusDays(it.toLong()) }
} catch (e: Exception) {
    List(7) { null }
}

/** 当前时间是否正处于第 p 节（今天高亮左列用） */
fun nowPeriod(campus: String, p: Int): Boolean {
    val t = CampusTimes.times(campus)[p] ?: return false
    return try {
        val now = LocalTime.now()
        !now.isBefore(LocalTime.parse(t.first)) && now.isBefore(LocalTime.parse(t.second))
    } catch (e: Exception) {
        false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(schedule: Schedule, th: AppColors, onDone: () -> Unit) {
    val context = LocalContext.current
    var user by remember { mutableStateOf(Repository.user) }
    var pass by remember { mutableStateOf("") }
    var savePass by remember { mutableStateOf(Repository.savedPass) }
    var auto by remember { mutableStateOf(Repository.autoUpdate) }
    var url by remember { mutableStateOf(Repository.jwUrl) }
    var start by remember { mutableStateOf(schedule.termStartMonday) }
    var weekOverride by remember {
        mutableStateOf(Repository.weekOverride.takeIf { it > 0 }?.toString() ?: "")
    }
    var mode by remember { mutableStateOf(Repository.themeMode) }
    var paletteId by remember { mutableStateOf(Repository.paletteId) }
    var customHue by remember { mutableStateOf(Repository.customHue) }
    var invert by remember { mutableStateOf(Repository.invert) }
    var startSunday by remember { mutableStateOf(Repository.weekStartSunday) }

    MacDialog(color = th.surface, onDismissRequest = onDone) { close ->
        Column {
            // 内层滚动列只放表单内容，取消/保存按钮固定在卡片底部
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("设置", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = th.textPrimary)

                SectionTitle("账号与导入", th.accent)
                OutlinedTextField(user, { user = it },
                    label = { Text("学号 / 账号") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(pass, { pass = it },
                    label = { Text(if (savePass) "密码（已保存，留空不改）" else "密码") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(url, { url = it },
                    label = { Text("教务网址（默认课表页，可改）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(savePass, { savePass = it })
                    Text("保存密码", fontSize = 13.sp, color = th.textPrimary)
                    Spacer(Modifier.width(10.dp))
                    Checkbox(auto, { auto = it })
                    Text("打开时自动更新课表", fontSize = 13.sp, color = th.textPrimary)
                }

                SectionTitle("一周起点", th.accent)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !startSunday, onClick = { startSunday = false },
                        label = { Text("周一", fontSize = 12.sp) })
                    FilterChip(selected = startSunday, onClick = { startSunday = true },
                        label = { Text("周日", fontSize = 12.sp) })
                }

                SectionTitle("色系", th.accent)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PALETTE_NAMES.forEachIndexed { i, n ->
                        FilterChip(
                            selected = customHue < 0 && paletteId == i,
                            onClick = { paletteId = i; customHue = -1 },
                            label = { Text(n, fontSize = 12.sp, maxLines = 1) }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = customHue in 0..359,
                        onClick = { if (customHue < 0) customHue = 210 },
                        label = { Text("自定义", fontSize = 12.sp, maxLines = 1) }
                    )
                    Spacer(Modifier.width(6.dp))
                    Slider(
                        value = (if (customHue in 0..359) customHue else 210).toFloat(),
                        onValueChange = { customHue = it.toInt() },
                        valueRange = 0f..359f,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (customHue in 0..359) Color.hsl(customHue.toFloat(), 0.55f, 0.5f)
                                else th.accent
                            )
                    )
                }

                SectionTitle("外观", th.accent)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = mode == "auto", onClick = { mode = "auto" },
                        label = { Text("跟随系统", fontSize = 12.sp) })
                    FilterChip(selected = mode == "light", onClick = { mode = "light" },
                        label = { Text("浅色", fontSize = 12.sp) })
                    FilterChip(selected = mode == "dark", onClick = { mode = "dark" },
                        label = { Text("深色", fontSize = 12.sp) })
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = invert, onCheckedChange = { invert = it })
                    Spacer(Modifier.width(8.dp))
                    Text("反色（在当前模式上翻转明暗）", fontSize = 13.sp, color = th.textPrimary)
                }

                SectionTitle("学期", th.accent)
                OutlinedButton(onClick = {
                    val cal = Calendar.getInstance()
                    DatePickerDialog(context, { _, y, m, d ->
                        start = "%04d-%02d-%02d".format(y, m + 1, d)
                    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH),
                        cal.get(Calendar.DAY_OF_MONTH)).show()
                }) { Text("第一周周一：$start", fontSize = 13.sp) }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(weekOverride, {
                    weekOverride = it.filter { ch -> ch.isDigit() }.take(2)
                }, label = { Text("手动指定当前周（可空=自动）") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            }

            HorizontalDivider(color = th.divider, thickness = 1.dp)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = close) { Text("取消", color = th.textSecondary) }
                Spacer(Modifier.width(4.dp))
                Button(onClick = {
                    Repository.user = user
                    if (pass.isNotEmpty()) {
                        Repository.pass = pass
                        Repository.savedPass = savePass
                    } else {
                        Repository.savedPass = savePass
                    }
                    Repository.autoUpdate = auto
                    Repository.jwUrl = url.trim()
                    Repository.weekOverride = weekOverride.toIntOrNull() ?: 0
                    Repository.saveSchedule(schedule.copy(termStartMonday = start))
                    Repository.themeMode = mode
                    Repository.paletteId = paletteId
                    Repository.customHue = customHue
                    Repository.invert = invert
                    Repository.weekStartSunday = startSunday
                    Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                    close()
                }) { Text("保存") }
            }
        }
    }
}

/** 详情弹窗的信息行：图标 + 标签 + 值 */
@Composable
fun InfoRow(icon: String, label: String, value: String, th: AppColors) {
    if (value.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, fontSize = 13.sp)
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 11.sp, color = th.textSecondary, modifier = Modifier.width(36.dp))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = th.textPrimary)
    }
}

fun dayName(d: Int) =
    listOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")[d.coerceIn(1, 7)]

/** 把周次列表压缩成 "1-3,9-10周" 这样的标签 */
fun weekLabel(weeks: List<Int>): String {
    if (weeks.isEmpty()) return "全学期"
    val w = weeks.sorted()
    val ranges = mutableListOf<Pair<Int, Int>>()
    var s = w[0]
    var prev = w[0]
    for (i in 1 until w.size) {
        if (w[i] == prev + 1) {
            prev = w[i]
        } else {
            ranges.add(s to prev)
            s = w[i]
            prev = w[i]
        }
    }
    ranges.add(s to prev)
    return ranges.joinToString(",") { (a, b) -> if (a == b) "$a" else "$a-$b" } + "周"
}

/** 计算当前教学周：优先手动指定，否则按第一周周一推算 */
fun resolveCurrentWeek(startMonday: String): Int {
    if (Repository.weekOverride > 0) return Repository.weekOverride
    return try {
        val start = LocalDate.parse(startMonday, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val diff = ChronoUnit.DAYS.between(start, LocalDate.now())
        if (diff < 0) 1 else (diff / 7 + 1).toInt()
    } catch (e: Exception) {
        1
    }
}
