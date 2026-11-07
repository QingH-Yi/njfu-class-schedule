package com.njfu.schedule

import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset

/**
 * 纯 HTTP 抓取器：跳过 WebView 渲染，直接用 HTTP 协议完成
 * CAS 登录（sudy RSA 加密）→ 课表页抓取 → 学分抓取 → 本地解析。
 * 全链路 2~3 秒（WebView 路径实测约 30 秒）。
 *
 * 流程状态机（按教务系统的实际行为动态判断，不做硬性假设——选课期的
 * 二次登录页有的会话会遇到、有的不会）：
 *  1. 带已存 Cookie 直取课表页 → 命中 kbcontent 直接解析（最快，0.2s）
 *  2. 被重定向到 CAS：
 *     - 有 CASTGC（SSO 会话还在）→ 原生跳转即得 ticket，无需密码
 *     - 无 → 表单登录（RSA 加密密码），页面出现验证码/密码错误则失败
 *  3. 落到"入口选择"页（选课期特有）→ 访问门户主页面建立会话
 *  4. 标准课表页可达 → 直接解析；返回通知 JSON/空 → 走门户内嵌课表（loadkb 格式）
 *  5. 任一步失败 → 抛 GrabException，调用方回退到 WebView 手动路径
 *
 * 教务系统结构备注（2026-11 逆向）：
 *  - CAS: uia.njfu.edu.cn/authserver/login，表单字段 username/password(RSA)/encrypted=true/loginType=1/execution/_eventId=submit
 *  - sudy RSA: chunkSize=2*biHighIndex(m)，逐 16 位小端字取块，biToHex 输出到最高非零字
 *  - jwxt 会话 cookie: bzb_jsxsd + SERVERID（WAF rump/c 签发）
 *  - 标准课表: /jsxsd/xskb/xskb_list.do（.kbcontent 格子，font title 标注）
 *  - 门户课表: /jsxsd/framework/xsMainV_new.htmlx?t1=1（span.box + div.item-box，含 GUID 与学分）
 *  - 学分页: /jsxsd/framework/mainV_index_loadkb.htmlx?rq=all&sjmsValue=<GUID>&xnxqid=<学期>&xswk=false
 */
object HttpGrabber {

    private const val HOST = "https://jwxt.njfu.edu.cn"
    private const val CAS_URL = "https://uia.njfu.edu.cn/authserver/login"
    private val RSA_MODULUS =
        "008aed7e057fe8f14c73550b0e6467b023616ddc8fa91846d2613cdb7f7621e3cada4cd5d812d627af6b87727ade4e26d26208b7326815941492b2204c3167ab2d53df1e3a2c9153bdb7c8c2e968df97a5e7e01cc410f92c4c2c2fba529b3ee988ebc1fca99ff5119e036d732c368acf8beba01aa2fdafa45b21e4de4928d0d403"
    private const val PAGE_TIMEOUT = 10000
    private const val LOGIN_TIMEOUT = 15000
    private const val MAX_REDIRECT = 6

    class GrabException(message: String) : Exception(message)

    /** Cookie 存储：domain → (name → value) */
    class CookieJar {
        private val jar = LinkedHashMap<String, LinkedHashMap<String, String>>()

        fun store(domain: String, setCookieHeaders: List<String>) {
            val bag = jar.getOrPut(domain) { LinkedHashMap() }
            for (head in setCookieHeaders) {
                for (part in head.split(Regex(",(?=[^;]+?=)"))) {
                    val first = part.trim().split(";").firstOrNull() ?: continue
                    val eq = first.indexOf('=')
                    if (eq <= 0) continue
                    val name = first.substring(0, eq).trim()
                    val value = first.substring(eq + 1).trim()
                    if (name.isNotEmpty() && name.lowercase() !in
                        listOf("path", "domain", "expires", "secure", "httponly", "samesite", "max-age")) {
                        bag[name] = value
                    }
                }
            }
        }

