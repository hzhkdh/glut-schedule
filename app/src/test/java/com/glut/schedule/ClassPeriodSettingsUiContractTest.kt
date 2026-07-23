package com.glut.schedule

import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.ui.pages.classPeriodSettingsLabels
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassPeriodSettingsUiContractTest {
    @Test
    fun guilinUsesFourteenRowsIncludingNoonAndTwelfthPeriod() {
        val labels = classPeriodSettingsLabels(CampusType.GUILIN)

        assertEquals(14, labels.size)
        assertEquals(listOf("第1节", "第2节", "第3节", "第4节"), labels.take(4))
        assertEquals(listOf("午1", "午2"), labels.subList(4, 6))
        assertEquals("第12节", labels.last())
    }

    @Test
    fun nanningUsesElevenRowsWithoutNoonPeriods() {
        val labels = classPeriodSettingsLabels(CampusType.NANNING)

        assertEquals((1..11).map { "第${it}节" }, labels)
        assertFalse(labels.any { it.startsWith("午") })
    }

    @Test
    fun settingsNavigationAndDraftActionsAreExposed() {
        val activity = source("MainActivity.kt")
        val screen = page("ClassPeriodSettingsScreen.kt")

        assertTrue(activity.contains("SettingsSubPage.CLASS_PERIODS"))
        assertTrue(activity.contains("\"上课时间\""))
        assertTrue(activity.contains("onClassPeriods"))
        assertTrue(screen.contains("\"保存\""))
        assertTrue(screen.contains("\"恢复默认时间\""))
        assertTrue(screen.contains("remember(campusType, periods)"))
        assertTrue(screen.contains("validateClassPeriods(campusType, draft)"))
        assertTrue(screen.contains("draft = defaultClassPeriods(campusType)"))
    }

    private fun source(name: String): String {
        val module = File("src/main/java/com/glut/schedule/$name")
        return (if (module.exists()) module else File("app/src/main/java/com/glut/schedule/$name")).readText()
    }

    private fun page(name: String): String {
        val module = File("src/main/java/com/glut/schedule/ui/pages/$name")
        return (if (module.exists()) module else File("app/src/main/java/com/glut/schedule/ui/pages/$name")).readText()
    }
}
