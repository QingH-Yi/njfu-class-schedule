package com.njfu.schedule

import java.time.format.DateTimeFormatter

/**
 * 各 Activity / 小组件共用的纯工具：JS 转义、课表签名、星期名、周次格式化。
 * 原先这些在两个 Activity 里各写了一份，容易改一处漏一处，统一到这里。
 */

/** 学期起始日等日期统一用这个格式（顶层常量，避免每次解析都重新编译 formatter） */
val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/** 把字符串安全地嵌入页面 JS 的单引号字面量（登录脚本、抓取脚本共用） */
fun jsStr(s: String): String = s
    .replace("\\", "\\\\").replace("'", "\\'")
    .replace("\n", "\\n").replace("\r", "")

/** 课表规范化签名：三轮结果签名一致才算没抓错 */
fun tableSignature(r: ParseResult): String = buildString {
    append(r.term)
    r.courses.sortedWith(compareBy({ it.day }, { it.startPeriod }, { it.name })).forEach {
        append("|${it.name}#${it.day}#${it.startPeriod}#${it.endPeriod}#${it.teacher}#${it.location}#${it.weeks.joinToString(",")}")
    }
}

// 星期名表（下标 1..7 = 周一..周日，下标 0 占位）
private val DAY_FULL = listOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")
/** 日期条用的单字星期名（下标 0..6 = 周一..周日） */
val DAY_SHORT = listOf("一", "二", "三", "四", "五", "六", "日")

/** "周一"（越界/0 一律夹到周一，与旧行为一致） */
fun dayName(d: Int): String = DAY_FULL[d.coerceIn(1, 7)]

/** 日期条用的"一"（单字）；输入 1..7 = 周一..周日 */
fun dayNameShort(d: Int): String = DAY_SHORT[d.coerceIn(1, 7) - 1]

/**
 * 把周次列表压缩成 "1-3,9-10" 这样的文本。
 * suffix="周" 时得到 "1-3,9-10周"（展示用）；空列表时用 empty 兜底。
 */
fun formatWeeks(weeks: List<Int>, suffix: String = "", empty: String = ""): String {
    if (weeks.isEmpty()) return empty
    val w = weeks.sorted()
    val ranges = mutableListOf<Pair<Int, Int>>()
    var s = w[0]; var prev = w[0]
    for (i in 1 until w.size) {
        if (w[i] == prev + 1) prev = w[i] else { ranges.add(s to prev); s = w[i]; prev = w[i] }
    }
    ranges.add(s to prev)
    return ranges.joinToString(",") { (a, b) -> if (a == b) "$a" else "$a-$b" } + suffix
}

/** 展示用："1-3,9-10周"；空=全学期 */
fun weekLabel(weeks: List<Int>): String = formatWeeks(weeks, suffix = "周", empty = "全学期")

/** 编辑框用："1-16,18"；空="" */
fun weeksToText(weeks: List<Int>): String = formatWeeks(weeks)

/** 周次文本里的数字/区间（预编译，解析器和 CSV 导入共用热点） */
private val WEEK_RANGE_RE = Regex("(\\d+)\\s*[-~—–]\\s*(\\d+)|(\\d+)")

/**
 * 周次文本解析（唯一入口，编辑弹窗与教务解析器共用）：
 * "1-16,18" / "3-8,10-13" / "单周" "双周" 修饰都支持；中文顿号"、"分隔与 "x-x单/x-x双"
 * （WakeUp CSV 格式）同样兼容；1..53 之外的数字丢弃。
 */
fun parseWeekNumbers(text: String, oddOnly: Boolean = false, evenOnly: Boolean = false): List<Int> {
    val out = sortedSetOf<Int>()
    for (m in WEEK_RANGE_RE.findAll(text)) {
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
    var list = out.toList()
    if (oddOnly) list = list.filter { it % 2 == 1 }
    if (evenOnly) list = list.filter { it % 2 == 0 }
    return list
}

/**
 * 统一身份认证（CAS）自动登录填表 JS：MainActivity 后台更新与 ImportActivity 抓取共用。
 * 在登录输入框为空时才填写（避免覆盖用户正在输入的内容），找到提交按钮就点并返回 'submitted'。
 * 统一认证手机版模板会常驻渲染验证码输入框（未必真的要求填写），所以不因它存在而跳过；
 * 若真需要验证码，提交失败无害，用户手动接管。
 */
fun autoLoginJs(user: String, pass: String): String =
    "(function(){try{" +
            "var u=document.querySelector('input[name=username],input#username,input[name=user],input[name=yh],input[name=zh]');" +
            "var pw=document.querySelector('input[type=password]');" +
            "if(!u||!pw)return 'nofield';" +
            "if(u.value&&pw.value)return 'filled';" +
            "u.value='${jsStr(user)}';pw.value='${jsStr(pass)}';" +
            "var b=document.querySelector('button[type=submit],input[type=submit],#loginBtn,.loginBtn');" +
            "if(b){b.click();return 'submitted';}return 'nofield';" +
            "}catch(e){return 'err';}})();"

/**
 * 三轮抓取收集器：课表/学分抓 3 轮、一致才采纳（防抓错/抓一半）。
 * 收满 count 轮时 offer 返回 true；线程安全（回调跑在 WebView 的 JavaBridge 后台线程）。
 */
class RoundCollector<T>(private val count: Int = 3) {
    private val items = ArrayList<T>(count)
    @Synchronized fun offer(item: T): Boolean {
        if (items.size >= count) return false
        items.add(item)
        return items.size == count
    }
    @Synchronized fun reset() = items.clear()
    @get:Synchronized val all: List<T> get() = items.toList()
    @get:Synchronized val size: Int get() = items.size
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
