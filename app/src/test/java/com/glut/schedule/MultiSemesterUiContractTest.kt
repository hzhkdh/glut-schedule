package com.glut.schedule

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiSemesterUiContractTest {
    @Test
    fun importScreenExposesSemesterCacheAndEnrollmentStates() {
        val source = page("DirectLoginScreen.kt")

        listOf("学期课表", "当前", "已缓存", "未下载", "下载中", "重试", "确认入学学期")
            .forEach { assertTrue("missing UI copy: $it", source.contains(it)) }
        assertTrue(source.contains("selectOrImportSemester"))
        assertTrue(source.contains("heightIn(min = 48.dp)"))
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
