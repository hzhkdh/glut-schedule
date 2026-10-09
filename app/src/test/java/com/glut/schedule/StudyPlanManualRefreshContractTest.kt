package com.glut.schedule

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyPlanManualRefreshContractTest {
    @Test
    fun studyPlanScreenNeverRefreshesWithoutTopBarAction() {
        val screen = source("StudyPlanScreen.kt")
        val activity = source("MainActivity.kt")

        assertFalse(screen.contains("viewModel.refresh()"))
        assertFalse(screen.contains("LaunchedEffect"))
        assertTrue(activity.contains("onClick = studyPlanViewModel::refresh"))
    }

    @Test
    fun graduateRefreshUsesGraduatePortalAndReadonlyLayout() {
        val viewModel = source("StudyPlanViewModel.kt")
        val screen = source("StudyPlanScreen.kt")

        assertTrue(viewModel.contains("CampusType.GRADUATE"))
        assertTrue(viewModel.contains("GraduateClient"))
        assertTrue(viewModel.contains("GraduateParser.studyPlan"))
        assertTrue(screen.contains("uiState.isReadOnly"))
        assertTrue(screen.contains("readOnly = uiState.isReadOnly"))
    }

    @Test
    fun graduateSessionIsCommittedOnlyAfterScheduleWrite() {
        val source = source("DirectLoginViewModel.kt")
        val loginStart = source.indexOf("private suspend fun onGraduateLoginSuccess")
        val loginEnd = source.indexOf("private suspend fun sourceClassPeriods", loginStart)
        val loginSuccess = source.substring(loginStart, loginEnd)
        assertFalse(loginSuccess.contains("sessionStore.saveCookie"))
        assertFalse(loginSuccess.contains("sessionStore.saveCampusBaseUrl"))
        assertFalse(loginSuccess.contains("scheduleRepository.clearAllData"))

        val importStart = source.indexOf("private suspend fun performGraduateImport")
        val switchIndex = source.indexOf("switchSource = {", importStart)
        assertTrue(switchIndex > importStart)
        assertTrue(source.indexOf("sessionStore.saveCookie", switchIndex) > switchIndex)
        assertTrue(source.indexOf("sessionStore.saveCampusBaseUrl", switchIndex) > switchIndex)
        val writeScheduleIndex = source.indexOf("writeSchedule = {", importStart)
        assertTrue(writeScheduleIndex > importStart)
        assertTrue(source.indexOf("scheduleRepository.clearAllData", writeScheduleIndex) > writeScheduleIndex)
    }

    private fun source(name: String): String {
        val modulePath = if (name == "MainActivity.kt") {
            "src/main/java/com/glut/schedule/$name"
        } else {
            "src/main/java/com/glut/schedule/ui/pages/$name"
        }
        val module = File(modulePath)
        val rootPath = if (name == "MainActivity.kt") {
            "app/src/main/java/com/glut/schedule/$name"
        } else {
            "app/src/main/java/com/glut/schedule/ui/pages/$name"
        }
        return (if (module.exists()) module else File(rootPath)).readText()
    }
}
