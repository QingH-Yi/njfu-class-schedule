package com.njfu.schedule

import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.temporal.ChronoUnit
import java.util.Calendar

class MainActivity : ComponentActivity() {

    private val refreshSignal = mutableStateOf(0)
    private var bgWebView: WebView? = null
    private var bgGrab: BgGrab? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Repository.init(this)

        // 覆盖安装（版本号变了且不是首次安装）：先清掉旧版本残留的课表数据，再强制抓取一次
        val verCode = try {
            packageManager.getPackageInfo(packageName, 0).versionCode
        } catch (e: Exception) {
            0
        }
        val upgraded = Repository.appVersion != 0 && Repository.appVersion != verCode
        if (upgraded) {
            // 覆盖安装：只清本地课表（保留账号/外观）；不在启动路径建 WebView（会拖慢开机）
            Repository.clearCourses()
        }
        Repository.appVersion = verCode

        setContent { ScheduleApp(refreshSignal) }
        // 打开时自动更新课表（尽力而为，依赖已保存的登录会话）；
        // 覆盖安装后的第一次打开即使关了自动更新也强制抓一次，保证数据是干净的
        if (Repository.hasCredentials() && (Repository.autoUpdate || upgraded)) {
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
            val grab = BgGrab()
            bgGrab = grab
            wv.addJavascriptInterface(grab, "AndroidGrab")
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
        private val tableRounds = ArrayList<String>()
        private val tableResults = ArrayList<ParseResult>()
        private val creditRounds = ArrayList<Map<String, String>>()
        private var roundsStarted = false

        @JavascriptInterface
        fun receive(html: String) {
            val result = JwxtParser.parseHtml(html) ?: return
            if (result.courses.isEmpty()) return
            var done = false
            synchronized(tableRounds) {
                if (tableRounds.size >= 3 || creditRounds.isNotEmpty()) return
                tableRounds.add(tableSignature(result))
                tableResults.add(result)
                done = tableRounds.size == 3
            }
            if (!done) return
            // 课表三轮完全一致才入库（防抓错/抓一半）；不一致保持旧课表不动
            if (tableRounds.toSet().size != 1) {
                runOnUiThread { destroyBgWebView() }
                return
            }
            val r = tableResults[0]
            // 用户手动改过课表：保留修改，别被教务覆盖
            Repository.replaceCourses(r.courses, r.term, keepEdited = Repository.editedCourses().isNotEmpty())
            runOnUiThread {
                refreshSignal.value++
                startCreditRounds(r.term.ifEmpty { Repository.loadSchedule().term })
            }
        }

        /** 收到一轮个人中心课表 HTML（悬停浮层 div.item-box 里带「学分：X」） */
        @JavascriptInterface
        fun receiveKb(html: String) {
            if (html.isEmpty()) return
            val map = try {
                JwxtParser.parseCredits(html)
            } catch (e: Exception) {
                emptyMap<String, String>()
            }
            var done = false
            synchronized(creditRounds) {
                if (creditRounds.size < 3) {
                    creditRounds.add(map)
                    done = creditRounds.size == 3
                }
            }
            if (done) {
                // 三轮完全一致的课名才采纳，防止爬错
                val merged = Repository.mergeCreditsRounds(creditRounds.toList())
                Repository.applyCredits(merged)
                runOnUiThread {
                    refreshSignal.value++
                    destroyBgWebView()
                }
            }
        }

        /** 三轮学分抓取只启动一次（GRAB_JS 会多次回传课表） */
        fun markRoundsStarted(): Boolean = synchronized(this) {
            if (roundsStarted) false else { roundsStarted = true; true }
        }
    }

    /** 课表规范化签名：三轮签名一致才算没抓错 */
    private fun tableSignature(r: ParseResult): String = buildString {
        append(r.term)
        r.courses.sortedWith(compareBy({ it.day }, { it.startPeriod }, { it.name })).forEach {
            append("|${it.name}#${it.day}#${it.startPeriod}#${it.endPeriod}#${it.teacher}#${it.location}#${it.weeks.joinToString(",")}")
        }
    }

    /** 课表抓到后连抓三轮学分（个人中心课表接口），三轮一致才写库 */
    private fun startCreditRounds(term: String) {
        val wv = bgWebView ?: return
        val grab = bgGrab ?: return
        if (!grab.markRoundsStarted()) return
        val js = loadKbJs(term)
        for (d in longArrayOf(500, 3000, 5500)) {
            wv.postDelayed({
                try { bgWebView?.evaluateJavascript(js, null) } catch (_: Exception) {}
            }, d)
        }
    }

    companion object {
        /** GitHub 用户名（改名后统一在这里改） */
        const val GITHUB_USER = "QingH-Yi"
        /** 站点主页 */
        const val SITE_URL = "https://qingh-yi.github.io/njfu-class-schedule/"
        /** GitHub 仓库主页（教程/设置页展示，检查更新也指向这里） */
        const val GITHUB_URL = "https://github.com/$GITHUB_USER/njfu-class-schedule"
        private const val RELEASE_API =
                "https://api.github.com/repos/$GITHUB_USER/njfu-class-schedule/releases/latest"

        fun currentVersionName(ctx: android.content.Context): String = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }

