package com.glut.schedule

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CampusInfoUiContractTest {
    @Test
    fun campusInfoUsesFourTabsAndKeepsGesturesInsideTheImageViewport() {
        val screen = source("ui/pages/CampusImageScreen.kt")

        listOf("教学日历", "上课时间", "校车路线", "校园地图").forEach {
            assertTrue(screen.contains(it))
        }
        assertTrue(screen.contains("PrimaryTabRow"))
        assertTrue(screen.contains("viewModel::selectType"))
        assertTrue(screen.contains("Color(0xFFF6F4EF)"))
        assertTrue(screen.contains("ContentScale.Fit"))
        assertTrue(screen.contains("contentAlignment = Alignment.Center"))
        assertTrue(screen.contains("clipToBounds()"))
        assertTrue(screen.indexOf("clipToBounds()") < screen.indexOf("transformable(transformState)"))
        assertTrue(screen.contains("R.drawable.yanshan_campus_map"))
    }

    @Test
    fun mainActivityUsesOneCampusInfoDestinationAndRefreshesTheCurrentTab() {
        val main = source("MainActivity.kt")
        val drawer = source("ui/navigation/DrawerItem.kt")

        assertTrue(drawer.contains("CampusInfo(\"校园信息\""))
        assertFalse(drawer.contains("AcademicCalendar(\"校历\""))
        assertFalse(drawer.contains("ShuttleBus(\"校车路线\""))
        assertTrue(main.contains("DrawerItem.CampusInfo"))
        assertTrue(main.contains("viewModel::refreshCurrent"))
        assertTrue(main.contains("刷新校园信息"))
    }

    private fun source(path: String): String {
        val module = File("src/main/java/com/glut/schedule/$path")
        return (if (module.exists()) module else File("app/src/main/java/com/glut/schedule/$path")).readText()
    }
}
