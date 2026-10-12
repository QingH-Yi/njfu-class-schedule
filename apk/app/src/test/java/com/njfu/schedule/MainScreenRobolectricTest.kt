package com.njfu.schedule

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric + Compose：在 JVM 上跑界面（不需要模拟器 / KVM）。
 *
 * 目的：挡住"一滑就闪退"这类回归——拖动日期条、翻周、打开详情都不能崩，
 * 且滑完之后课表仍然正常显示。
 * 注意：只验证"能不能跑通 / 崩不崩"，像素级样式（好不好看、卡不卡）测不了。
 * 又：课表 pager 会同时组合相邻 3 页（beyondViewportPageCount=1），
 * 同一门课跨周出现时会有多个同名节点，所以断言用"至少存在一个"。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainScreenRobolectricTest {

    @get:Rule
    val rule = createComposeRule()

    private fun seed() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        Repository.init(ctx)
        Repository.checkUpdate = false   // 测试里别发网络请求
        Repository.showTutorial = false  // 别弹教程盖住界面
        Repository.weekOverride = 7      // 固定"当前周"，行为可复现
        Repository.autoUpdate = false
        Repository.saveSchedule(
            Schedule(
                term = "2026-2027-1",
                termStartMonday = "2026-08-31",
                campus = "xz",
                courses = listOf(
                    Course(
                        id = "c1", name = "电气控制与PLC", teacher = "戴斌",
                        location = "教五楼-50320", day = 1, startPeriod = 1, endPeriod = 3,
                        weeks = (3..8).toList()
                    ),
                    Course(
                        id = "c2", name = "信号与系统分析", teacher = "焦万果",
                        location = "50308", day = 3, startPeriod = 3, endPeriod = 4,
                        weeks = (1..16).toList()
                    ),
                    Course(
                        id = "c3", name = "专业实习", teacher = "蒋小川", location = "",
                        day = 0, startPeriod = 1, endPeriod = 11, weeks = (7..9).toList()
                    )
                )
            )
        )
    }

    private fun hasText(text: String): Boolean =
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun composesMainScreenWithoutCrash() {
        seed()
        rule.setContent { ScheduleApp(mutableStateOf(0)) }
        rule.waitForIdle()
        assertTrue("应显示课程名", hasText("电气控制与PLC"))
    }

    @Test
    fun swipingDateStripAndPagerDoesNotCrash() {
        seed()
        rule.setContent { ScheduleApp(mutableStateOf(0)) }
        rule.waitForIdle()

        // 拖动日期条（v1.3.3 就是在这里一滑就闪退）
        repeat(3) {
            rule.onNodeWithTag("dateStrip").performTouchInput { swipeLeft() }
            rule.waitForIdle()
        }
        repeat(3) {
            rule.onNodeWithTag("dateStrip").performTouchInput { swipeRight() }
            rule.waitForIdle()
        }

        // 翻周（左右滑课表）
        rule.onNodeWithTag("weekPager").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.onNodeWithTag("weekPager").performTouchInput { swipeRight() }
        rule.waitForIdle()

        // 滑完之后界面还在、课表还能正常显示
        assertTrue("滑动后应仍显示课程名", hasText("电气控制与PLC"))
    }

    @Test
    fun locationShownOnGridCard() {
        seed()
        rule.setContent { ScheduleApp(mutableStateOf(0)) }
        rule.waitForIdle()
        // 主界面课程格要能看到教室（不点进详情）
        assertTrue("课程格应显示教室", hasText("教五楼-50320"))
    }

    @Test
    fun sundayWeekStartComposesAndSwipes() {
        seed()
        Repository.weekStartSunday = true   // 一周起点改周日（曾出现日期与课程对不齐）
        rule.setContent { ScheduleApp(mutableStateOf(0)) }
        rule.waitForIdle()
        assertTrue("周日起点应显示课程名", hasText("电气控制与PLC"))
        rule.onNodeWithTag("weekPager").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertTrue("周日起点翻周后仍应显示课程名", hasText("信号与系统分析"))
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h1600dp-mdpi")
    fun bottomArrangementRowSitsBelowGrid() {
        seed()
        rule.setContent { ScheduleApp(mutableStateOf(0)) }
        rule.waitForIdle()
        // 课表 pager 同时组合相邻 3 页 → gridBody / bottomRow 各有 3 个；
        // 未显示的相邻页 bounds 是全 0（宽高为 0），必须过滤掉，否则会误取到它、
        // 断言 `0 >= -2` 恒真而"假通过"。只保留有实际尺寸的节点，再取最靠左的那个。
        // 用高屏（h1600dp）：11 节网格就有 638dp，矮屏上"安排"行在视口外会被裁成 0 尺寸。
        fun visible(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes()
            .filter { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }
            .minByOrNull { it.boundsInRoot.left }
        val g = visible("gridBody")
        val r = visible("bottomRow")
        assertTrue("应有可见的课表网格", g != null)
        assertTrue("应有可见的底部'安排'行（含整周实习）", r != null)
        // "安排"行必须整体落在 11 节网格的下方。
        // 曾经它是 Box 的第二个子项，Box 默认把子项堆在左上 → 被压到课表顶部（用户报的 bug）。
        assertTrue(
            "安排行 top=${r!!.boundsInRoot.top} 应 >= 网格 bottom=${g!!.boundsInRoot.bottom}",
            r.boundsInRoot.top >= g.boundsInRoot.bottom - 2f
        )
    }

    @Test
    fun blankCellSelectsOnTapAndAddsOnDoubleTap() {
        seed()
        rule.setContent { ScheduleApp(mutableStateOf(0)) }
        rule.waitForIdle()
        // 周六是空白格（testTag = b:6:1），避开启用了"整周实习"覆盖的周一到周五。
        // 课表 pager 会同时组合相邻 3 页，同名 testTag 有 3 个；取中间那一页（当前可见的）那个。
        val blanks = rule.onAllNodesWithTag("b:6:1")
        assertTrue("应能找到空白格", blanks.fetchSemanticsNodes().isNotEmpty())
        val blank = blanks[minOf(1, blanks.fetchSemanticsNodes().size - 1)]
        // 单击：只选中，不该弹出"添加课程"对话框
        blank.performTouchInput { click() }
        rule.waitForIdle()
        assertTrue("单击空白格不该弹添加框",
            rule.onAllNodesWithText("课程名（必填）").fetchSemanticsNodes().isEmpty())
        // 双击：弹出"添加课程"对话框
        blank.performTouchInput { doubleClick() }
        rule.waitForIdle()
        assertTrue("双击空白格应弹出添加框",
            rule.onAllNodesWithText("课程名（必填）").fetchSemanticsNodes().isNotEmpty())
    }
}
