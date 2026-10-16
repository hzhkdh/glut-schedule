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
        // 规则列表按来源分成两组，组标题由 GroupHeader 渲染（原来是一个混在一起的「已添加的调课」）。
        assertTrue(screen.contains("GroupHeader("))
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
        // 出现次数就是「定义 1 次 + 刷新入口调用 2 次（本科/南宁分支、研究生分支）」。
        // 研究生取数链路完全独立，若只让本科分支同步，研究生用户点「刷新课表」拿不到「休」角标。
        // 多一处即意味着又冒出了自动触发点。
        assertEquals(3, Regex("refreshHolidayYears").findAll(schedule).count())
    }

    /**
     * 自动调课的对账表只在用户主动刷新时取。计数式断言：**每个入口恰好一次**，
     * 多出来一次就意味着某处又冒出了自动触发点（与 `refreshHolidayYears` 那条同一语言风格）。
     */
    @Test
    fun autoMakeupFetchesOnlyOnUserInitiatedRefresh() {
        val schedule = page("ScheduleViewModel.kt")
        val overview = page("SemesterOverviewViewModel.kt")
        val directLogin = page("DirectLoginViewModel.kt")
        val editor = page("HolidayAdjustmentsViewModel.kt")

        assertEquals(1, Regex("refreshMakeupTable\\(").findAll(schedule).count())
        assertEquals(1, Regex("refreshMakeupTable\\(").findAll(overview).count())
        assertEquals(1, Regex("refreshMakeupTable\\(").findAll(directLogin).count())
        assertEquals(1, Regex("refreshMakeupTable\\(").findAll(editor).count())

        // 编辑页那一次必须落在按钮处理函数里，不能出现在 init（进入页面只读缓存）。
        assertTrue(editor.contains("fun downloadMakeupTable()"))
        assertTrue(editor.substringAfter("fun downloadMakeupTable()").contains("refreshMakeupTable("))
        assertTrue(overview.contains("loadHolidays(fetchMissing = false)"))
    }

    /**
     * 研究生也要显示国家法定节假日，并执行学校统一补班；门户有没有下发某门课的调课记录
     * 是另一回事。若只沿用本科导入后的同步逻辑，研究生首次导入后没有节假日缓存，日期栏
     * 就缺少「休」，而自动补班只能等用户绕到别的页面刷新。
     */
    @Test
    fun graduateImportSynchronizesHolidayAndMakeupDataBeforeRecomposingRules() {
        val login = page("DirectLoginViewModel.kt")
        val graduateImport = login.substringAfter("private suspend fun performGraduateImport(")
            .substringBefore("private companion object")

        assertTrue(
            "研究生课表成功写入后也必须补齐节假日与补班对照表",
            graduateImport.contains("refreshHolidayAndMakeupData(")
        )
        val sync = login.substringAfter("private suspend fun refreshHolidayAndMakeupData(")
            .substringBefore("private suspend fun performImport(")
        assertTrue(sync.contains("refreshMissingHolidayYears("))
        assertTrue(sync.contains("refreshMakeupTable("))
        // 重算必须晚于研究生课程与门户调课记录落库，否则自动规则无法对已下发补课让位。
        assertTrue(graduateImport.contains("autoDayCopyCoordinator.recompose("))
    }

    @Test
    fun automaticWeekendMakeupsExpandTheGridWithoutOverwritingUserPreference() {
        val viewModel = page("ScheduleViewModel.kt")

        assertTrue(
            "自动规则目标日在周末时，课表必须展开周末列，否则补课卡会被五列网格过滤",
            viewModel.contains("autoRules.any { it.targetDate.dayOfWeek.value > 5 }")
        )
        assertTrue(
            "自动展开只影响当前渲染，仍要保留用户手动显示周末的设置",
            viewModel.contains("settings.showWeekend || autoWeekendMakeup")
        )
    }

    @Test
    fun graduateManualRefreshAlsoSynchronizesHolidayAndMakeupCaches() {
        val viewModel = page("ScheduleViewModel.kt")
        val refresh = viewModel.substringAfter("fun refreshSchedule()")
            .substringBefore("private suspend fun importExactSemester(")
        val graduateStart = refresh.indexOf("if (targetSemester.campus == CampusType.GRADUATE)")
        val graduateLaunch = refresh.indexOf("viewModelScope.launch {", graduateStart)
        val ordinaryLaunch = refresh.indexOf("viewModelScope.launch {", graduateLaunch + 1)
        assertTrue(graduateStart >= 0 && graduateLaunch > graduateStart && ordinaryLaunch > graduateLaunch)
        val graduateBranch = refresh.substring(graduateStart, ordinaryLaunch)

        assertTrue("研究生手动刷新也要补齐公共节假日缓存", graduateBranch.contains("refreshHolidayYears()"))
        assertTrue("研究生手动刷新也要同步学校补班对照表", graduateBranch.contains("fetchMakeupTable()"))
        assertTrue("旁路缓存同步后才刷新研究生课表", graduateBranch.contains("refreshGraduateSchedule(targetSemester)"))
    }

    @Test
    fun autoCopyRulesAreGatedByTheSwitchAtTheRenderLayer() {
        val store = source("data/settings/ScheduleSettingsStore.kt")
        val viewModel = page("ScheduleViewModel.kt")
        val widget = source("widget/ScheduleWidgetDataSource.kt")
        val editor = page("HolidayAdjustmentsViewModel.kt")

        assertTrue(store.contains("auto_day_copy_enabled"))
        // 存储层闸门：开关关闭时恒为空表，任何消费方都画不出已关闭的规则。
        assertTrue(store.contains("if (!enabled) emptyMap()"))
        // 三处渲染消费点都取用这个已开闸门的 Flow，而不是裸读存储键。
        assertTrue(viewModel.contains("settingsStore.autoDayCopies"))
        assertTrue(widget.contains("settingsStore.autoDayCopies"))
        assertTrue(editor.contains("settingsStore.autoDayCopies"))
        // 开关默认打开：没拨过开关的人直接就能看到调休补课卡；显式关过的保持关闭。
        assertTrue(
            store.contains(
                "internal fun autoDayCopyEnabledFromStored(value: Boolean?): Boolean = value ?: true"
            )
        )
    }

    @Test
    fun autoSectionLivesOnTheEditorPageWithoutBreakingTheSinglePanel() {
        val screen = page("HolidayAdjustmentsScreen.kt")
        val viewModel = page("HolidayAdjustmentsViewModel.kt")

        // 两个分区标题成对：只差「自动 / 手动」两字，一眼能看出是对立的两类。
        assertTrue(screen.contains("\"自动调休\""))
        assertTrue(screen.contains("\"手动调休\""))
        assertTrue(screen.contains("state.autoDayCopyEnabled"))
        assertTrue(screen.contains("AutoDayCopySection("))
        // 组标题即来源声明，由 ViewModel 侧的分组函数提供。
        assertTrue(viewModel.contains("AUTO_ROW_GROUP_TITLE = \"自动补的\""))
        assertTrue(viewModel.contains("MANUAL_ROW_GROUP_TITLE = \"我加的\""))
        // 来源徽标：自动规则必须能一眼看出不是自己加的。
        assertTrue(viewModel.contains("AUTO_REMOTE(\"自动·教务\")"))
        assertTrue(viewModel.contains("AUTO_DERIVED(\"自动·推导\")"))
        // 仍然只用 Divider 分段，不新增卡片；也不在设置主页加开关。
        assertFalse(screen.contains("AutoCard"))
        assertFalse(screen.contains("显示节假日"))
    }

    /**
     * 两大类各有自己的身份色：自动走页面本来就有的强调蓝，手动走课表「调」角标已经在用的橙。
     *
     * 之前两类在视觉上分不出来——手动表单的日期块和自动规则的行内徽标用的是**同一个蓝**，
     * 于是「自动 / 手动」长得一模一样。手动的日期块也必须跟着自己那一类换成橙。
     */
    @Test
    fun theTwoCategoriesCarryOppositeAccents() {
        val screen = page("HolidayAdjustmentsScreen.kt")

        assertTrue(screen.contains("Accent = Color(0xFF3F7DF6)"))
        assertTrue(screen.contains("ManualAccent = Color(0xFFE57411)"))
        assertTrue(screen.contains("ManualChipBg = Color(0xFFFDF3E9)"))
        assertTrue(screen.contains("ManualChipText = Color(0xFFC2610A)"))
        // 分区标题左侧的身份色条：颜色即身份，不必读文字就分得清。
        assertTrue(screen.contains("private fun AccentRail("))
    }

    /**
     * 逐行来源徽标已撤掉，来源改由组标题声明。
     *
     * 那个徽标在长日期文本旁边会被挤成「自 / 动 / · / 教 / 务」一个字一行，并把整行撑高到
     * 日期行看着偏上。分组的纯逻辑见 [ManualDayCopyGroupTest]。
     */
    @Test
    fun rowBadgeIsReplacedByGroupHeadings() {
        val screen = page("HolidayAdjustmentsScreen.kt")

        assertFalse("来源徽标不该再逐行渲染", screen.contains("row.origin.label"))
        assertTrue("来源应由组标题声明", screen.contains("GroupHeader("))
    }

    @Test
    fun makeupDerivationYieldsToAcademicAdjustments() {
        val model = source("data/model/AutoDayCopy.kt")

        // 让位是纯函数，且必须按 makeupWeek/makeupDay 反查绝对日期。
        assertTrue(model.contains("internal fun adjustmentTargetDates("))
        assertTrue(model.contains("makeupWeek in MIN_ACADEMIC_WEEK..MAX_ACADEMIC_WEEK"))
        assertTrue(model.contains("yieldedDates: Set<LocalDate>"))
        // 推导带一键停机开关，出事时能不改逻辑就退回「只信对照表」。
        assertTrue(model.contains("AUTO_DERIVATION_ENABLED"))
    }

    /**
     * 让位判定读的是 Room 里的教务调课记录，所以**每一条会写这些记录的路径都必须重算**。
     * 漏一处，教务新下发的补课卡就会和自动副本同时显示同一节课。
     *
     * 计数式断言：数字变了就说明新增/移除了写调课记录的路径，需要重新确认下面每条都接了重算。
     */
    @Test
    fun everyPathThatWritesAdjustmentsRecomposesAutoCopies() {
        // 刷新课表（本科 + 研究生两条分支）+ 冷启动同步对照表之后（表里收录了本学期时推导要退场，
        // 不重算就得等用户下次刷新才生效）。
        assertEquals(3, Regex("autoDayCopyCoordinator\\.recompose\\(").findAll(page("ScheduleViewModel.kt")).count())
        // 本科/南宁导入与研究生导入都在成功落库后重算，缺任一条都会让首次导入看不到补班规则。
        assertEquals(2, Regex("autoDayCopyCoordinator\\.recompose\\(").findAll(page("DirectLoginViewModel.kt")).count())
        // 学期概览：刷新节假日之后 + 「刷新调课」写入新记录之后。
        assertEquals(2, Regex("autoDayCopyCoordinator\\.recompose\\(").findAll(page("SemesterOverviewViewModel.kt")).count())
        // 批量下载历史学期那条路径写在 AppContainer 的 commit 里。
        assertEquals(1, Regex("autoDayCopyCoordinator\\.recompose\\(").findAll(source("ScheduleApplication.kt")).count())
        // 编辑页：开关 / 重新计算 / 下载对照表 / 恢复已忽略。
        assertEquals(4, Regex("autoDayCopyCoordinator\\.recompose\\(").findAll(page("HolidayAdjustmentsViewModel.kt")).count())
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
