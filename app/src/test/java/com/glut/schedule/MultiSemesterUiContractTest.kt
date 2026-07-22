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
        assertTrue(screen.contains("viewModel::downloadSemester"))
        assertTrue(screen.contains("viewModel::viewSemester"))
        assertTrue(screen.contains("heightIn(min = 48.dp)"))
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
        assertTrue(viewBody.contains("scheduleRepository.selectSemester(semesterId)"))
    }

    @Test
    fun loginImportProbesImmediateNextOnceAndReusesPromotedPayload() {
        val viewModel = page("DirectLoginViewModel.kt")
        val importBody = viewModel.substringAfter("private suspend fun performImport(")
            .substringBefore("private suspend fun fetchAndSaveScores(")

        assertTrue(importBody.contains("AcademicSemesterParser.parseCatalogPlan("))
        assertTrue(importBody.contains("AcademicSemesterProbePlanner.decide("))
        assertTrue(importBody.contains("decision.promotedPayload"))
        assertTrue(importBody.split("semesterImportService.importSemester(").size - 1 == 1)
    }

    @Test
    fun historicalScheduleIsReadOnlyAndOffersReturnToCurrent() {
        val screen = page("ScheduleScreen.kt")

        assertTrue(screen.contains("历史学期 · 只读"))
        assertTrue(screen.contains("返回当前"))
        assertTrue(screen.contains("onSemesterClick"))
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
    }

    private fun page(name: String): String {
        val module = File("src/main/java/com/glut/schedule/ui/pages/$name")
        return (if (module.exists()) module else File("app/src/main/java/com/glut/schedule/ui/pages/$name")).readText()
    }
}