        fun headerFor(url: String): String? {
            val host = url.substringAfter("//").substringBefore('/')
            val relevant = LinkedHashMap<String, String>()
            for ((domain, bag) in jar) {
                if (host == domain || host.endsWith(".$domain")) bag.forEach { (n, v) -> relevant[n] = v }
            }
            return relevant.entries.joinToString("; ") { "${it.key}=${it.value}" }.ifEmpty { null }
        }

        fun snapshot(): String = jar.entries.joinToString("\n") { (d, bag) ->
            d + "\t" + bag.entries.joinToString("\t") { "${it.key}=${it.value}" }
        }

        fun restore(snapshot: String) {
            for (line in snapshot.split("\n")) {
                val parts = line.split("\t")
                if (parts.size < 2) continue
                val bag = jar.getOrPut(parts[0]) { LinkedHashMap() }
                for (kv in parts.drop(1)) {
                    val eq = kv.indexOf('=')
                    if (eq > 0) bag[kv.substring(0, eq)] = kv.substring(eq + 1)
                }
            }
        }

        fun hasJwxtSession(): Boolean =
            jar["jwxt.njfu.edu.cn"]?.get("bzb_jsxsd")?.isNotEmpty() == true
    }

    fun cookieSnapshot(): String = jar.snapshot()

    /** 抓取结果 */
    class Result(
        val tableHtml: String,      // 课表页 HTML（标准页或门户页，JwxtParser 两种都认）
        val creditHtml: String?,    // 学分页 HTML（含 item-box 学分），可能为 null
        val fromStandard: Boolean   // 是否标准课表页（xskb_list）
    )

    // ------------------------------------------------------------------

    private var jar = CookieJar()

    /** 页面特征分类 */
    internal enum class PageKind { COURSE_TABLE, LOADKB_TABLE, CAS_LOGIN, ENTRY_CHOICE, NOTICE_JSON, EMPTY, OTHER }

    private fun occurrences(s: String, needle: String): Int = s.split(needle).size - 1

    internal fun classify(body: String): PageKind {
        if (body.isEmpty()) return PageKind.EMPTY
        if (body.contains("id=\"passbutton\"") || body.contains("name=\"execution\"") ||
            body.contains("统一身份认证平台")) return PageKind.CAS_LOGIN
        if (body.contains("进入选课") && body.contains("进入首页")) return PageKind.ENTRY_CHOICE
        if (occurrences(body, "kbcontent") > 3) return PageKind.COURSE_TABLE
        if (occurrences(body, "item-box") > 3 && body.contains("星期")) return PageKind.LOADKB_TABLE
        if (body.contains("\"flag1\"")) return PageKind.NOTICE_JSON
        return PageKind.OTHER
    }

