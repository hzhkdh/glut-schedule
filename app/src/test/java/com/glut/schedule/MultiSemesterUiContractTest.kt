package com.glut.schedule

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiSemesterUiContractTest {
    @Test
    fun importScreenExposesSemesterCacheWithoutEnrollmentModal() {
        val screen = page("DirectLoginScreen.kt")
        val viewModel = page("DirectLoginViewModel.kt")

        listOf("学期课表", "当前", "已缓存", "未下载", "下载中", "重试")
            .forEach { assertTrue("missing UI copy: $it", screen.contains(it)) }
        assertTrue(screen.contains("selectOrImportSemester"))
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
