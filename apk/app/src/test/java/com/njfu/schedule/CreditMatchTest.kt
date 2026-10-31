package com.njfu.schedule

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 学分抓取/匹配回归（2026-10-31 用户报"每门课基本都该有学分"）：
 * 1) 同一格塞两门课时学分解析只取第一个课名 → 用当天活页 loadkb 快照回归（本机文件）；
 * 2) 课表课名与学分页课名的分组括号全角/半角不一致 → 匹配失败（体育/工程经济概论）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CreditMatchTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setup() {
        Repository.init(ctx)
        Repository.saveSchedule(Schedule("2026-2027-1", "2026-08-31", "xz", emptyList()))
    }

    @Test
    fun liveLoadkbParsesAllCourseCredits() {
        val html = javaClass.getResourceAsStream("/kb_loadkb_live.html")!!
            .readBytes().toString(Charsets.UTF_8)
        val credits = JwxtParser.parseCredits(html)
        // 13 门有学分的课（含同一 item-box 里塞两门课的第二门：动物与人类）
        assertEquals(13, credits.size)
        assertEquals("2", credits["动物与人类[科学精神类]"])
        assertEquals("2", credits["电气控制与PLC"])
        assertEquals("1", credits["工程经济管理概论[社会认知类]（分组01）"])
    }

    @Test
    fun creditMatchingToleratesBracketStyle() {
        // 课表侧课名用的是半角括号/多空格，学分页是全角括号
        Repository.saveSchedule(
            Schedule(
                "2026-2027-1", "2026-08-31", "xz",
                listOf(
                    Course("c1", "工程经济管理概论[社会认知类] (分组01)", "", "", 6, 1, 4, (6..10).toList()),
                    Course("c2", "体育(5) (舞龙)", "", "", 5, 7, 8, (1..16).toList())
                )
            )
        )
        val n = Repository.applyCredits(
            mapOf(
                "工程经济管理概论[社会认知类]（分组01）" to "1",
                "体育(5)（舞龙）" to "0.5"
            )
        )
        assertEquals("两门都要匹配上", 2, n)
        val courses = Repository.loadSchedule().courses
        assertEquals("1", courses.first { it.name.startsWith("工程经济") }.credit)
        assertEquals("0.5", courses.first { it.name.startsWith("体育") }.credit)
    }
}
