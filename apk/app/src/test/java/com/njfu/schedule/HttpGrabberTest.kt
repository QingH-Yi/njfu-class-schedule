package com.njfu.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HttpGrabber 的纯函数部分：sudy RSA（与网页端登录 JS 逐字节对照过的基准值）、
 * 页面分类、CookieJar。CAS 网络链路无法在单测里跑，由真机端到端验证。
 */
class HttpGrabberTest {

    /** 基准值来自真实 CAS 页面 JS（security.js + login.js）在 Node 里的加密结果 */
    @Test
    fun sudyRsaMatchesBrowserJs() {
        val expected = "22aa1347eab18d9124291823e2c082ebd1fdac745215173f9659e6f498859f32" +
                "122a5a859d348e3cdae7e16e557406e9dda3f9a4d3e958773a6f2b5e0e4c7a6d8ae083e9cef" +
                "29a439fc8334207c1a2573d694d9d576790f7cf0b27894a1757a178885a57d7d87b09ad58e" +
                "96f69050cd7a4f7035553774942f81180bcdd8ce7c3"
        assertEquals(expected, HttpGrabber.sudyRsa("@Yqh050826"))
    }

    @Test
    fun sudyRsaShortPassword() {
        // 1 字符密码：块填充后同样 126 字节、输出 64 字
        val r = HttpGrabber.sudyRsa("a")
        assertTrue("长度应=64 字", r.split(" ").size == 1 && r.replace(" ", "").length == 256)
    }

    @Test
    fun classifyPages() {
        // 标准课表页
        assertEquals(HttpGrabber.PageKind.COURSE_TABLE, HttpGrabber.classify("<div class=\"kbcontent1\">x</div>".repeat(5)))
        // 门户/个人中心课表
        assertEquals(HttpGrabber.PageKind.LOADKB_TABLE, HttpGrabber.classify(
            "<span class=\"box\"><p>x</p></span><div class=\"item-box\"></div>".repeat(4) + "<table>星期一</table>"))
        // CAS 登录页
        assertEquals(HttpGrabber.PageKind.CAS_LOGIN, HttpGrabber.classify("<input id=\"passbutton\" type=\"submit\">"))
        // 入口选择页
        assertEquals(HttpGrabber.PageKind.ENTRY_CHOICE, HttpGrabber.classify("<a>进入选课</a><a>进入首页</a>"))
        // 通知 JSON（选课期拦截页）
        assertEquals(HttpGrabber.PageKind.NOTICE_JSON, HttpGrabber.classify("{\"flag1\":2,\"msgContent\":\"...\"}"))
        // 空响应
        assertEquals(HttpGrabber.PageKind.EMPTY, HttpGrabber.classify(""))
    }

    @Test
    fun cookieJarRoundtrip() {
        val jar = HttpGrabber.CookieJar()
        jar.store("jwxt.njfu.edu.cn", listOf("bzb_jsxsd=ABC123; Path=/jsxsd; HttpOnly", "SERVERID=pc2; path=/"))
        jar.store("uia.njfu.edu.cn", listOf("CASTGC=TGT-xxx; Path=/authserver; HttpOnly"))
        val h = jar.headerFor("https://jwxt.njfu.edu.cn/jsxsd/xskb/xskb_list.do")
        assertTrue("应带 jwxt cookie", h!!.contains("bzb_jsxsd=ABC123"))
        assertTrue("不应带 uia cookie", !h.contains("CASTGC"))
        assertTrue(jar.hasJwxtSession())
        // 持久化往返
        val snap = jar.snapshot()
        val jar2 = HttpGrabber.CookieJar()
        jar2.restore(snap)
        assertTrue(jar2.hasJwxtSession())
        val h2 = jar2.headerFor("https://jwxt.njfu.edu.cn/jsxsd/xskb/xskb_list.do") ?: ""
        assertTrue("往返后应带 bzb_jsxsd=ABC123", h2.contains("bzb_jsxsd=ABC123"))
    }
}
