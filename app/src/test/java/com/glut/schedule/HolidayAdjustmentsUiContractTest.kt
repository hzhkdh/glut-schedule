package com.glut.schedule

import java.io.File
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
    fun semesterOverviewLoadsEveryYearTheSemesterSpans() {
        val overview = page("SemesterOverviewViewModel.kt")

        assertTrue(overview.contains("for (year in semesterStart.year..semesterEnd.year)"))
        assertFalse(overview.contains("private suspend fun fetchHolidays("))
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
