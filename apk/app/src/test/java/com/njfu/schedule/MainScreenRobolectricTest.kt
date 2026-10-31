package com.njfu.schedule

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun refreshKeepsViewedWeekAndArrangement() {
        seed()   // weekOverride=7；专业实习在第 7-9 周
        val refresh = mutableStateOf(0)
        rule.setContent { ScheduleApp(refresh) }
        rule.waitForIdle()
        // 滑到下一周（离开"本周"）→ 出现"回到第 7 周"提示
        rule.onNodeWithTag("weekPager").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertTrue("滑动后应离开本周", hasText("回到第 7 周（本周）"))
        // 模拟后台自动更新完成：刷新不该把翻周位置弹回本周。
        // 回归：以前 key(refreshState) 会整树重建，翻周位置/实习会被重置（用户报的"划着划着实习就没了"）。
        refresh.value++
        rule.waitForIdle()
        assertTrue("刷新后不该弹回本周", hasText("回到第 7 周（本周）"))
    }

    @Test
    fun locationShownOnGridCardWhenPresent() {
        // 需求：有教室的课，课程格上要显示教室（没教室的课才不显示）。
        seed()
        rule.setContent { ScheduleApp(mutableStateOf(0)) }
        rule.waitForIdle()
        assertTrue("有教室的课，课程格应显示教室", hasText("教五楼-50320"))
    }

    @Test
    fun teacherShownOnGridCard() {
        // 需求：所有课在课程格上都要显示老师（不用点进详情）。
        seed()
        rule.setContent { ScheduleApp(mutableStateOf(0)) }
        rule.waitForIdle()
        assertTrue("课程格应显示老师", hasText("戴斌"))
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h1600dp-mdpi")
    fun teacherVisibleEvenWithLongCourseName() {
        // 超长课名 + 有教室：课名/教室/老师都要能显示（字号自适应，不裁掉老师）。
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        Repository.init(ctx)
        Repository.checkUpdate = false
        Repository.showTutorial = false
        Repository.weekOverride = 7
        Repository.autoUpdate = false
        Repository.saveSchedule(
            Schedule(
                term = "2026-2027-1", termStartMonday = "2026-08-31", campus = "xz",
                courses = listOf(
                    Course("c1", "工程经济管理概论[社会认知类]（分组01）", "王老师", "50320", 1, 1, 1, (3..8).toList())
                )
            )
        )
        rule.setContent { ScheduleApp(mutableStateOf(0)) }
        rule.waitForIdle()
        val nodes = rule.onAllNodesWithText("王老师", substring = true).fetchSemanticsNodes()
            .filter { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }
        assertTrue("长课名下老师仍应可见", nodes.isNotEmpty())
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
    fun bottomRowHiddenInWeeksWithoutArrangement() {
        seed()
        // 整周实习是第 7-9 周。把"当前周"设到第 11 周：pager 只组合相邻 3 页（第 10/11/12 周），
        // 都不含第 7-9 周 → 整行、整张卡都不该出现（选 10 周不行：相邻的第 9 周仍在 7-9 内，
        // 离屏页会残留一个"专业实习"节点，导致 hasText 误判）。
        Repository.weekOverride = 11
        rule.setContent { ScheduleApp(mutableStateOf(0)) }
        rule.waitForIdle()
        val rows = rule.onAllNodesWithTag("bottomRow").fetchSemanticsNodes()
            .filter { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }
        assertTrue("第11周无整周安排，不该显示'安排'行", rows.isEmpty())
        assertTrue("也不该显示'专业实习'卡片", !hasText("专业实习"))
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h1600dp-mdpi")
    fun bottomCardsSpanMondayToFriday() {
        seed()
        rule.setContent { ScheduleApp(mutableStateOf(0)) }
        rule.waitForIdle()
        fun visible(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes()
            .filter { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }
            .minByOrNull { it.boundsInRoot.left }
        val pager = visible("weekPager")!!
        val cards = visible("bottomCards")!!
        val col = pager.boundsInRoot.width / 7f
        // 卡片区应横跨【周一~周五】5 列（起点与课表第 1 列对齐，宽度≈5/7 的课表宽）
        assertTrue(
            "卡片区左缘应贴着课表第1列（周一）: cards.left=${cards.boundsInRoot.left} pager.left=${pager.boundsInRoot.left}",
            kotlin.math.abs(cards.boundsInRoot.left - pager.boundsInRoot.left) <= 2f
        )
        assertTrue(
            "卡片区宽度应≈5列: cards.w=${cards.boundsInRoot.width} 5列=${col * 5}",
            kotlin.math.abs(cards.boundsInRoot.width - col * 5) <= 4f
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

    /** 打开设置：点顶栏设置按钮，断言新设置界面渲染出来（分组标题 + 关键控件） */
    private fun openSettings() {
        seed()
        rule.setContent { ScheduleApp(mutableStateOf(0)) }
        rule.waitForIdle()
        rule.onNodeWithTag("settingsButton").performClick()
        rule.waitForIdle()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h1600dp-mdpi")
    fun settingsDialogShowsAllSections() {
        // 设置界面美化后仍要能看到全部分组（回归：重构行式卡片别把某组漏掉）。
        openSettings()
        assertTrue("应有'设置'标题", hasText("设置"))
        assertTrue("应有账号分组", hasText("账号与导入"))
        assertTrue("应有一周起点分组", hasText("一周起点"))
        assertTrue("应有色系分组", hasText("色系"))
        assertTrue("应有外观分组", hasText("外观"))
        assertTrue("应有背景分组", hasText("背景图片"))
        assertTrue("应有更新分组", hasText("更新"))
        assertTrue("应有学期分组", hasText("学期"))
        assertTrue("应有关于分组", hasText("关于"))
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h1600dp-mdpi")
    fun settingsShowsKeyControlsAndPalettes() {
        // 关键控件与 4 个色系名都要在（色系改成色板后名字仍需可见）。
        openSettings()
        assertTrue("应有学号输入", hasText("学号 / 账号"))
        assertTrue("应有自动更新开关", hasText("打开时自动更新"))
        assertTrue("应有一周起点分段", hasText("每周从哪天开始"))
        assertTrue("应列出马卡龙色系", hasText("马卡龙"))
        assertTrue("应列出海盐色系", hasText("海盐"))
        assertTrue("应列出樱花色系", hasText("樱花"))
        assertTrue("应列出抹茶色系", hasText("抹茶"))
        assertTrue("应有主题模式分段", hasText("主题模式"))
        assertTrue("应有第一周周一", hasText("第一周周一"))
    }

    /** 当前可见的课程格文本节点数（相邻 3 页离屏页 bounds 为 0 但文本仍在，必须过滤） */
    private fun visibleTextCount(name: String): Int =
        rule.onAllNodesWithText(name).fetchSemanticsNodes()
            .count { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }

    /** 切换一周起点（周一/周日）后，课表列序必须跟着换。
     *  回归：列内容被 remember(week, inWeek, otherWeek) 缓存但键里没有"星期几"，
     *  起点切换后每列位置对应的星期变了、缓存没失效 → 只有日期条换列、课表不动。 */
    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h1600dp-mdpi")
    fun weekStartSundayReordersGridColumns() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        Repository.init(ctx)
        Repository.checkUpdate = false
        Repository.showTutorial = false
        Repository.weekOverride = 7
        Repository.autoUpdate = false
        Repository.weekStartSunday = false
        Repository.saveSchedule(
            Schedule(
                "2026-2027-1", "2026-08-31", "xz",
                listOf(
                    Course("d1", "周一课", "师A", "101", 1, 1, 2, (1..16).toList()),
                    Course("d7", "周日课", "师B", "201", 7, 1, 2, (1..16).toList())
                )
            )
        )
        val refresh = mutableStateOf(0)
        rule.setContent { ScheduleApp(refresh) }
        rule.waitForIdle()

        fun leftOf(name: String): Float? =
            rule.onAllNodesWithText(name).fetchSemanticsNodes()
                .filter { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }
                .minByOrNull { it.boundsInRoot.left }?.boundsInRoot?.left

        // 周一起点：周一在第 1 列、周日在第 7 列
        assertTrue(leftOf("周一课")!! < leftOf("周日课")!!)
        // 切到周日起点（设置保存会 refreshState++）
        Repository.weekStartSunday = true
        refresh.value++
        rule.waitForIdle()
        assertTrue(
            "切周日起点后课表列序要跟着换（周日应跑到第 1 列），" +
                "周一left=${leftOf("周一课")} 周日left=${leftOf("周日课")}",
            leftOf("周日课")!! < leftOf("周一课")!!
        )
    }

    /** 打开一门课的详情弹窗：单击选中 + 再单击（网格手势是 pointerInput，注入真实触摸才触发）。
     *  半透明的"非本周课"单击就直接开详情，所以单击后先看弹窗出没出现，没出再补一次双击。 */
    private fun openDetail(name: String) {
        val nodes = rule.onAllNodesWithText(name).fetchSemanticsNodes()
        val visible = nodes.withIndex().filter { it.value.boundsInRoot.width > 0f && it.value.boundsInRoot.height > 0f }
        assertTrue("应能找到可见的课程格：$name", visible.isNotEmpty())
        val i = visible.first().index
        rule.onAllNodesWithText(name)[i].performClick()
        rule.waitForIdle()
        if (rule.onAllNodesWithText("备忘录", substring = true).fetchSemanticsNodes().isEmpty()) {
            // 第二次用 doubleClick（与 blankCell 用例同款）：两次独立单击会被双击检测窗口吞掉
            rule.onAllNodesWithText(name)[i].performTouchInput { doubleClick() }
            rule.waitForIdle()
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h1600dp-mdpi")
    fun deleteCourseFlowWholeSemesterRemovesFromGrid() {
        seed()
        rule.setContent { ScheduleApp(mutableStateOf(0)) }
        rule.waitForIdle()
        openDetail("信号与系统分析")
        assertTrue("应打开详情弹窗", hasText("教室"))
        // 详情底部「删除」→ 确认框（默认整学期）→ 确认删除
        rule.onNodeWithTag("detailDeleteButton").performClick()
        rule.waitForIdle()
        assertTrue("应弹出删除确认框", hasText("删除范围"))
        rule.onNodeWithTag("confirmDeleteButton").performClick()
        rule.waitForIdle()
        assertEquals(
            "删除后网格里不该再有这门课", 0, visibleTextCount("信号与系统分析")
        )
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h1600dp-mdpi")
    fun deleteCourseThisWeekOnlyKeepsOtherWeeks() {
        seed()  // 信号与系统分析 1-16 周；当前周固定第 7 周
        rule.setContent { ScheduleApp(mutableStateOf(0)) }
        rule.waitForIdle()
        openDetail("信号与系统分析")
        rule.onNodeWithTag("detailDeleteButton").performClick()
        rule.waitForIdle()
        // 切到「仅第 7 周」再确认
        rule.onAllNodesWithText("仅第 7 周")[0].performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("confirmDeleteButton").performClick()
        rule.waitForIdle()
        // 第 7 周不再显示（相邻周页面上还有，但离屏不可见）；数据里其余周次保留
        assertEquals(
            "第 7 周不该再显示这门课", 0, visibleTextCount("信号与系统分析")
        )
        val c = Repository.loadSchedule().courses.first { it.id == "c2" }
        assertEquals("其余周次要保留", "1-6,8-16", formatWeeks(c.weeks))
        assertTrue("拆剩的课要标 edited（防重抓覆盖回完整周次）", c.edited)
    }

    /** 详情弹窗规格：节次/教室/老师/周次/学分五行固定显示，没数据的行显 "—" 而不是整行消失 */
    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h1600dp-mdpi")
    fun detailDialogAlwaysShowsAllFieldRows() {
        seed()   // 种子里没有学分数据 → 学分行以前会整行消失
        rule.setContent { ScheduleApp(mutableStateOf(0)) }
        rule.waitForIdle()
        openDetail("信号与系统分析")
        assertTrue("教室行要显示", hasText("教室"))
        assertTrue("老师行要显示", hasText("老师"))
        assertTrue("周次行要显示", hasText("周次"))
        assertTrue("学分行要显示（空值显 —）", hasText("学分"))
        assertTrue("空值要显占位符", hasText("—"))
    }

    /** 编辑弹窗取消"同步修改"后，时段必须还原成这一节自己的（带着并集保存会炸出重复课） */
    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h1600dp-mdpi")
    fun editDialogUncheckSyncDoesNotDuplicateCourses() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        Repository.init(ctx)
        Repository.checkUpdate = false
        Repository.showTutorial = false
        Repository.weekOverride = 7
        Repository.autoUpdate = false
        Repository.saveSchedule(
            Schedule(
                "2026-2027-1", "2026-08-31", "xz",
                listOf(
                    Course("m1", "高等数学", "张三", "50320", 1, 1, 2, (1..16).toList()),
                    Course("m2", "高等数学", "张三", "50320", 3, 3, 4, (1..16).toList())
                )
            )
        )
        rule.setContent { ScheduleApp(mutableStateOf(0)) }
        rule.waitForIdle()
        openDetail("高等数学")   // 打开的是周一那节
        rule.onAllNodesWithText("编辑")[0].performClick()
        rule.waitForIdle()
        // 取消"同步修改本周所有同名课"
        rule.onNodeWithTag("editSyncRow").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("editSaveButton").performClick()
        rule.waitForIdle()
        val courses = Repository.loadSchedule().courses
        assertEquals("取消同步后保存不能炸出重复课", 2, courses.size)
        assertEquals("周一那节保持原时段", 1, courses.first { it.day == 1 }.startPeriod)
        assertEquals("周三那节不受影响", 1, courses.count { it.day == 3 })
    }

    /** 不在本周的课（半透明"非本周课程"）删除时：不提供无效的"仅本周"，只能整学期删 */
    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h1600dp-mdpi")
    fun deleteDialogHidesThisWeekOptionForCourseNotInCurrentWeek() {
        seed()
        Repository.showOtherWeekCourses = true
        // 电气控制与PLC 是 3-8 周，第 7 周在课；改用"仅第 10-12 周"的课来当半透明课不好种，
        // 直接把 weekOverride 设到 11（实习 7-9 周不在；信号 1-16 在）——用一个 10-12 周的课
        Repository.saveSchedule(
            Schedule(
                "2026-2027-1", "2026-08-31", "xz",
                listOf(
                    Course("c2", "信号与系统分析", "焦万果", "50308", 3, 3, 4, (1..16).toList()),
                    Course("c9", "生产实习", "赵六", "厂房", 2, 1, 2, listOf(10, 11, 12))
                )
            )
        )
        Repository.weekOverride = 7
        rule.setContent { ScheduleApp(mutableStateOf(0)) }
        rule.waitForIdle()
        // 生产实习不在第 7 周 → 半透明显示，点开详情 → 删除
        openDetail("生产实习")
        rule.onNodeWithTag("detailDeleteButton").performClick()
        rule.waitForIdle()
        assertTrue(
            "不在本周的课不该出现'仅第 7 周'选项",
            rule.onAllNodesWithText("仅第 7 周").fetchSemanticsNodes().isEmpty()
        )
        assertTrue(
            "应提示只能整学期删除",
            rule.onAllNodesWithText("只能整学期删除", substring = true).fetchSemanticsNodes().isNotEmpty()
        )
        // 整学期删除仍然有效
        rule.onNodeWithTag("confirmDeleteButton").performClick()
        rule.waitForIdle()
        assertEquals(
            "整学期删除要生效", 0, visibleTextCount("生产实习")
        )
        assertFalse(Repository.loadSchedule().courses.any { it.id == "c9" })
    }
}
