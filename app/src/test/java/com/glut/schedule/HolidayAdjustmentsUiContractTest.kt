package com.glut.schedule

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「休 / 调」日期角标与「调休调课」编辑页的源码契约。
 *
 * 这些断言锁的是「接线」而不是纯逻辑（纯逻辑见 [ManualDayCopyTest]）：
 * 入口有没有接上、网格有没有真的收到数据、有没有偷偷加一个开关。
 */
class HolidayAdjustmentsUiContractTest {

    @Test
    fun settingsExposesHolidayAdjustmentsEntryWithoutAHolidayToggle() {
        val activity = source("MainActivity.kt")

        assertTrue(activity.contains("HOLIDAY_ADJUSTMENTS(\"调休调课\")"))
        assertTrue(activity.contains("onHolidayAdjustments"))
        assertTrue(activity.contains("HolidayAdjustmentsScreen("))
        assertTrue(activity.contains("HolidayAdjustmentsViewModelFactory("))
        // 角标常驻、无设置开关，与小程序一致。
        assertFalse(activity.contains("显示节假日"))
    }

    @Test
    fun editorPageIsASinglePanelSplitByDividers() {
        val screen = page("HolidayAdjustmentsScreen.kt")

        assertTrue(screen.contains("\"原日期\""))
        assertTrue(screen.contains("\"目标日期\""))
        assertTrue(screen.contains("原日期课程不会移除"))
        assertTrue(screen.contains("\"添加调课\""))
        assertTrue(screen.contains("已添加的调课"))
        assertTrue(screen.contains("暂未添加调休调课"))
        assertTrue(screen.contains("暂未找到当前学期课表"))
        // 目标日是法定假日时先确认，而不是拒绝保存。
        assertTrue(screen.contains("目标日为节假日"))
        // 单页连续面板：三段之间只用分隔线，不各自套卡片。
        assertTrue(screen.contains("private fun Divider()"))
        assertFalse(screen.contains("IntroCard"))
        assertFalse(screen.contains("EditorCard"))
        assertFalse(screen.contains("RuleCard"))
    }

    @Test
    fun editorRestrictsDatePickingToTheSemesterRange() {
        val screen = page("HolidayAdjustmentsScreen.kt")

        assertTrue(screen.contains("SelectableDates"))
        assertTrue(screen.contains("isSelectableDate"))
        assertTrue(screen.contains("nextDateWithinSemester"))
    }

    @Test
    fun scheduleGridRendersRestAndAdjustmentMarkersWithAdjustmentWinning() {
        val grid = component("ScheduleGrid.kt")

        assertTrue(grid.contains("HOLIDAY(\"休\")"))
        assertTrue(grid.contains("ADJUSTMENT(\"调\")"))
        assertTrue(grid.contains("Color(0xFF2D9A72)"))
        assertTrue(grid.contains("Color(0xFFE57411)"))
        // 角标绝对定位在日期格右下角，不能撑高日期栏或改变列宽。
        assertTrue(grid.contains("Alignment.BottomEnd"))
        assertTrue(grid.contains(".width(dayWidth)"))
    }

    @Test
    fun scheduleScreenFeedsMarkersAndCopiesIntoTheGrid() {
        val screen = page("ScheduleScreen.kt")
        val viewModel = page("ScheduleViewModel.kt")

        assertTrue(screen.contains("holidayDates = uiState.holidayDates"))
        assertTrue(screen.contains("manualAdjustmentDates ="))
        assertTrue(screen.contains("manualCopyBlocksForWeek("))
        assertTrue(viewModel.contains("manualDayCopiesBySemester[currentSemesterId]"))
        // 历史学期是只读快照：不取用当前学期的规则与节假日角标。
        assertTrue(viewModel.contains("holidayDates = if (isHistorical) emptySet()"))
    }

