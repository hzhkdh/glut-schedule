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