        /** remote 是否比 current 新（支持 "v1.2.1" / "1.2" 等写法） */
        fun isNewerVersion(remote: String, current: String): Boolean {
            fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V")
                .split(".").map { seg -> seg.filter { it.isDigit() }.toIntOrNull() ?: 0 }
            val a = parts(remote)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        /** 拉取 GitHub 最新 Release 的 (版本号, 链接)。国内网络可能连不上，失败返回 null 静默跳过 */
        fun fetchLatestRelease(): Pair<String, String>? {
            var conn: HttpURLConnection? = null
            return try {
                conn = URL(RELEASE_API).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "njfu-class-schedule")
                if (conn.responseCode != 200) return null
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val tag = json.optString("tag_name")
                if (tag.isEmpty()) null
                else tag to json.optString("html_url").ifEmpty { GITHUB_URL }
            } catch (e: Exception) {
                null
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }

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
    var editTarget by remember { mutableStateOf<Course?>(null) }
    var addReq by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) } // 星期,节次,查看的周
    var showTutorial by remember { mutableStateOf(Repository.showTutorial) }

    // 后台自动更新完成后刷新界面
    LaunchedEffect(refreshState.value) { schedule = Repository.loadSchedule() }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { schedule = Repository.loadSchedule() }

    val currentWeek = remember(schedule.termStartMonday, refreshState.value) {
        resolveCurrentWeek(schedule.termStartMonday)
    }
    val th = remember(refreshState.value) { currentAppColors(context) }

    // 后台检查新版本（GitHub Release），发现新版在顶部显示横条；连不上就算了
    var updateInfo by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(Unit) {
        if (Repository.checkUpdate) {
            val remote = withContext(Dispatchers.IO) { MainActivity.fetchLatestRelease() }
            if (remote != null &&
                MainActivity.isNewerVersion(remote.first, MainActivity.currentVersionName(context))
            ) {
                updateInfo = remote
            }
        }
    }

    MaterialTheme(
        colorScheme = if (th.dark)
            darkColorScheme(primary = th.accent, background = th.bg, surface = th.surface)
        else
            lightColorScheme(primary = th.accent, background = th.bg, surface = th.surface)
    ) {
    // 任何设置 / 数据变化都让整棵界面重新读一遍（等效"重启"，但不会闪退、不丢画面）
    key(refreshState.value) {
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
        Column(Modifier.padding(pad).fillMaxSize()) {
            updateInfo?.let { (v, url) ->
                Text(
                    "⬇️ 发现新版本 v$v，点击前往下载",
                    fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(th.accent)
                        .clickable { openUrl(context, url) }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (schedule.courses.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                    TimetableView(
                        schedule, currentWeek, th,
                        onCourseClick = { detail = it },
                        onAddCourse = { d, p, w -> addReq = Triple(d, p, w) }
                    )
                } else {
                    val byDay = schedule.courses.groupBy { it.day }
                    LazyColumn(
                        Modifier.fillMaxSize(),
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
        }
    }

    if (showSettings) {
        SettingsDialog(schedule, th, onDone = {
            showSettings = false
            schedule = Repository.loadSchedule()
            refreshState.value++
        }, onShowTutorial = { showTutorial = true })
    }

    detail?.let { c0 ->
        // 保存后从存储里取最新（含 note/credit 字段）
        val c = schedule.courses.firstOrNull { it.id == c0.id } ?: c0
        val color = th.cardColor(c.name)
        var note by remember(c.id) { mutableStateOf(c.note ?: "") }
        val noteDirty = note.trim() != (c.note ?: "")
        // 同名课在本周的其他上课时间（如周三/周五都上 → 逐行列出）
        val sameName = schedule.courses
            .filter { it.name == c.name && it.day != 0 }
            .sortedWith(compareBy({ it.day }, { it.startPeriod }))
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
                // 信息区 + 备忘录放进有上限的可滚动区域：字段多或小屏时也不裁切、不互相挤
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (c.day == 0) InfoRow("⏰", "安排", "整周（周一至周五）", th)
                    else PeriodRows(c, sameName, schedule.campus, th)
                    InfoRow("📍", "教室", c.location, th)
                    InfoRow("👨‍🏫", "老师", c.teacher, th)
                    InfoRow("🔁", "周次", weekLabel(c.weeks), th)
                    InfoRow("🎓", "学分", c.credit.orEmpty(), th)
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
                }
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
                            // 学分以爬取的为准（只读），保存时原样带回归里
                            Repository.updateCourseMeta(c.id, note.trim(), c.credit ?: "")
                            schedule = Repository.loadSchedule()
                            detail = schedule.courses.firstOrNull { it.id == c.id } ?: c0
                        }
                    ) { Text("保存", color = if (noteDirty) th.accent else th.textSecondary) }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = { editTarget = c }) {
                        Text("编辑", color = th.accent)
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = close) { Text("关闭", color = th.textSecondary) }
                }
            }
        }
    }

    // 编辑课程：可改节次 / 周次，并选择只改"本周"还是"整个学期"
    editTarget?.let { editing ->
        val cur = schedule.courses.firstOrNull { it.id == editing.id } ?: editing
        EditCourseDialog(
            course = cur, currentWeek = currentWeek, th = th,
            onDismiss = { editTarget = null },
            onConfirm = { name, teacher, location, day, startP, endP, weeks, onlyThisWeek ->
                Repository.editCourse(cur, name, teacher, location, day, startP, endP, weeks, onlyThisWeek, currentWeek)
                schedule = Repository.loadSchedule()
                editTarget = null
                detail = null
                refreshState.value++   // 整界面重载，确保生效
            }
        )
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

    // 放在最后组合：弹窗窗口按加入顺序叠放，教程才能盖住设置弹窗
    if (showTutorial) {
        TutorialDialog(th = th, onDone = { showTutorial = false })
    }
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
            Text(
                c.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = th.textPrimary,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
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
    // 左列"节次/时间"与课表格子共用同一个纵向滚动状态，保证上下滑动时一起动、不错位
    val vs = rememberScrollState()
    var selectedKey by remember { mutableStateOf<String?>(null) }
    // 翻周时清除选中高亮
    LaunchedEffect(pagerState.currentPage) { selectedKey = null }
    // 一周起点可设为周日：列顺序变化，日期按 周日=该周最后一天 重排
    // 外观设置只读一次，避免在每页/每次重绘里反复读 SharedPreferences
    val showOtherWeek = Repository.showOtherWeekCourses
    val startSunday = Repository.weekStartSunday
    val dayCols = if (startSunday) listOf(7, 1, 2, 3, 4, 5, 6) else (1..7).toList()
    val dateIdx: (Int) -> Int = if (startSunday)
        { i -> if (i == 0) 6 else i - 1 } else { i -> i }
    // 日期条对齐点：该周第一天在日期条上的 index（周日起点=该周周日，周一起点=该周周一）
    val weekStartIndex: (Int) -> Int = if (startSunday)
        { w -> 6 + (w - 1) * 7 } else { w -> 7 + (w - 1) * 7 }

    Column(Modifier.fillMaxSize()) {
        // 日期条：不可手动拖动（拖动太飘、停不住，已去掉），与课表"锁死"——
        // 课表横向滑到哪，日期条就同步跟到哪（7 天 = 1 周 = 1 页），全程对齐、不跳
        val headerState = rememberLazyListState(
            initialFirstVisibleItemIndex = weekStartIndex(currentWeek.coerceIn(1, 53)).coerceIn(0, 53 * 7 - 1)
        )
        // 日期条最左那天的"天序号"基准（周一=7、周日=6），把点选的日期换算成周次用
        val dayIndexBase = weekStartIndex(1)
        val stripStart = remember(schedule.termStartMonday) {
            try {
                LocalDate.parse(schedule.termStartMonday, DateTimeFormatter.ofPattern("yyyy-MM-dd")).minusDays(7)
            } catch (e: Exception) {
                null
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
            BoxWithConstraints(Modifier.weight(1f)) {
            // 每个日期格子的宽度 = 课表每列的宽度（都是"可用宽度 ÷ 7"）→ 日期和课程竖直方向严格对齐；
            // 切换 周一/周日 起点时两边一起左移/右移一列，始终对齐
            val cellW = maxWidth / 7f
            val cellPx = with(LocalDensity.current) { cellW.toPx() }.coerceAtLeast(1f)
            // 课表(pager)横向位置 → 日期条对应位置，逐帧连续锁死
            LaunchedEffect(pagerState, startSunday, cellPx) {
                val maxIdx = 53 * 7 - 1
                snapshotFlow { pagerState.currentPage + pagerState.currentPageOffsetFraction }.collect { p ->
                    val d = dayIndexBase + p * 7f
                    val idx = d.toInt().coerceIn(0, maxIdx)
                    val off = ((d - idx) * cellPx).toInt().coerceAtLeast(0)
                    try {
                        headerState.scrollToItem(idx, off)
                    } catch (e: Exception) {
                    }
                }
            }
            LazyRow(
                state = headerState,
                userScrollEnabled = false,
                modifier = Modifier.fillMaxWidth().testTag("dateStrip")
            ) {
            items(count = 53 * 7) { i ->
                val date = stripStart?.plusDays(i.toLong())
                // 当前显示的这一周 = 从 weekStartIndex(viewedWeek) 起的 7 天（与课表列顺序一致）
                val inViewed = i >= weekStartIndex(viewedWeek) && i < weekStartIndex(viewedWeek) + 7
                val isToday = date == today
                Column(
                    Modifier
                        .width(cellW)
                        .height(44.dp)
                        .background(if (inViewed) th.todayTint else Color.Transparent)
                        .clickable {
                            if (date != null) {
                                val ww = (((i - dayIndexBase) / 7) + 1).coerceIn(1, 53)
                                scope.launch { pagerState.animateScrollToPage(ww - 1) }
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
            // 左列：节次 + 上课时间（与课表格子共用纵向滚动状态，上下滑动时一起动）
            Column(Modifier.width(46.dp).verticalScroll(vs)) {
                // 当前正上的节次整列只算一次（原来每节都重新解析时间，是滑动卡顿的一处）
                val nowP = if (viewedWeek == currentWeek) nowPeriodIndex(schedule.campus) else -1
                val campusTimes = CampusTimes.times(schedule.campus)
                for (p in 1..11) {
                    val isNow = p == nowP
                    Box(Modifier.height(slotH).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "$p", fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Bold,
                                color = if (isNow) th.accent else th.textSecondary
                            )
                            val t = campusTimes[p]
                            Text(
                                t?.first ?: "", fontSize = 8.sp, lineHeight = 10.sp,
                                color = if (isNow) th.accent else th.textSecondary
                            )
                            Text(
                                t?.second ?: "", fontSize = 8.sp, lineHeight = 10.sp,
                                color = if (isNow) th.accent else th.textSecondary
                            )
                        }
                    }
                }
            }
            // 左右滑动切换周次，每页一周的 7 列课程
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).testTag("weekPager"),
                beyondViewportPageCount = 1
            ) { page ->
                val week = page + 1
                val showOther = showOtherWeek
                val inWeek = remember(schedule.courses, week) {
                    schedule.courses.filter { it.weeks.isEmpty() || week in it.weeks }
                }
                val otherWeek = remember(schedule.courses, week, showOther) {
                    if (showOther) schedule.courses
                        .filter { it.weeks.isNotEmpty() && week !in it.weeks && it.day != 0 }
                    else emptyList()
                }
                val byDay = inWeek.groupBy { it.day }
                val othersByDay = otherWeek.groupBy { it.day }
                val dates = weekDates(schedule.termStartMonday, week)
                Column(Modifier.fillMaxSize().verticalScroll(vs)) {
                    Box(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth()) {
                            for ((i, d) in dayCols.withIndex()) {
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .height(slotH * 11)
                                        .background(if (dates.getOrNull(dateIdx(i)) == today) th.todayTint else Color.Transparent)
                                ) {
                                    for (seg in remember(week, inWeek, otherWeek) {
                                        daySegments(byDay[d] ?: emptyList(), othersByDay[d] ?: emptyList())
                                    }) {
                                        val c = seg.course
                                        if (c != null) {
                                            val color = th.cardColor(c.name)
                                            val dimmed = seg.dim
                                            val bodyAlpha = if (dimmed) 0.55f else 1f
                                            Box(
                                                Modifier
                                                    .padding(horizontal = 1.dp, vertical = 2.dp)
                                                    .height(slotH * seg.span - 4.dp)
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(
                                                        if (dimmed) color.first.copy(alpha = 0.30f)
                                                        else color.first
                                                    )
                                                    .then(
                                                        if (!dimmed && selectedKey == "c:${c.id}")
                                                            Modifier.border(2.dp, th.accent, RoundedCornerShape(10.dp))
                                                        else Modifier
                                                    )
                                                    .pointerInput(c.id, dimmed) {
                                                        val key = "c:${c.id}"
                                                        detectTapGestures(
                                                            // 单击选中；选中后再次单击（或直接双击）= 打开详情
                                                            onTap = {
                                                                if (dimmed) onCourseClick(c)
                                                                else if (selectedKey == key) onCourseClick(c)
                                                                else selectedKey = key
                                                            },
                                                            onDoubleTap = { onCourseClick(c) }
                                                        )
                                                    }
                                            ) {
                                                Column(Modifier.padding(4.dp)) {
                                                    Text(
                                                        c.name, fontSize = 10.sp, lineHeight = 12.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = color.second.copy(alpha = bodyAlpha)
                                                    )
                                                    if (c.location.isNotEmpty()) {
                                                        // 教室完整显示、不截断——不点进详情也能看到
                                                        Text(
                                                            c.location, fontSize = 9.sp, lineHeight = 11.sp,
                                                            color = color.second.copy(alpha = 0.8f * bodyAlpha)
                                                        )
                                                    }
                                                    if (c.teacher.isNotEmpty()) {
                                                        Text(
                                                            c.teacher, fontSize = 9.sp, lineHeight = 11.sp,
                                                            color = color.second.copy(alpha = 0.75f * bodyAlpha),
                                                            maxLines = 1, overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }
                                                if (!c.note.isNullOrEmpty() && !dimmed) {
                                                    Text(
                                                        "📝", fontSize = 9.sp,
                                                        modifier = Modifier.align(Alignment.TopEnd).padding(3.dp)
                                                    )
                                                }
                                            }
                                        } else {
                                            // 空白格：单击选中、双击（或选中后再次单击）才添加课程
                                            val blankKey = "b:$d:${seg.start}"
                                            val blankSel = selectedKey == blankKey
                                            Box(
                                                Modifier
                                                    .padding(horizontal = 1.dp, vertical = 1.dp)
                                                    .height(slotH * seg.span - 2.dp)
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .then(if (blankSel) Modifier.border(2.dp, th.accent, RoundedCornerShape(8.dp)) else Modifier)
                                                    .testTag(blankKey)
                                                    .pointerInput(d, seg.start) {
                                                        detectTapGestures(
                                                            onTap = {
                                                                if (selectedKey == blankKey) onAddCourse(d, seg.start, week)
                                                                else selectedKey = blankKey
                                                            },
                                                            onDoubleTap = { onAddCourse(d, seg.start, week) }
                                                        )
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    "+", fontSize = 15.sp,
                                                    color = th.textSecondary.copy(alpha = if (blankSel) 0.75f else 0.25f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // 实习 / 实验 / 课程设计：统一放在第 11 节下方新开的一行（有就显示，没有就不显示）
                        // 一周起点为周日时周一在第 2 列 → 整块右移一列，始终与 周一~周五 对齐
                        val weekBottom = inWeek.filter { it.day == 0 }
                        if (weekBottom.isNotEmpty()) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(slotH)
                                    .padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.width(46.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        "安排", fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                        color = th.textSecondary
                                    )
                                }
                                Row(Modifier.weight(1f).fillMaxHeight()) {
                                    val bStart = if (startSunday) 1 else 0
                                    if (bStart > 0) Spacer(Modifier.weight(bStart.toFloat()))
                                    Row(
                                        Modifier.weight(5f).fillMaxHeight(),
                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        for (b in weekBottom) {
                                            val color = th.cardColor(b.name)
                                            Column(
                                                Modifier
                                                    .weight(1f)
                                                    .fillMaxHeight()
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(color.first)
                                                    .clickable { onCourseClick(b) }
                                                    .padding(horizontal = 5.dp, vertical = 4.dp)
                                            ) {
                                                Text(
                                                    b.name, fontSize = 9.5.sp, lineHeight = 11.sp,
                                                    fontWeight = FontWeight.SemiBold, color = color.second,
                                                    maxLines = 2, overflow = TextOverflow.Ellipsis
                                                )
                                                if (b.teacher.isNotEmpty()) {
                                                    Text(
                                                        b.teacher, fontSize = 8.5.sp, lineHeight = 10.sp,
                                                        color = color.second.copy(alpha = 0.75f),
                                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    val bEnd = 7 - 5 - bStart
                                    if (bEnd > 0) Spacer(Modifier.weight(bEnd.toFloat()))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 一天 11 节的布局段：课程块 + 可点击的空白块（重叠时裁剪保证不越界）；dim=非本周半透明课 */
private data class GridSeg(val course: Course?, val start: Int, val span: Int, val dim: Boolean = false)

private fun daySegments(list: List<Course>, sub: List<Course> = emptyList()): List<GridSeg> {
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
    if (sub.isEmpty()) return segs
    // 非本周课（半透明）：只填进本周课没占用的空段，不与本周课重叠
    val subs = sub.sortedBy { it.startPeriod }
    val filled = mutableListOf<GridSeg>()
    for (seg in segs) {
        if (seg.course == null) {
            val segEnd = seg.start + seg.span - 1
            val hit = subs.firstOrNull { it.endPeriod >= seg.start && it.startPeriod <= segEnd }
            if (hit != null) {
                val s = maxOf(hit.startPeriod, seg.start)
                val e = minOf(hit.endPeriod, segEnd)
                if (s <= e) {
                    if (s > seg.start) filled.add(GridSeg(null, seg.start, s - seg.start))
                    filled.add(GridSeg(hit, s, e - s + 1, dim = true))
                    if (e < segEnd) filled.add(GridSeg(null, e + 1, segEnd - e))
                    continue
                }
            }
        }
        filled.add(seg)
    }
    return filled
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

/** 把周次列表压成 "1-16,18" 这样的文本（编辑框用） */
fun weeksToText(weeks: List<Int>): String {
    if (weeks.isEmpty()) return ""
    val w = weeks.sorted()
    val ranges = mutableListOf<Pair<Int, Int>>()
    var s = w[0]; var prev = w[0]
    for (i in 1 until w.size) {
        if (w[i] == prev + 1) prev = w[i] else { ranges.add(s to prev); s = w[i]; prev = w[i] }
    }
    ranges.add(s to prev)
    return ranges.joinToString(",") { (a, b) -> if (a == b) "$a" else "$a-$b" }
}

/** 解析 "1-16,18" 这样的周次文本 → [1..16,18] */
fun parseWeeksText(text: String): List<Int> {
    val out = sortedSetOf<Int>()
    for (m in Regex("(\\d+)\\s*[-~—–]\\s*(\\d+)|(\\d+)").findAll(text)) {
        val g = m.groupValues
        if (g[1].isNotEmpty()) {
            var a = g[1].toIntOrNull() ?: continue
            var b = g[2].toIntOrNull() ?: continue
            if (a > b) { val t = a; a = b; b = t }
            for (i in a..b) if (i in 1..53) out.add(i)
        } else {
            g[3].toIntOrNull()?.let { if (it in 1..53) out.add(it) }
        }
    }
    return out.toList()
}

/** 编辑课程里的可视化选时段：7 列(星期) × 11 行(节次)，可点击、可从左上拖到右下框选 */
@Composable
fun PeriodPicker(
    day: Int, startP: Int, endP: Int, th: AppColors,
    onChange: (day: Int, startP: Int, endP: Int) -> Unit
) {
    // 当前选择（矩形）：列=星期(1..7)，行=节次(1..11)
    var aDay by remember(day, startP, endP) { mutableStateOf(day.coerceIn(1, 7)) }
    var aP by remember(day, startP, endP) { mutableStateOf(startP.coerceIn(1, 11)) }
    var bDay by remember(day, startP, endP) { mutableStateOf(day.coerceIn(1, 7)) }
    var bP by remember(day, startP, endP) { mutableStateOf(endP.coerceIn(1, 11)) }

    fun commit() { onChange(aDay, minOf(aP, bP), maxOf(aP, bP)) }

    val cellW = 30.dp; val cellH = 20.dp
    Column(Modifier.fillMaxWidth()) {
        Row {
            Box(Modifier.width(20.dp)) {}
            for (d in 1..7) Text(dayNameShort(d), fontSize = 10.sp, color = th.textSecondary,
                modifier = Modifier.width(cellW), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        Row {
            Column { for (p in 1..11) Text("$p", fontSize = 9.sp, color = th.textSecondary,
                modifier = Modifier.width(20.dp).height(cellH), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
            Box(
                Modifier.weight(1f).height(cellH * 11).clip(RoundedCornerShape(8.dp))
                    .background(th.divider.copy(alpha = 0.35f))
                    .pointerInput(Unit) {
                        val w = size.width / 7f; val h = size.height / 11f
                        fun cell(x: Float, y: Float): Pair<Int, Int> =
                            ((x / w).toInt().coerceIn(0, 6) + 1) to ((y / h).toInt().coerceIn(0, 10) + 1)
                        detectDragGestures(
                            onDragStart = { o -> val (dd, pp) = cell(o.x, o.y); aDay = dd; aP = pp; bDay = dd; bP = pp },
                            onDrag = { ch, _ -> val (dd, pp) = cell(ch.position.x, ch.position.y); bDay = dd; bP = pp; ch.consume() },
                            onDragEnd = { commit() }
                        )
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { o ->
                            val w = size.width / 7f; val h = size.height / 11f
                            val dd = ((o.x / w).toInt().coerceIn(0, 6) + 1)
                            val pp = ((o.y / h).toInt().coerceIn(0, 10) + 1)
                            aDay = dd; aP = pp; bDay = dd; bP = pp; commit()
                        }
                    }
            ) {
                // 选中矩形高亮
                val c0 = minOf(aDay, bDay); val c1 = maxOf(aDay, bDay)
                val r0 = minOf(aP, bP); val r1 = maxOf(aP, bP)
                Box(
                    Modifier
                        .offset(x = cellW * (c0 - 1), y = cellH * (r0 - 1))
                        .width(cellW * (c1 - c0 + 1)).height(cellH * (r1 - r0 + 1))
                        .background(th.accent.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("点选或拖拽选择 · ${dayName(minOf(aDay, bDay))} 第${minOf(aP, bP)}-${maxOf(aP, bP)}节",
            fontSize = 11.sp, color = th.textSecondary)
    }
}

fun dayNameShort(d: Int) = listOf("", "一", "二", "三", "四", "五", "六", "日")[d.coerceIn(1, 7)]

/** 编辑课程：改节次 / 周次，可选"整个学期"或"仅本周" */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditCourseDialog(
    course: Course,
    currentWeek: Int,
    th: AppColors,
    onDismiss: () -> Unit,
    onConfirm: (name: String, teacher: String, location: String, day: Int, startP: Int, endP: Int, weeks: List<Int>, onlyThisWeek: Boolean) -> Unit
) {
    var name by remember { mutableStateOf(course.name) }
    var teacher by remember { mutableStateOf(course.teacher) }
    var location by remember { mutableStateOf(course.location) }
    var day by remember { mutableStateOf(course.day) }
    var startP by remember { mutableStateOf(course.startPeriod) }
    var endP by remember { mutableStateOf(course.endPeriod) }
    var weeksText by remember { mutableStateOf(weeksToText(course.weeks)) }
    var onlyThisWeek by remember { mutableStateOf(false) }
    val valid = name.isNotBlank() && startP in 1..11 && endP in startP..11

    MacDialog(color = th.surface, onDismissRequest = onDismiss) { close ->
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("编辑课程", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = th.textPrimary)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(name, { name = it },
                label = { Text("课程名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(teacher, { teacher = it },
                    label = { Text("老师") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(location, { location = it },
                    label = { Text("教室") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            if (course.day != 0) {
                Text("上课时间（点选或拖拽框选）", fontSize = 12.sp, color = th.textSecondary)
                Spacer(Modifier.height(4.dp))
                PeriodPicker(day, startP, endP, th) { d, s, e -> day = d; startP = s; endP = e }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("第", fontSize = 13.sp, color = th.textSecondary)
                    OutlinedTextField(startP.toString(),
                        { s -> s.filter { it.isDigit() }.take(2).toIntOrNull()?.let { if (it in 1..11) startP = it } },
                        singleLine = true, modifier = Modifier.width(58.dp).padding(horizontal = 4.dp))
                    Text("节 到", fontSize = 13.sp, color = th.textSecondary)
                    OutlinedTextField(endP.toString(),
                        { s -> s.filter { it.isDigit() }.take(2).toIntOrNull()?.let { if (it in 1..11) endP = it } },
                        singleLine = true, modifier = Modifier.width(58.dp).padding(horizontal = 4.dp))
                    Text("节", fontSize = 13.sp, color = th.textSecondary)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(selected = !onlyThisWeek, onClick = { onlyThisWeek = false },
                    label = { Text("整个学期", fontSize = 12.sp, maxLines = 1) })
                FilterChip(selected = onlyThisWeek, onClick = { onlyThisWeek = true },
                    label = { Text("仅第 $currentWeek 周", fontSize = 12.sp, maxLines = 1) })
            }
            Spacer(Modifier.height(8.dp))
            if (!onlyThisWeek) {
                OutlinedTextField(
                    weeksText, { weeksText = it },
                    label = { Text("周次（如 1-16,18）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text("只改第 $currentWeek 周，其余周次不变。", fontSize = 11.sp, color = th.textSecondary)
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = close) { Text("取消", color = th.textSecondary) }
                Spacer(Modifier.width(4.dp))
                Button(enabled = valid, onClick = {
                    onConfirm(
                        name, teacher, location, day, startP, endP,
                        if (onlyThisWeek) listOf(currentWeek) else parseWeeksText(weeksText),
                        onlyThisWeek
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

/**
 * 日期条"天序号"(可含小数) → 课表 pager 的 (page, pageOffsetFraction)。
 * page 夹在 0..maxPage、frac 夹在 ±0.5 内，保证 PagerState.scrollToPage 不会因越界/非法偏移而抛异常。
 */
fun pagerPosFromDay(d: Float, dayIndexBase: Int, maxPage: Int): Pair<Int, Float> {
    val p = ((d - dayIndexBase) / 7f).coerceIn(0f, maxPage.toFloat())
    val page = Math.round(p).coerceIn(0, maxPage)
    val frac = (p - page).coerceIn(-0.49f, 0.49f)
    return page to frac
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

/** 当前时间正处在第几节（今天高亮左列用）；不在任何一节课内返回 -1。整列只算一次 */
fun nowPeriodIndex(campus: String): Int {
    val times = CampusTimes.times(campus)
    val now = try {
        LocalTime.now()
    } catch (e: Exception) {
        return -1
    }
    for (p in 1..11) {
        val t = times[p] ?: continue
        try {
            if (!now.isBefore(LocalTime.parse(t.first)) && now.isBefore(LocalTime.parse(t.second))) return p
        } catch (e: Exception) {
        }
    }
    return -1
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    schedule: Schedule,
    th: AppColors,
    onDone: () -> Unit,
    onShowTutorial: () -> Unit = {}
) {
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
    var checkUp by remember { mutableStateOf(Repository.checkUpdate) }
    var showOther by remember { mutableStateOf(Repository.showOtherWeekCourses) }

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
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(showOther, { showOther = it })
                    Text("显示非本周课程（半透明）", fontSize = 13.sp, color = th.textPrimary)
                }

                SectionTitle("更新", th.accent)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checkUp, { checkUp = it })
                    Text("打开时后台检查新版本（GitHub）", fontSize = 13.sp, color = th.textPrimary)
                }
                Spacer(Modifier.height(6.dp))
                // 手动检查：到 GitHub 搜索最新 Release，有新版直接给下载入口
                var checking by remember { mutableStateOf(false) }
                var checkResult by remember { mutableStateOf<List<String>?>(null) } // [版本, 链接] 或 ["LATEST"] 或 ["FAIL"]
                val scope = rememberCoroutineScope()
                OutlinedButton(
                    enabled = !checking,
                    onClick = {
                        checking = true
                        checkResult = null
                        scope.launch {
                            val remote = withContext(Dispatchers.IO) { MainActivity.fetchLatestRelease() }
                            checking = false
                            checkResult = when {
                                remote == null -> listOf("FAIL")
                                MainActivity.isNewerVersion(
                                    remote.first, MainActivity.currentVersionName(context)
                                ) -> listOf(remote.first, remote.second)
                                else -> listOf("LATEST")
                            }
                        }
                    }
                ) { Text(if (checking) "正在检查…" else "立即检查更新", fontSize = 13.sp) }
                checkResult?.let { r ->
                    Spacer(Modifier.height(4.dp))
                    when (r[0]) {
                        "LATEST" -> Text(
                            "✓ 已是最新版本 v${MainActivity.currentVersionName(context)}",
                            fontSize = 12.sp, color = th.textSecondary
                        )
                        "FAIL" -> Text(
                            "检查失败（网络连不上 GitHub，稍后再试）",
                            fontSize = 12.sp, color = th.textSecondary
                        )
                        else -> Text(
                            "⬇️ 发现新版本 v${r[0]}，点击下载安装包",
                            fontSize = 13.sp, fontWeight = FontWeight.Medium, color = th.accent,
                            modifier = Modifier
                                .clickable { openUrl(context, r[1]) }
                                .padding(vertical = 2.dp)
                        )
                    }
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

                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = th.divider, thickness = 1.dp)
                Spacer(Modifier.height(10.dp))
                Text(
                    "📖 使用教程",
                    fontSize = 13.sp, fontWeight = FontWeight.Medium, color = th.accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onShowTutorial() }
                        .padding(vertical = 4.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "GitHub：github.com/${MainActivity.GITHUB_USER}（作者）",
                    fontSize = 12.sp, color = th.textSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { openUrl(context, MainActivity.GITHUB_URL) }
                        .padding(vertical = 4.dp)
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "南林课程表 v${MainActivity.currentVersionName(context)} · 作者 ID：${MainActivity.GITHUB_USER}",
                    fontSize = 11.sp, color = th.textSecondary.copy(alpha = 0.8f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )

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
                    Repository.checkUpdate = checkUp
                    Repository.showOtherWeekCourses = showOther
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
        Text(label, fontSize = 11.sp, color = th.textSecondary, modifier = Modifier.width(38.dp))
        Text(
            value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = th.textPrimary,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 详情弹窗的节次行：同名课在本周的其他上课时间逐行列出，
 * 当前这节前面加圆点、加粗（如一节课周三周五都上 → 两行）。
 */
@Composable
fun PeriodRows(sel: Course, sameName: List<Course>, campus: String, th: AppColors) {
    val times = CampusTimes.times(campus)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text("⏰", fontSize = 13.sp)
        Spacer(Modifier.width(8.dp))
        Text(
            "节次", fontSize = 11.sp, color = th.textSecondary,
            modifier = Modifier.width(38.dp).padding(top = 2.dp)
        )
        Column(Modifier.weight(1f)) {
            sameName.forEach { x ->
                val t1 = x.startTime.ifEmpty { times[x.startPeriod]?.first ?: "" }
                val t2 = x.endTime.ifEmpty { times[x.endPeriod]?.second ?: "" }
                val span = if (x.startPeriod == x.endPeriod) "第${x.startPeriod}节"
                else "第${x.startPeriod}-${x.endPeriod}节"
                val isCurrent = x.id == sel.id && sameName.size > 1
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isCurrent) {
                        Box(
                            Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(th.accent)
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        "${dayName(x.day)} $span" +
                                if (t1.isNotEmpty() && t2.isNotEmpty()) " · $t1-$t2" else "",
                        fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                        color = th.textPrimary
                    )
                }
            }
        }
    }
}

/** 首次打开的使用教程：顶部 GitHub 链接，底部固定「不再显示」勾选 +「关闭」按钮 */
@Composable
fun TutorialDialog(th: AppColors, onDone: () -> Unit) {
    val context = LocalContext.current
    var dontShow by remember { mutableStateOf(false) }
    MacDialog(color = th.surface, onDismissRequest = {
        if (dontShow) Repository.showTutorial = false
        onDone()
    }) { close ->
        Column(Modifier.fillMaxWidth()) {
            Text("使用教程", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = th.textPrimary)
            Spacer(Modifier.height(2.dp))
            Text(
                "GitHub：github.com/${MainActivity.GITHUB_USER}",
                fontSize = 12.sp, fontWeight = FontWeight.Medium, color = th.accent,
                modifier = Modifier
                    .clickable { openUrl(context, MainActivity.GITHUB_URL) }
                    .padding(vertical = 2.dp)
            )
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                listOf(
                    "👆 单击课程格" to "选中高亮，看清这节课的时间地点。",
                    "👆👆 双击课程格" to "打开详情：查看完整信息、写备忘录（格子右上角会显示 📝）、填学分；同一门课一周上多次时会一并列出。",
                    "➕ 点空白格" to "手动添加课程，可选 每周 / 单周 / 双周 / 仅本周。",
                    "↔️ 左右滑动" to "切换上一周 / 下一周；顶部日期条也能拖动，点某一天直接跳到那一周。",
                    "🔝 顶栏按钮" to "从左到右：课表/列表视图切换、切换校区、手动更新课表、设置。",
                    "🎨 设置" to "4 套色系 + 自定义色相、深色模式、反色、一周起点、学期第一周日期；设置底部可随时重新打开本教程。",
                    "🔄 自动更新" to "在设置里保存账号密码后，打开 App 会自动同步教务最新课表；同步失败不影响已保存的课表。手动加的课会被教务数据覆盖。",
                    "⬇️ 检查更新" to "打开 App 时后台访问 GitHub 检查新版本（设置里可关闭，也可随时手动检查），发现新版点顶部横条或设置里的下载入口即可。"
                ).forEachIndexed { i, (title, desc) ->
                    if (i > 0) Spacer(Modifier.height(9.dp))
                    Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = th.textPrimary)
                    Text(
                        desc, fontSize = 12.sp, lineHeight = 17.sp,
                        color = th.textSecondary,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
            HorizontalDivider(color = th.divider, thickness = 1.dp)
            Spacer(Modifier.height(4.dp))
            // 底部固定行：不再显示 + 关闭（内容滚动，这行不动）
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { dontShow = !dontShow }
                ) {
                    Checkbox(checked = dontShow, onCheckedChange = { dontShow = it })
                    Text("不再显示", fontSize = 13.sp, color = th.textPrimary)
                }
                Button(onClick = close) { Text("关闭") }
            }
        }
    }
}

/** 用系统浏览器打开链接；没有浏览器等异常时静默忽略 */
fun openUrl(ctx: android.content.Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {}
}

/**
 * 学分抓取脚本：在已登录的教务页面里先取「个人中心」门户页的节次模式 GUID，
 * 再 fetch 个人中心课表（mainV_index_loadkb.htmlx，悬停课程卡片显示的浮层
 * 就是其中 div.item-box，内含「学分：X」），把整页 HTML 回传 AndroidGrab.receiveKb。
 * 手动导入（ImportActivity）与后台自动更新（MainActivity）共用。
 */
fun loadKbJs(term: String): String {
    val t = term.replace("\\", "\\\\").replace("'", "\\'")
    return """(function(){
function done(h){try{AndroidGrab.receiveKb(h||'')}catch(e){}}
function guid(cb){
 var x=new XMLHttpRequest();
 x.open('GET','/jsxsd/framework/xsMainV_new.htmlx?t1=1',true);
 x.timeout=8000;
 x.onload=function(){
  var t=x.responseText||'';
  var m=t.match(/class="layui-this"[^>]*data-value="([0-9A-Fa-f]{32})"/)||
        t.match(/data-value="([0-9A-Fa-f]{32})"[^>]*class="layui-this"/)||
        t.match(/data-value="([0-9A-Fa-f]{32})"/);
  cb(m?m[1]:'');
 };
 x.onerror=function(){cb('')};x.ontimeout=function(){cb('')};
 x.send();
}
guid(function(g){
 if(!g){done('');return;}
 var x=new XMLHttpRequest();
 var term='$t';
 var q='rq=all&sjmsValue='+g+(term?('&xnxqid='+encodeURIComponent(term)):'')+'&xswk=false';
 x.open('GET','/jsxsd/framework/mainV_index_loadkb.htmlx?'+q,true);
 x.timeout=10000;
 x.onload=function(){done(x.responseText||'')};
 x.onerror=function(){done('')};x.ontimeout=function(){done('')};
 x.send();
});
})();"""
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