    @Test
    fun holidayCacheIsKeyedByDataYearNotFetchDay() {
        val store = source("data/settings/ScheduleSettingsStore.kt")
        val client = source("service/holiday/TimorHolidayClient.kt")

        assertTrue(store.contains("holidays_cache_years"))
        assertTrue(store.contains("setHolidayYearCache"))
        // 旧读取方（学期概览、问候语）沿用同一份派生值，签名不变。
        assertTrue(store.contains("val holidaysCache: Flow<Pair<String, String>>"))
        assertTrue(client.contains("/api/holiday/year/"))
    }

    @Test
    fun dialogsPinLightContainerColorsInsteadOfInheritingTheDarkTheme() {
        val adjustments = page("HolidayAdjustmentsScreen.kt")
        val score = page("ScoreScreen.kt")

        // Material3 的 AlertDialog / DatePicker 默认容器色是 surfaceContainerHigh，而本应用
        // 主题是 darkColorScheme，其默认值是深灰 #2B2930（黑底白字）。因此凡是不显式传色的
        // 弹窗都会渲染成黑色 UI —— 这两个页面必须显式指定。
        // 两个 AlertDialog 各一套三件套（日期选择器的容器色走 colors 对象，见下）。
        assertEquals(
            2,
            Regex("containerColor = PanelBg,\\s*titleContentColor = TextPrimary")
                .findAll(adjustments).count()
        )
        assertTrue(adjustments.contains("colors = pickerColors"))
        assertTrue(adjustments.contains("titleContentColor = TextPrimary"))
        assertTrue(adjustments.contains("textContentColor = TextSecondary"))
        assertTrue(adjustments.contains("DatePickerDefaults.colors("))
        assertTrue(score.contains("containerColor = ScoreCardBg"))
        assertTrue(score.contains("titleContentColor = ScorePrimary"))
        assertTrue(score.contains("textContentColor = ScoreSecondary"))
    }

    @Test
    fun semesterOverviewLoadsEveryYearTheSemesterSpans() {
        val overview = page("SemesterOverviewViewModel.kt")

        // 秋季学期跨年到次年 1 月，只取「今年」会漏掉元旦。
        assertTrue(overview.contains("val years = semesterStart.year..semesterEnd.year"))
        assertTrue(overview.contains("for (year in years)"))
        // 与首页角标共用同一个取数入口，避免两条路径口径漂移。
        assertTrue(overview.contains("refreshMissingHolidayYears("))
        assertFalse(overview.contains("private suspend fun fetchHolidays("))
    }

    @Test
    fun holidayFetchHappensOnlyOnUserInitiatedRefresh() {
        val schedule = page("ScheduleViewModel.kt")
        val overview = page("SemesterOverviewViewModel.kt")
        val import = page("DirectLoginViewModel.kt")

        // 三个主动入口：刷新课表 / 刷新学期概览 / 重新导入课表。
        assertTrue(schedule.contains("refreshHolidayYears()"))
        assertTrue(overview.contains("loadHolidays(fetchMissing = true)"))
        assertTrue(import.contains("refreshMissingHolidayYears("))
        // 进入页面只读缓存，不做任何自动请求。
        assertTrue(overview.contains("loadHolidays(fetchMissing = false)"))
        // 不能又在 init 里自动取数——那等于把「只在主动刷新时请求」重新变成后台轮询。
        assertFalse(schedule.contains("ensureHolidayYearsForCurrentSemester"))
        // 出现次数就是「定义 1 次 + 刷新入口调用 1 次」；多一处即意味着又冒出了自动触发点。
        assertEquals(2, Regex("refreshHolidayYears").findAll(schedule).count())
    }

    private fun source(name: String): String {
        val module = File("src/main/java/com/glut/schedule/$name")
        return (if (module.exists()) module else File("app/src/main/java/com/glut/schedule/$name")).readText()
    }

    private fun page(name: String): String {
        val module = File("src/main/java/com/glut/schedule/ui/pages/$name")
        return (if (module.exists()) module else File("app/src/main/java/com/glut/schedule/ui/pages/$name")).readText()
    }

    private fun component(name: String): String {
        val module = File("src/main/java/com/glut/schedule/ui/components/$name")
        return (if (module.exists()) module else File("app/src/main/java/com/glut/schedule/ui/components/$name")).readText()
    }
}
