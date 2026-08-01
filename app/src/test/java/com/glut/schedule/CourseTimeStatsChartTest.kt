package com.glut.schedule

import com.glut.schedule.data.model.CourseTimeStatsItem
import com.glut.schedule.ui.pages.buildCourseTimeStatsChartCenter
import com.glut.schedule.ui.pages.findCourseTimeDonutSegment
import com.glut.schedule.ui.pages.formatStatsMinutes
import com.glut.schedule.ui.pages.formatStatsMinutesAccessible
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CourseTimeStatsChartTest {
    @Test
    fun donutHitTestFindsLargeAndTinySegments() {
        val shares = listOf(0.25, 0.25, 0.49, 0.01)

        assertEquals(
            0,
            findCourseTimeDonutSegment(
                tapX = 153f,
                tapY = 47f,
                width = 200f,
                height = 200f,
                innerRadius = 48f,
                outerRadius = 92f,
                shares = shares
            )
        )
        assertEquals(
            1,
            findCourseTimeDonutSegment(
                tapX = 153f,
                tapY = 153f,
                width = 200f,
                height = 200f,
                innerRadius = 48f,
                outerRadius = 92f,
                shares = shares
            )
        )
        assertEquals(
            3,
            findCourseTimeDonutSegment(
                tapX = 97.6f,
                tapY = 25f,
                width = 200f,
                height = 200f,
                innerRadius = 48f,
                outerRadius = 92f,
                shares = shares
            )
        )
    }

    @Test
    fun donutHitTestRejectsCenterAndOutside() {
        val shares = listOf(0.6, 0.4)

        assertNull(
            findCourseTimeDonutSegment(
                tapX = 100f,
                tapY = 100f,
                width = 200f,
                height = 200f,
                innerRadius = 48f,
                outerRadius = 92f,
                shares = shares
            )
        )
        assertNull(
            findCourseTimeDonutSegment(
                tapX = 199f,
                tapY = 100f,
                width = 200f,
                height = 200f,
                innerRadius = 48f,
                outerRadius = 92f,
                shares = shares
            )
        )
    }

    @Test
    fun chartCenterSwitchesBetweenTotalAndSelectedCategory() {
        val selected = CourseTimeStatsItem(
            key = "teacher",
            label = "刘月红",
            minutes = 2_160,
            share = 0.134,
            colorHex = "#EA580C"
        )

        assertEquals(
            Triple("总计划课时", "268h30m", "100%"),
            buildCourseTimeStatsChartCenter(16_110, null).let {
                Triple(it.label, it.timeText, it.shareText)
            }
        )
        assertEquals(
            Triple("刘月红", "36h", "13.4%"),
            buildCourseTimeStatsChartCenter(16_110, selected).let {
                Triple(it.label, it.timeText, it.shareText)
            }
        )
    }

    @Test
    fun visibleTimeIsCompactWhileAccessibilityKeepsFullChineseUnits() {
        assertEquals("30m", formatStatsMinutes(30))
        assertEquals("24h", formatStatsMinutes(1_440))
        assertEquals("19h30m", formatStatsMinutes(1_170))
        assertEquals("30 分钟", formatStatsMinutesAccessible(30))
        assertEquals("24 小时", formatStatsMinutesAccessible(1_440))
        assertEquals("19 小时 30 分", formatStatsMinutesAccessible(1_170))
    }
}
