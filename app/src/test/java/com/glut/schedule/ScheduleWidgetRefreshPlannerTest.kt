package com.glut.schedule

import com.glut.schedule.widget.ScheduleWidgetRefreshPlanner
import com.glut.schedule.widget.WidgetCourseItem
import com.glut.schedule.widget.WidgetScheduleSnapshot
import com.glut.schedule.widget.WidgetScheduleStatus
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleWidgetRefreshPlannerTest {
    private val date = LocalDate.of(2026, 3, 16)

    @Test
    fun beforeCourseRefreshesAtItsStart() {
        val now = date.atTime(8, 0)

        assertEquals(
            date.atTime(8, 30),
            ScheduleWidgetRefreshPlanner.nextRefreshAt(now, snapshot(course("08:30", "10:05")))
        )
    }

    @Test
    fun duringCourseRefreshesAtItsEnd() {
        val now = date.atTime(9, 0)

        assertEquals(
            date.atTime(10, 5),
            ScheduleWidgetRefreshPlanner.nextRefreshAt(now, snapshot(course("08:30", "10:05")))
        )
    }

    @Test
    fun afterFinalCourseRefreshesAtMidnight() {
        val now = date.atTime(18, 0)

        assertEquals(
            date.plusDays(1).atStartOfDay(),
            ScheduleWidgetRefreshPlanner.nextRefreshAt(now, snapshot(course("08:30", "10:05")))
        )
    }

    @Test
    fun missingOrMalformedTimesStillRefreshAtMidnight() {
        val now = date.atTime(9, 0)
        val invalidCourses = listOf(course("", ""), course("not-a-time", "25:90"))

        assertEquals(
            date.plusDays(1).atStartOfDay(),
            ScheduleWidgetRefreshPlanner.nextRefreshAt(now, snapshot(*invalidCourses.toTypedArray()))
        )
    }

    private fun snapshot(vararg courses: WidgetCourseItem) = WidgetScheduleSnapshot(
        status = WidgetScheduleStatus.READY,
        today = date,
        currentWeek = 2,
        todayCourses = courses.toList()
    )

    private fun course(start: String, end: String) = WidgetCourseItem(
        date = date,
        title = "程序设计实践",
        room = "05201",
        teacher = "",
        startSection = 1,
        endSection = 2,
        startTime = start,
        endTime = end,
        colorHex = "#FF6B93"
    )
}
