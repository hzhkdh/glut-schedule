package com.glut.schedule

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiSemesterUiContractTest {
    @Test
    fun scheduleLoadingPlaceholderDoesNotDrawDefaultBackgroundBeforeSettingsRestore() {
        val screen = page("ScheduleScreen.kt")
        val loadingBranch = screen
            .substringAfter("if (!uiState.isInitialized) {")
            .substringBefore("val blocksByWeek")

        assertTrue(loadingBranch.contains("Box(modifier = modifier.fillMaxSize())"))
        assertFalse(loadingBranch.contains("ScheduleBackgroundImage("))
    }

    @Test
    fun customBackgroundLoadingDoesNotDrawBuiltInArtworkFallback() {
        val screen = page("ScheduleScreen.kt")
        val loadingBranch = screen
            .substringAfter("customBackgroundBitmap == null")
            .substringBefore("val pagerState")

        assertTrue(loadingBranch.contains("Box(modifier = modifier.fillMaxSize())"))
        assertFalse(loadingBranch.contains("ScheduleBackgroundImage("))
    }

    /**
     * 卡片只暴露「长按管理卡片」这一个新手势。
     *
     * 这条测试的前身是 `scheduleCardsDoNotExposeRemarkGesturesOrBadges`，当时明确断言
     * `ScheduleGrid.kt` 里**不许出现** `combinedClickable` —— 那是「卡片不做长按备注手势」
     * 刻意立的规矩。现在长按改用来打开「卡片管理」弹层，规矩随之收窄而不是取消：
     * 备注相关的入口与角标仍然一律不许回来，长按本身则被正向锁住，防止有人把备注手势
     * 借道加回来。这是一次有意识的推翻，不是把测试删掉了事。
     */
    @Test
    fun scheduleCardsOnlyExposeTheLongPressManageGesture() {
        val screen = page("ScheduleScreen.kt")
        val grid = component("ScheduleGrid.kt")
        assertFalse(screen.contains("viewModel.saveCourseRemark"))
        assertFalse(screen.contains("viewModel.deleteCourseRemark"))
        assertFalse(grid.contains("Icons.Rounded.ChatBubble"))
        assertTrue(grid.contains("combinedClickable"))
        assertTrue(grid.contains("onCourseLongClick"))
    }

    @Test
    fun admissionParsingUsesTheStudentNumberFromEachAuthenticationAttempt() {
        val viewModel = page("DirectLoginViewModel.kt")

        val successCallsWithSnapshot = viewModel.lineSequence().count { line ->
            line.contains("onLoginSuccess(") &&
                line.contains("state.rememberPassword, state.username")
        }
        assertTrue(
            "Guilin direct, OA fallback, and Nanning captcha success must pass their student-number snapshot",
            successCallsWithSnapshot == 3
        )
        assertTrue(viewModel.contains("remember: Boolean, studentNumber: String"))
        assertTrue(viewModel.contains("performImport(cookie, campusBaseUrl, studentNumber)"))
        assertTrue(viewModel.contains("studentNumber = studentNumber"))
        assertFalse(viewModel.contains("studentNumber = _uiState.value.username"))
    }

    @Test
    fun importScreenExposesSemesterCacheWithoutEnrollmentModal() {
        val screen = page("DirectLoginScreen.kt")
        val viewModel = page("DirectLoginViewModel.kt")

        listOf("学期课表", "当前", "已缓存", "未下载", "下载中", "重试")
            .forEach { assertTrue("missing UI copy: $it", screen.contains(it)) }
        listOf("ExposedDropdownMenuBox", "DropdownMenuItem", "下载并缓存", "重新下载", "查看课表", "正在查看")
            .forEach { assertTrue("missing dropdown UI contract: $it", screen.contains(it)) }
        assertTrue(screen.contains("disabledContentColor = LoginSecondary"))
        assertFalse(screen.contains("Text(actionLabel, color = Color.White"))
        assertTrue(screen.contains("viewModel::downloadSemester"))
        assertTrue(screen.contains("viewModel::downloadAllSemesters"))
        assertTrue(screen.contains("viewModel::retryFailedSemester"))
        assertTrue(screen.contains("全部下载"))
        assertTrue(screen.contains("最近一次全部下载"))
        assertTrue(screen.contains("已完成 \$completedItems/\${downloadState.items.size}"))
        assertTrue(screen.contains("viewModel::viewSemester"))
        assertTrue(screen.contains("heightIn(min = 48.dp)"))
        assertFalse(screen.contains("Scaffold("))
        assertFalse(screen.contains("EnrollmentStartDialog"))
        assertFalse(screen.contains("showEnrollmentDialog"))
        assertFalse(viewModel.contains("showEnrollmentDialog"))
        assertFalse(viewModel.contains("confirmEnrollmentStart"))
        assertFalse(viewModel.contains("enrollmentYearInput"))
        assertFalse(viewModel.contains("enrollmentSeason"))
        assertFalse(viewModel.contains("pendingCatalogHtml"))
        assertFalse(viewModel.contains("confirmedEnrollmentStart"))
        assertTrue(screen.contains("NanningCaptchaDialog"))
        assertTrue(screen.contains("showCaptchaDialog"))
        assertTrue(screen.contains("onRefresh, modifier = Modifier.size(48.dp)"))
        assertTrue(viewModel.contains("submitNanningCaptcha"))
    }

    @Test
    fun importSemesterDropdownUsesTheWarmLightSurfaceInDarkSystemTheme() {
        val screen = page("DirectLoginScreen.kt")

        assertTrue(screen.contains("containerColor = LoginCardBg"))
        assertTrue(screen.contains("MenuDefaults.itemColors"))
        assertTrue(screen.contains("textColor = LoginPrimary"))
    }

    @Test
    fun semesterDownloadAndViewAreDistinctAndDownloadNeverSelects() {
        val viewModel = page("DirectLoginViewModel.kt")
        val screen = page("DirectLoginScreen.kt")
        val coordinator = service("SemesterBulkDownloadCoordinator.kt")
        val container = appSource("ScheduleApplication.kt")
        val downloadBody = viewModel.substringAfter("fun downloadSemester(")
            .substringBefore("fun viewSemester(")
        val viewBody = viewModel.substringAfter("fun viewSemester(")
            .substringBefore("fun returnToCurrentSemester(")

        assertTrue(viewModel.contains("fun downloadSemester(semesterId: String)"))
        assertTrue(viewModel.contains("fun viewSemester(semesterId: String)"))
        assertFalse(downloadBody.contains("scheduleRepository.selectSemester"))
        assertTrue(downloadBody.contains("semesterBulkDownloadCoordinator.startSingle(semesterId)"))
        // 统一导入路径后批量下载没有线路可选，也没有逐周进度可言：
        // 这三个旧接口必须彻底消失，而不是留着空跑。
        assertFalse(container.contains("semesterImportMode.first()"))
        assertFalse(container.contains("onProgress = onProgress"))
        assertFalse(coordinator.contains("completedWeeks"))
        assertTrue(coordinator.contains("previousStatus"))
        assertTrue(screen.contains("canRedownload"))
        assertTrue(screen.contains("onDownloadSemester(selectedSemester.id)"))
        assertTrue(viewBody.contains("AcademicSemesterViewPlanner.weekFor("))
        assertTrue(viewBody.contains("settingsStore.setCurrentWeekNumber(week)"))
        assertTrue(viewBody.contains("scheduleRepository.selectSemester(semesterId)"))
        assertTrue(
            viewBody.indexOf("scheduleRepository.selectSemester(semesterId)") <
                viewBody.indexOf("settingsStore.setCurrentWeekNumber(week)")
        )
    }

    @Test
    fun loginImportProbesImmediateNextThenImportsCurrentFromTimetable() {
        val viewModel = page("DirectLoginViewModel.kt")
        val importBody = viewModel.substringAfter("private suspend fun performImport(")
            .substringBefore("private suspend fun fetchAndSaveScores(")

        assertTrue(importBody.contains("AcademicSemesterParser.parseCatalogPlan("))
        assertTrue(importBody.contains("AcademicSemesterProbePlanner.decide("))
        assertTrue(importBody.contains("val currentSemester = decision.currentSemester"))
        // 两次调用：先探测紧邻下学期，再正式导入当前学期。
        assertTrue(importBody.split("semesterImportService.importSemester(").size - 1 == 2)
        assertTrue(importBody.contains("semester = currentSemester"))
        // 没有线路可选之后，导入调用不得再传 mode。
        assertFalse(importBody.contains("mode = SemesterImportMode"))
        assertFalse(importBody.contains("mode = settingsStore.semesterImportMode"))
        assertTrue(importBody.contains("portalMaxWeek = currentPayload.portalMaxWeek"))
        assertTrue(
            importBody.indexOf("val currentPayload") <
                importBody.indexOf("scheduleRepository.saveSemesterCatalog(semesterCatalog)")
        )
        assertTrue(importBody.contains("AcademicSemesterCalendarResolver.resolve("))
        assertTrue(importBody.contains("weeklyStartMonday = currentPayload.semesterStartMonday"))
        assertTrue(importBody.contains("settingsStore.setSemesterStartMonday(resolvedCalendar.startMonday)"))
        assertTrue(importBody.contains("settingsStore.setSemesterEndDate(resolvedCalendar.endDate)"))
        assertTrue(importBody.contains("settingsStore.setCurrentWeekNumber(resolvedCalendar.currentWeekNumber)"))
        assertFalse(importBody.contains("AcademicSemesterCurrentImportPlanner.parse("))
    }

    @Test
    fun unifiedImportNeverDownloadsWeeksAndKeepsLandingAsMaxWeekSourceOnly() {
        val service = service("AcademicSemesterImportService.kt")

        // 逐周下载整段作废：解析器、POST 表单、进度回调都不应再出现在服务里。
        assertFalse(service.contains("WeeklyTimetableParser"))
        assertFalse(service.contains("weeklyTimetablePostUrl"))
        assertFalse(service.contains("weeklyTimetableForm"))
        assertFalse(service.contains("probeForm("))
        assertFalse(service.contains("onProgress"))
        assertFalse(service.contains("Semaphore"))
        assertFalse(service.contains("awaitAll"))
        // 周次课表只剩一次落地页元数据读取：最大周、当前周和服务器日期；失败只降级。
        assertTrue(service.contains("probeWeeklyLandingMetadata("))
        assertTrue(service.contains("WeeklyLandingPageParser.parse("))
        assertTrue(service.contains("availableWeeks"))
    }

    @Test
    fun authenticatedStudentNumberSnapshotCoversCurrentAndHistoricalImports() {
        val viewModel = page("DirectLoginViewModel.kt")
        val sessionStore = service("AcademicSessionStore.kt")
        val container = appSource("ScheduleApplication.kt")
        val importBody = viewModel.substringAfter("private suspend fun performImport(")
            .substringBefore("private suspend fun fetchAndSaveScores(")

        assertTrue(sessionStore.contains("authenticatedStudentNumber"))
        assertTrue(sessionStore.contains("saveAuthenticatedStudentNumber"))
        assertTrue(viewModel.contains("sessionStore.saveAuthenticatedStudentNumber(studentNumber)"))
        assertTrue(container.contains("academicSessionStore.authenticatedStudentNumber.first()"))
        assertTrue(container.contains("studentIdFallback = session.ownerStudentNumber"))
        assertFalse(importBody.contains("_uiState.value.username"))
        assertTrue(importBody.contains("studentIdFallback = studentNumber"))
    }

    @Test
    fun scheduleViewModelSwitchesDataSourceBeforeApplyingTargetWeek() {
        val viewModel = page("ScheduleViewModel.kt")
        val selectBody = viewModel.substringAfter("fun selectSemester(")
            .substringBefore("fun returnToCurrentSemester(")

        assertTrue(selectBody.contains("AcademicSemesterViewPlanner.weekFor("))
        assertTrue(selectBody.contains("settingsStore.setCurrentWeekNumber(week)"))
        assertTrue(selectBody.contains("repository.selectSemester(semesterId)"))
        assertTrue(
            selectBody.indexOf("repository.selectSemester(semesterId)") <
                selectBody.indexOf("settingsStore.setCurrentWeekNumber(week)")
        )
    }

    @Test
    fun scheduleViewModelsUseSharedSemesterWeekPolicyWithoutBlockingConstruction() {
        val scheduleViewModel = page("ScheduleViewModel.kt")
        val overviewViewModel = page("SemesterOverviewViewModel.kt")

        assertTrue(scheduleViewModel.contains("academicMaxWeekForSemester("))
        assertTrue(overviewViewModel.contains("academicMaxWeekForSemester("))
        assertFalse(scheduleViewModel.contains("runBlocking"))
        assertFalse(scheduleViewModel.contains(".first(), cold"))
    }

    @Test
    fun schedulePagerStateIsIsolatedPerViewedSemester() {
        val screen = page("ScheduleScreen.kt")

        assertTrue(screen.contains("key(uiState.viewedSemester?.id)"))
        assertTrue(screen.contains("rememberPagerState("))
        assertTrue(screen.contains("if (!uiState.isInitialized)"))
        assertTrue(screen.contains("pagerState.scrollToPage(targetPage)"))
        assertFalse(screen.contains("pagerState.animateScrollToPage(targetPage)"))
        assertTrue(screen.contains("pagerState.animateScrollToPage(currentWeekPage)"))
        assertTrue(screen.contains("animatedPagerScrollInProgress"))
    }

    @Test
    fun importPageKeepsCampusSelectorAndHasNoImportModeSelector() {
        val screen = page("DirectLoginScreen.kt")

        // 校区仍要选（登哪个教务），线路不再要选（只有一个数据源）。
        assertTrue("未找到「南宁分校」标题", screen.contains("Text(\"南宁分校\""))
        assertFalse("「导入模式」标签应随线路统一而删除", screen.contains("Text(\"导入模式\""))
        assertFalse("不应再有「导入方式」等同义标签", screen.contains("Text(\"导入方式\""))
        assertFalse("不应再渲染线路选择胶囊", screen.contains("listOf(\"模式1\", \"模式2\")"))
        assertFalse("线路两行图例应删除", screen.contains("模式1 · 解析"))
        assertFalse("「换用模式2重试」入口应删除", screen.contains("换用模式2重试"))
        assertFalse("「重下会换线路」提示应删除", screen.contains("semesterImportModeSwitchHint"))
    }

    @Test
    fun currentSemesterRefreshUsesExactSemesterImportAndLightweightCalendarProbe() {
        val viewModel = page("ScheduleViewModel.kt")
        val refreshBody = viewModel.substringAfter("fun refreshSchedule(")
            .substringBefore("fun clearMessage(")

        assertTrue(refreshBody.contains("uiState.value.viewedSemester"))
        assertTrue(refreshBody.contains("semesterImportService.importSemester("))
        // 线路已统一，刷新不再需要也不得携带 mode：过去要靠「沿用该学期当初的线路」
        // 才能避免跨学期改写取值口径，现在这条约束随单一数据源一起消失了。
        assertFalse(refreshBody.contains("mode = targetSemester.importMode"))
        assertFalse(refreshBody.contains("mode = settingsStore.semesterImportMode.first()"))
        assertTrue(refreshBody.contains("repository.replaceSemesterSchedule("))
        assertTrue(refreshBody.contains("portalMaxWeek = payload.portalMaxWeek"))
        assertTrue(refreshBody.contains("probeScheduleEndpoints("))
        assertTrue(refreshBody.contains("AcademicSemesterCalendarResolver.resolve("))
        assertFalse(refreshBody.contains("replaceImportedCourses("))
        assertFalse(refreshBody.contains("sessionStore.timetableUrl"))
        assertFalse(refreshBody.contains("repository.selectSemester("))
    }

    @Test
    fun historicalScheduleUsesLightweightHeaderMenuWithoutBanner() {
        val screen = page("ScheduleScreen.kt")
        val header = component("ScheduleHeader.kt")

        assertFalse(screen.contains("Surface(\n                    color = Color.Black.copy(alpha = 0.48f)"))
        assertFalse(screen.contains("ModalBottomSheet"))
        assertTrue(header.contains("DropdownMenu("))
        assertTrue(header.contains("管理与下载其他学期"))
        assertFalse(header.contains("semesterLabel"))
        assertTrue(header.contains("\"▾\""))
        assertTrue(header.contains("返回当前学期"))
        // 下拉必须显式给乳白容器色：不传 containerColor 会落到 GlutScheduleTheme 里唯一的
        // 暗色方案（Theme.kt 的 surface = 0xFF101827），这正是用户看到的「漆黑面板」。
        assertTrue(header.contains("containerColor = MenuCardBg"))
        // 状态标签（当前 / 已缓存 / 正在查看）必须走共用函数，不在渲染处再写一份字面量。
        assertTrue(header.contains("semesterMenuStatusText("))
        assertTrue(screen.contains("onSemesterSelected = viewModel::selectSemester"))
        assertTrue(screen.contains("onReturnToCurrentClick = viewModel::returnToCurrentSemester"))
        assertFalse(screen.contains("本周无课程"))
        assertFalse(screen.contains("历史学期可刷新"))
    }

    @Test
    fun historicalOverviewUsesArchiveModeWithoutProgressOrHolidayCards() {
        val screen = page("SemesterOverviewScreen.kt")
        val viewModel = page("SemesterOverviewViewModel.kt")

        assertTrue(screen.contains("历史学期归档"))
        assertTrue(screen.contains("if (uiState.isArchiveMode)"))
        assertTrue(viewModel.contains("isArchiveMode"))
        assertTrue(viewModel.contains("holidays = if (isArchiveMode) emptyList()"))
        assertFalse(screen.contains("教务系统未提供该历史学期的权威校历"))
        assertTrue(viewModel.contains("if (adj.originalWeek > 0) adj.originalWeek else adj.makeupWeek"))
    }

    private fun page(name: String): String {
        val module = File("src/main/java/com/glut/schedule/ui/pages/$name")
        return (if (module.exists()) module else File("app/src/main/java/com/glut/schedule/ui/pages/$name")).readText()
    }

    private fun component(name: String): String {
        val module = File("src/main/java/com/glut/schedule/ui/components/$name")
        return (if (module.exists()) module else File("app/src/main/java/com/glut/schedule/ui/components/$name")).readText()
    }

    private fun service(name: String): String {
        val module = File("src/main/java/com/glut/schedule/service/academic/$name")
        return (if (module.exists()) module else File("app/src/main/java/com/glut/schedule/service/academic/$name")).readText()
    }

    private fun appSource(name: String): String {
        val module = File("src/main/java/com/glut/schedule/$name")
        return (if (module.exists()) module else File("app/src/main/java/com/glut/schedule/$name")).readText()
    }
}
