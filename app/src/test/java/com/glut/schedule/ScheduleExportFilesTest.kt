package com.glut.schedule

import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.service.export.ExportSolidBackground
import com.glut.schedule.service.export.relativeLuminanceOf
import com.glut.schedule.service.export.sanitizeExportName
import com.glut.schedule.service.export.scheduleCsvFileName
import com.glut.schedule.service.export.scheduleIcsFileName
import com.glut.schedule.service.export.scheduleImageFileName
import com.glut.schedule.service.export.schedulePdfFileName
import com.glut.schedule.ui.navigation.DrawerItem
import com.glut.schedule.ui.navigation.isEnabledFor
import com.glut.schedule.ui.navigation.unsupportedHintFor
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleExportFilesTest {

    @Test
    fun stripsFilesystemReservedCharactersFromSemesterNames() {
        assertEquals(
            "20262027第一学期",
            sanitizeExportName("2026/2027:第一*学期?\"<>|", "本学期")
        )
        assertEquals("本学期", sanitizeExportName("///", "本学期"))
        assertEquals("本学期", sanitizeExportName("   ", "本学期"))
    }

    @Test
    fun imageFileNameCarriesSemesterWeekAndMondayDate() {
        assertEquals(
            "桂工课表_2026-2027学年第一学期_第09周_20261026.png",
            scheduleImageFileName(
                semesterName = "2026-2027学年第一学期",
                weekNumber = 9,
                weekMonday = LocalDate.of(2026, 10, 26)
            )
        )
    }

    @Test
    fun icsFileNameUsesTheSemesterOnly() {
        assertEquals(
            "桂工课表_2026-2027学年第一学期.ics",
            scheduleIcsFileName("2026-2027学年第一学期")
        )
    }

    @Test
    fun csvAndPdfFileNamesDescribeTheExportScope() {
        assertEquals(
            "桂工课表_2026-2027学年第一学期.csv",
            scheduleCsvFileName("2026-2027学年第一学期")
        )
        assertEquals(
            "桂工课表_2026-2027学年第一学期_第09周.pdf",
            schedulePdfFileName("2026-2027学年第一学期", weekNumber = 9, wholeSemester = false)
        )
        assertEquals(
            "桂工课表_2026-2027学年第一学期_整学期.pdf",
            schedulePdfFileName("2026-2027学年第一学期", weekNumber = 9, wholeSemester = true)
        )
    }

    @Test
    fun solidBackgroundPaletteStaysDarkEnoughForWhiteText() {
        val entries = ExportSolidBackground.entries
        assertTrue("至少提供 6 种配色", entries.size >= 6)
        assertEquals("配色不应重复", entries.size, entries.map { it.topColor }.distinct().size)
        entries.forEach { option ->
            val luminance = relativeLuminanceOf(option.topColor)
            assertTrue(
                "${option.label} 亮度处于中间地带，白字和深色字都不够清楚",
                luminance < 0.35 || luminance > 0.65
            )
        }
        assertTrue(ExportSolidBackground.WHITE.isLight)
        assertFalse(ExportSolidBackground.NIGHT_BLUE.isLight)
        assertEquals(ExportSolidBackground.NIGHT_BLUE, ExportSolidBackground.DEFAULT)
    }

    @Test
    fun exportEntryStaysAvailableForEveryCampus() {
        listOf(CampusType.GUILIN, CampusType.NANNING, CampusType.GRADUATE).forEach { campus ->
            assertTrue("导出课表在 $campus 应可用", DrawerItem.Export.isEnabledFor(campus))
            assertNull(DrawerItem.Export.unsupportedHintFor(campus))
        }
        assertEquals("导出课表", DrawerItem.Export.title)
    }

    @Test
    fun exportEntrySitsFirstInOtherSection() {
        val module = File("src/main/java/com/glut/schedule/MainActivity.kt")
        val main = (if (module.exists()) module else File("app/src/main/java/com/glut/schedule/MainActivity.kt")).readText()
        assertTrue(
            main.contains(
                "listOf(DrawerItem.Schedule, DrawerItem.Exam, DrawerItem.StudyPlan, " +
                    "DrawerItem.SemesterOverview, DrawerItem.Import)"
            )
        )
        assertEquals(DrawerItem.Export, com.glut.schedule.ui.navigation.otherDrawerItems.first())
        assertTrue(main.contains("DrawerItem.Export -> ScheduleExportDestination"))
        assertTrue(main.contains("onSelectSemester = viewModel::selectSemester"))
    }

    @Test
    fun exportScreenOffersSemesterSwitching() {
        val module = File("src/main/java/com/glut/schedule/ui/pages/ScheduleExportScreen.kt")
        val screen = (if (module.exists()) module else File("app/src/main/java/com/glut/schedule/ui/pages/ScheduleExportScreen.kt")).readText()
        assertTrue(screen.contains("private fun SemesterSelectionCard("))
        assertTrue(screen.contains("state.semesters.filter"))
        assertTrue(screen.contains("管理与下载其他学期"))
        assertTrue(screen.contains("历史学期为只读快照，导出内容不含调休调课"))
    }

    @Test
    fun exportScreenKeepsAllFormatsAndSafetyNotes() {
        val module = File("src/main/java/com/glut/schedule/ui/pages/ScheduleExportScreen.kt")
        val screen = (if (module.exists()) module else File("app/src/main/java/com/glut/schedule/ui/pages/ScheduleExportScreen.kt")).readText()
        assertTrue(screen.contains("\"图片\""))
        assertTrue(screen.contains("\"PDF\""))
        assertTrue(screen.contains("\"表格\""))
        assertTrue(screen.contains("\"日历\""))
        assertTrue(screen.contains("保存到相册"))
        assertTrue(screen.contains("保存 PDF"))
        assertTrue(screen.contains("保存 CSV"))
        assertTrue(screen.contains("保存 ICS"))
        assertTrue(screen.contains("选择当前背景后，导出将包含你的自定义图片。"))
        assertTrue(screen.contains("研究生请先在设置中补全 12 节上课时间"))
        assertTrue(screen.contains("冲突课显示第一门"))
    }
}
