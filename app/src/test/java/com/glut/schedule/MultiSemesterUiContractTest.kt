package com.glut.schedule

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiSemesterUiContractTest {
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
    fun semesterDownloadAndViewAreDistinctAndDownloadNeverSelects() {
        val viewModel = page("DirectLoginViewModel.kt")
        val downloadBody = viewModel.substringAfter("fun downloadSemester(")
            .substringBefore("fun viewSemester(")
        val viewBody = viewModel.substringAfter("fun viewSemester(")
            .substringBefore("fun returnToCurrentSemester(")

        assertTrue(viewModel.contains("fun downloadSemester(semesterId: String)"))
        assertTrue(viewModel.contains("fun viewSemester(semesterId: String)"))
        assertFalse(downloadBody.contains("scheduleRepository.selectSemester"))
        assertTrue(downloadBody.contains("useWeeklyTimetable = true"))
        assertTrue(downloadBody.contains("onProgress = { completed, total ->"))
        assertTrue(downloadBody.contains("第${'$'}{completed}/${'$'}{total}周"))
        assertTrue(viewBody.contains("AcademicSemesterViewPlanner.weekFor("))
        assertTrue(viewBody.contains("settingsStore.setCurrentWeekNumber(week)"))
        assertTrue(viewBody.contains("scheduleRepository.selectSemester(semesterId)"))
        assertTrue(
            viewBody.indexOf("scheduleRepository.selectSemester(semesterId)") <
                viewBody.indexOf("settingsStore.setCurrentWeekNumber(week)")
        )
    }

    @Test
    fun loginImportProbesImmediateNextThenImportsCurrentFromWeeklyTimetable() {
        val viewModel = page("DirectLoginViewModel.kt")
        val importBody = viewModel.substringAfter("private suspend fun performImport(")
            .substringBefore("private suspend fun fetchAndSaveScores(")

        assertTrue(importBody.contains("AcademicSemesterParser.parseCatalogPlan("))
        assertTrue(importBody.contains("AcademicSemesterProbePlanner.decide("))
        assertTrue(importBody.contains("decision.promotedPayload"))
        assertTrue(importBody.split("semesterImportService.importSemester(").size - 1 == 2)
        assertTrue(importBody.contains("useWeeklyTimetable = false"))
        assertTrue(importBody.contains("semester = currentSemester"))
        assertTrue(importBody.contains("useWeeklyTimetable = true"))
        assertTrue(importBody.contains("portalMaxWeek = currentPayload.portalMaxWeek"))
        assertTrue(
            importBody.indexOf("val currentPayload") <
                importBody.indexOf("scheduleRepository.saveSemesterCatalog(semesterCatalog)")
        )
        assertTrue(importBody.contains("AcademicSemesterCalendarEstimator.estimate("))
        assertTrue(importBody.contains("settingsStore.setSemesterStartMonday(estimatedCalendar.startMonday)"))
        assertTrue(importBody.contains("settingsStore.setSemesterEndDate(estimatedCalendar.endDate)"))
        assertTrue(importBody.contains("settingsStore.setCurrentWeekNumber(estimatedCalendar.currentWeekNumber)"))
        assertFalse(importBody.contains("AcademicSemesterCurrentImportPlanner.parse("))
    }

    @Test
    fun weeklyTimetableImportIsSequentialAndValidatesEveryPage() {
        val service = service("AcademicSemesterImportService.kt")

        assertFalse(service.contains("WEEKLY_TIMETABLE_PARALLELISM"))
        assertFalse(service.contains("Semaphore"))
        assertFalse(service.contains("async"))
        assertFalse(service.contains("awaitAll"))
        assertTrue(service.contains("availableWeeks.sorted().forEach"))
        assertTrue(service.contains("page.validateFor("))
        assertTrue(service.contains("expectedSemesterMonday"))
    }

    @Test
    fun authenticatedStudentNumberSnapshotCoversCurrentAndHistoricalImports() {
        val viewModel = page("DirectLoginViewModel.kt")
        val sessionStore = service("AcademicSessionStore.kt")
        val downloadBody = viewModel.substringAfter("fun downloadSemester(")
            .substringBefore("fun viewSemester(")
        val importBody = viewModel.substringAfter("private suspend fun performImport(")
            .substringBefore("private suspend fun fetchAndSaveScores(")

        assertTrue(sessionStore.contains("authenticatedStudentNumber"))
        assertTrue(sessionStore.contains("saveAuthenticatedStudentNumber"))
        assertTrue(viewModel.contains("sessionStore.saveAuthenticatedStudentNumber(studentNumber)"))
        assertTrue(downloadBody.contains("sessionStore.authenticatedStudentNumber.first()"))
        assertFalse(downloadBody.contains("_uiState.value.username"))
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
    }

    @Test
    fun currentSemesterRefreshUsesExactSemesterImportWithoutGlobalProbeFallback() {
        val viewModel = page("ScheduleViewModel.kt")
        val refreshBody = viewModel.substringAfter("fun refreshSchedule(")
            .substringBefore("fun clearMessage(")

        assertTrue(refreshBody.contains("uiState.value.viewedSemester"))
        assertTrue(refreshBody.contains("semesterImportService.importSemester("))
        assertTrue(refreshBody.contains("useWeeklyTimetable = true"))
        assertTrue(refreshBody.contains("repository.replaceSemesterSchedule("))
        assertTrue(refreshBody.contains("portalMaxWeek = payload.portalMaxWeek"))
        assertFalse(refreshBody.contains("probeScheduleEndpoints("))
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
}