    /**
     * 执行完整抓取（后台线程调用）。
     * @param persistedCookies 上次会话的 Cookie（可为空；有时能 0.2s 直接命中）
     * @param onStatus 状态回调（后台线程）
     */
    fun grab(
        user: String, pass: String, term: String,
        persistedCookies: String?, onStatus: (String) -> Unit
    ): Result {
        jar = CookieJar()
        if (!persistedCookies.isNullOrEmpty()) jar.restore(persistedCookies)

        // ---- 第 1 步：带已有 Cookie 直取课表页（最快路径）----
        onStatus("正在连接教务系统…")
        var body = fetchBody("$HOST/jsxsd/xskb/xskb_list.do")
        var kind = classify(body)
        if (kind == PageKind.COURSE_TABLE) {
            onStatus("已登录，正在读取课表…")
            return finishStandard(body, term, onStatus)
        }

        // ---- 第 2 步：CAS 登录（SSO 或密码，动态判断）----
        onStatus("正在登录统一身份认证…")
        val service = URLEncoder.encode("$HOST/jsxsd/framework/xsMainV.jsp", "UTF-8")
        body = fetchBody("$CAS_URL?service=$service")
        kind = classify(body)
        if (kind == PageKind.CAS_LOGIN) {
            // 需要 login 表单（SSO 失效或没有）→ 密码登录
            if (user.isEmpty() || pass.isEmpty()) throw GrabException("需要登录")
            if (body.contains("请输入验证码") || body.contains("captchaError")) throw GrabException("需要验证码")
            val execution = Regex("name=\"execution\" value=\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                ?: run {
                    android.util.Log.d("HttpGrabber", "CAS无execution, body前300: " + body.take(300) + " body后300: " + body.takeLast(300))
                    throw GrabException("登录页解析失败")
                }
            val form = "username=${urlEnc(user)}&password=${urlEnc(sudyRsa(pass))}" +
                    "&encrypted=true&loginType=1&execution=${urlEnc(execution)}&_eventId=submit"
            body = fetchPost("$CAS_URL?service=$service", form)
            if (body.contains("账号或密码错误")) throw GrabException("账号或密码错误")
            if (body.contains("请输入验证码") || body.contains("captchaError")) throw GrabException("需要验证码")
            onStatus("登录成功，正在进入教务系统…")
        }
        // CAS 之后可能落在 jwxt 的任何页面（门户壳/入口选择/课表）——逐态处理
        kind = classify(body)

        // ---- 第 3 步：入口选择页（选课期特有，动态判断）----
        if (kind == PageKind.ENTRY_CHOICE || body.contains("进入首页")) {
            onStatus("正在进入教务系统…")
            body = fetchBody("$HOST/jsxsd/framework/xsMainV.jsp")
            kind = classify(body)
        }

        // ---- 第 4 步：拿课表（标准页优先；被拦则走门户内嵌课表）----
        body = fetchBody("$HOST/jsxsd/xskb/xskb_list.do")
        kind = classify(body)
        if (kind == PageKind.COURSE_TABLE) {
            onStatus("正在读取课表…")
            return finishStandard(body, term, onStatus)
        }
        onStatus("正在从门户读取课表…")
        body = fetchBody("$HOST/jsxsd/framework/xsMainV_new.htmlx?t1=1")
        kind = classify(body)
        if (kind == PageKind.LOADKB_TABLE) return Result(body, body, false)
        // 最后一搏：独立 loadkb 页（需要 GUID）
        val guid = Regex("data-value=\"([0-9A-Fa-f]{32})\"").find(body)?.groupValues?.get(1)
        if (guid != null) {
            val q = if (term.isEmpty()) "rq=all&sjmsValue=$guid&xswk=false"
                    else "rq=all&sjmsValue=$guid&xnxqid=${urlEnc(term)}&xswk=false"
            body = fetchBody("$HOST/jsxsd/framework/mainV_index_loadkb.htmlx?$q")
            if (classify(body) == PageKind.LOADKB_TABLE) return Result(body, body, false)
        }
        throw GrabException("教务系统未返回课表（可能处于选课期）")
    }

    /** 标准课表页成功后，顺带抓学分页（xsMainV_new 含 item-box 学分） */
    private fun finishStandard(tableHtml: String, term: String, onStatus: (String) -> Unit): Result {
        onStatus("正在爬学分…")
        val credit = try {
            val h = fetchBody("$HOST/jsxsd/framework/xsMainV_new.htmlx?t1=1")
            h.takeIf { occurrences(it, "item-box") > 3 }
        } catch (e: Exception) { null }
        return Result(tableHtml, credit, true)
    }

    // ---------------------- HTTP 基础 ----------------------

    private fun fetchBody(url: String): String = fetch(url, 0)

    private fun fetch(url: String, depth: Int): String {
        if (depth > MAX_REDIRECT) throw GrabException("重定向过多")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = PAGE_TIMEOUT
        conn.readTimeout = PAGE_TIMEOUT
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("User-Agent", MainActivity.DESKTOP_UA)
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
        jar.headerFor(url)?.let { conn.setRequestProperty("Cookie", it) }
        val status = conn.responseCode
        val setCookies = conn.headerFields?.filterKeys { it.equals("Set-Cookie", true) }?.values ?: emptyList()
        val domain = if (url.contains("uia.njfu.edu.cn")) "uia.njfu.edu.cn" else "jwxt.njfu.edu.cn"
        if (setCookies.isNotEmpty()) jar.store(domain, setCookies.flatten())
        val body = readBody(conn)
        // 重定向：手动跟随并携带 Cookie（HttpURLConnection 默认不跨协议跟随）
        if (status in listOf(301, 302, 303, 307)) {
            conn.getHeaderField("Location")?.let { loc ->
                val next = if (loc.startsWith("/")) originOf(url) + loc else loc
                if (next.startsWith("http") && next != url) {
                    conn.disconnect()
                    return fetch(next, depth + 1)
                }
            }
        }
        conn.disconnect()
        return body
    }

