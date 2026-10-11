package com.njfu.schedule

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat

/**
 * 内置简易浏览器：右上角固定一个「抓取课表」按钮。
 * 支持自动填账号登录；抓到页面后交给 JwxtParser 解析并保存，返回主界面。
 * 界面配色跟随主界面的主题设置（色系/深色/反色）。
 */
class ImportActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var status: TextView
    private lateinit var th: AppColors

    private val DEFAULT_URL = "https://jwxt.njfu.edu.cn/jsxsd/xskb/xskb_list.do"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repository.init(this)
        th = currentAppColors(this)

        window.statusBarColor = th.surface.toArgb()
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = !th.dark

        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }

        val root = FrameLayout(this)
        root.setBackgroundColor(th.bg.toArgb())

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            // 桌面 UA：统一认证/教务给手机 UA 返回另一套模板，自动登录只适配桌面版
            settings.userAgentString = MainActivity.DESKTOP_UA
            addJavascriptInterface(GrabInterface(), "AndroidGrab")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    CookieManager.getInstance().flush() // 持久化登录会话，供下次自动更新
                    status.text = if (url?.contains("xskb_list") == true)
                        "已加载课表页，点右上角「抓取课表」即可导入。"
                    else
                        "若在登录页请完成登录，进入「学期理论课表」后点右上角抓取。"
                    // 自动填账号登录：验证码检查是异步的，分批多试几次
                    for (d in longArrayOf(300, 3000, 8000)) {
                        webView.postDelayed({ tryAutoLogin() }, d)
                    }
                }
            }
        }

        // 右上角圆角胶囊抓取按钮
        val grabBtn = Button(this).apply {
            text = "⬇ 抓取课表"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply {
                setColor(th.accent.toArgb())
                cornerRadius = dp(24).toFloat()
            }
            setPadding(dp(18), dp(10), dp(18), dp(10))
            stateListAnimator = null
            setOnClickListener { doGrab() }
        }
        val btnLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.END; setMargins(0, dp(30), dp(14), 0) }

        // 底部圆角状态条
        status = TextView(this).apply {
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                setColor(if (th.dark) 0xF038312B.toInt() else 0xF2FFFFFF.toInt())
                cornerRadius = dp(18).toFloat()
            }
            textSize = 12f
            setTextColor(if (th.dark) 0xFFEFE6DB.toInt() else 0xFF6B584C.toInt())
            text = "正在打开教务系统…"
        }
        val statusLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            setMargins(dp(14), 0, dp(14), dp(18))
        }

        root.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(status, statusLp)
        root.addView(grabBtn, btnLp)
        setContentView(root)

        webView.loadUrl(Repository.jwUrl.ifEmpty { DEFAULT_URL })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    private fun jsStr(s: String) = s
        .replace("\\", "\\\\").replace("'", "\\'")
        .replace("\n", "\\n").replace("\r", "")

    private fun tryAutoLogin() {
        val u = Repository.user
        val p = Repository.pass
        if (u.isEmpty() || p.isEmpty() || !Repository.savedPass) return
        // 统一认证手机版模板会常驻渲染验证码输入框（未必真的要求填写），
        // 所以不因它存在而跳过；若真需要验证码，提交失败无害，用户手动接管即可。
        val js = "(function(){try{" +
                "var u=document.querySelector('input[name=username],input#username,input[name=user],input[name=yh],input[name=zh]');" +
                "var pw=document.querySelector('input[type=password]');" +
                "if(!u||!pw){return 'nofield';}" +
                "if(u.value&&pw.value){return 'filled';}" +
                "u.value='" + jsStr(u) + "';pw.value='" + jsStr(p) + "';" +
                "var b=document.querySelector('button[type=submit],input[type=submit],#loginBtn,.loginBtn');" +
                "if(b){b.click();return 'submitted';}return 'nofield';" +
                "}catch(e){return 'err';}})();"
        webView.evaluateJavascript(js) { r ->
            if (r.trim('"') == "submitted") {
                status.text = "已自动填写账号并尝试登录。若页面有验证码或登录失败，请手动完成登录后点右上角抓取。"
            }
        }
    }

    private fun doGrab() {
        status.text = "正在抓取当前页面…"
        webView.evaluateJavascript(MainActivity.GRAB_JS, null)
    }

    inner class GrabInterface {
        @JavascriptInterface
        fun receive(html: String) {
            runOnUiThread {
                val result = JwxtParser.parseHtml(html)
                if (result == null || result.courses.isEmpty()) {
                    status.text = "没解析到课表。请先进「学期理论课表」选好学期，再抓取。"
                    Toast.makeText(this@ImportActivity, "未识别到课表", Toast.LENGTH_SHORT).show()
                } else {
                    val added = Repository.addCourses(result.courses, result.term)
                    Toast.makeText(this@ImportActivity, "导入成功：新增 $added 门课", Toast.LENGTH_LONG).show()
                    setResult(RESULT_OK)
                    finish()
                }
            }
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
