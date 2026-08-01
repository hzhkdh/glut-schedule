package com.glut.schedule

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseTimeStatsScreenContractTest {
    @Test
    fun semesterDropdownUsesExplicitLightContainerForReadableText() {
        val module = File("src/main/java/com/glut/schedule/ui/pages/CourseTimeStatsScreen.kt")
        val source = (
            if (module.exists()) module
            else File("app/src/main/java/com/glut/schedule/ui/pages/CourseTimeStatsScreen.kt")
        ).readText()

        assertTrue(
            "学期下拉菜单必须显式使用浅色容器，避免设备深色弹层与深色文字重叠",
            source.contains("containerColor = StatsCardBg")
        )
    }

    @Test
    fun everyDimensionUsesTheSamePlannedHoursSummaryLabel() {
        val module = File("src/main/java/com/glut/schedule/ui/pages/CourseTimeStatsScreen.kt")
        val source = (
            if (module.exists()) module
            else File("app/src/main/java/com/glut/schedule/ui/pages/CourseTimeStatsScreen.kt")
        ).readText()

        assertFalse(source.contains("教师人课时"))
        assertTrue(source.contains("计划课时"))
    }

    @Test
    fun distributionUsesDonutAndTwoColumnDetailsWithoutSummaryOrBars() {
        val module = File("src/main/java/com/glut/schedule/ui/pages/CourseTimeStatsScreen.kt")
        val source = (
            if (module.exists()) module
            else File("app/src/main/java/com/glut/schedule/ui/pages/CourseTimeStatsScreen.kt")
        ).readText()

        assertTrue(source.contains("总时长分布"))
        assertTrue(source.contains("Canvas("))
        assertTrue(source.contains("CategoryGrid"))
        assertFalse(source.contains("SummaryCard("))
        assertFalse(source.contains("RankingCard("))
        assertFalse(source.contains("\"完整排行\""))
    }
}