    private fun fetchPost(url: String, form: String): String = fetchPost(url, form, 0)

    private fun fetchPost(url: String, form: String, depth: Int): String {
        if (depth > MAX_REDIRECT) throw GrabException("重定向过多")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = LOGIN_TIMEOUT
        conn.readTimeout = LOGIN_TIMEOUT
        conn.instanceFollowRedirects = false
        conn.doOutput = true
        conn.setRequestProperty("User-Agent", MainActivity.DESKTOP_UA)
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        conn.setRequestProperty("Origin", "https://uia.njfu.edu.cn")
        conn.setRequestProperty("Referer", url)
        jar.headerFor(url)?.let { conn.setRequestProperty("Cookie", it) }
        conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
        val status = conn.responseCode
        val setCookies = conn.headerFields?.filterKeys { it.equals("Set-Cookie", true) }?.values ?: emptyList()
        if (setCookies.isNotEmpty()) jar.store("uia.njfu.edu.cn", setCookies.flatten())
        val body = readBody(conn)
        if (status in listOf(301, 302, 303, 307)) {
            conn.getHeaderField("Location")?.let { loc ->
                val next = if (loc.startsWith("/")) originOf(url) + loc else loc
                if (next.startsWith("http") && next != url) {
                    conn.disconnect()
                    return fetch(next, depth + 1)   // 登录后跳 jwxt 域，改用 GET
                }
            }
        }
        conn.disconnect()
        return body
    }

    private fun originOf(url: String): String {
        val after = url.substringAfter("//")
        val scheme = url.substringBefore("://")
        return "$scheme://" + after.substringBefore('/')
    }

    private fun readBody(conn: HttpURLConnection): String {
        val charset = if ((conn.contentType ?: "").contains("gb", true))
            Charset.forName("GBK") else Charsets.UTF_8
        return try {
            conn.inputStream?.readBytes()?.toString(charset) ?: ""
        } catch (e: Exception) {
            try { conn.errorStream?.readBytes()?.toString(charset) ?: "" } catch (_: Exception) { "" }
        }
    }

    private fun urlEnc(s: String): String = URLEncoder.encode(s, "UTF-8")

    // ---------------------- sudy RSA（与网页端登录 JS 逐字节一致）----------------------

    fun sudyRsa(password: String): String {
        val n = BigInteger(RSA_MODULUS, 16)
        val e = BigInteger("10001", 16)
        val digitCount = n.bitLength() / 16          // 1024 位 → 64 个 16 位字
        val chunk = 2 * (digitCount - 1)             // chunkSize = 2 * biHighIndex(m) = 126 字节
        val bytes = password.toByteArray(Charsets.US_ASCII)
        val padded = bytes.copyOf((bytes.size + chunk - 1) / chunk * chunk)
        val out = ArrayList<String>()
        var i = 0
        while (i < padded.size) {
            var block = BigInteger.ZERO
            for (j in 0 until chunk / 2) {
                val lo = padded[i + 2 * j].toInt() and 0xFF
                val hi = padded[i + 2 * j + 1].toInt() and 0xFF
                block = block.or(BigInteger.valueOf(((hi shl 8) or lo).toLong()).shiftLeft(16 * j))
            }
            val c = block.modPow(e, n)
            // biToHex：从最高非零字往下每个字 4 个 hex 字符
            var hex = ""
            for (k in digitCount - 1 downTo 0) {
                val d = c.shiftRight(16 * k).and(BigInteger.valueOf(0xFFFF)).toInt()
                hex += String.format("%04x", d)
            }
            out.add(hex.trimStart('0').ifEmpty { "0" })
            i += chunk
        }
        return out.joinToString(" ")
    }
}
