package com.glut.schedule

import com.glut.schedule.data.model.ClassPeriod
import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.widget.ScheduleWidgetSnapshotBuilder
import com.glut.schedule.widget.WidgetScheduleStatus
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleWidgetSnapshotBuilderTest {
    private val semesterStart = LocalDate.of(2026, 3, 9)
    private val semesterEnd = LocalDate.of(2026, 7, 19)
    private val periods = listOf(
        ClassPeriod(1, "08:30", "09:15"),
        ClassPeriod(2, "09:20", "10:05"),
        ClassPeriod(3, "10:25", "11:10"),
        ClassPeriod(4, "11:15", "12:00"),
        ClassPeriod(7, "14:30", "15:15"),
        ClassPeriod(8, "15:20", "16:05")
    )

    @Test
    fun emptyCourseDataIsReportedAsNotImported() {
        val snapshot = build(LocalDateTime.of(2026, 3, 16, 8, 0), emptyList())

        assertEquals(WidgetScheduleStatus.NO_DATA, snapshot.status)
        assertEquals(emptyList<Any>(), snapshot.todayCourses)
        assertNull(snapshot.nextCourse)
    }

    @Test
    fun dateOutsideSemesterIsReportedSeparatelyFromNoCourses() {
        val snapshot = build(
            now = LocalDateTime.of(2026, 8, 3, 8, 0),
            courses = listOf(course("math", occurrence("math", day = 1, weeks = "1-19周")))
        )

        assertEquals(WidgetScheduleStatus.OUTSIDE_SEMESTER, snapshot.status)
    }

    @Test
    fun dateBeforeSemesterIsReportedAsNotStarted() {
        val snapshot = build(
            now = LocalDateTime.of(2026, 3, 2, 8, 0),
            courses = listOf(course("math", occurrence("math", day = 1, weeks = "1-19周")))
        )

        assertEquals(WidgetScheduleStatus.BEFORE_SEMESTER, snapshot.status)
    }

    @Test
    fun todayAndTomorrowCoursesAreFilteredByWeekAndSortedByStartTime() {
        val now = LocalDateTime.of(2026, 3, 16, 8, 0) // Week 2, Monday.
        val courses = listOf(
            course("afternoon", occurrence("afternoon", day = 1, start = 7, end = 8, weeks = "双周")),
            course("morning", occurrence("morning", day = 1, start = 1, end = 2, weeks = "1-19周")),
            course("tomorrow", occurrence("tomorrow", day = 2, start = 3, end = 4, weeks = "2周")),
            course("odd-only", occurrence("odd-only", day = 1, start = 3, end = 4, weeks = "单周"))
        )

        val snapshot = build(now, courses)

        assertEquals(WidgetScheduleStatus.READY, snapshot.status)
        assertEquals(2, snapshot.currentWeek)
        assertEquals(listOf("morning", "afternoon"), snapshot.todayCourses.map { it.title })
        assertEquals(listOf("tomorrow"), snapshot.tomorrowCourses.map { it.title })
        assertEquals("08:30", snapshot.todayCourses.first().startTime)
        assertEquals("10:05", snapshot.todayCourses.first().endTime)
        assertEquals("morning", snapshot.nextCourse?.title)
    }

    @Test
    fun suppliedCustomClassPeriodsControlWidgetCourseTimes() {
        val customPeriods = periods.map {
            when (it.section) {
                1 -> it.copy(startsAt = "07:45", endsAt = "08:30")
                2 -> it.copy(startsAt = "08:35", endsAt = "09:20")
                else -> it
            }
        }
        val snapshot = ScheduleWidgetSnapshotBuilder.build(
            now = LocalDateTime.of(2026, 3, 16, 7, 0),
            courses = listOf(course("morning", occurrence("morning", day = 1, weeks = "1-19周"))),
            classPeriods = customPeriods,
            semesterStartMonday = semesterStart,
            semesterEndDate = semesterEnd
        )

        assertEquals("07:45", snapshot.todayCourses.single().startTime)
        assertEquals("09:20", snapshot.todayCourses.single().endTime)
        assertEquals("07:45", snapshot.nextCourse?.startTime)
    }

    @Test
    fun noCourseTodayIncludesNearestCourseWithinSevenDays() {
        val now = LocalDateTime.of(2026, 3, 18, 12, 0) // Wednesday of week 2.
        val fridayCourse = course(
            "friday",
            occurrence("friday", day = 5, start = 3, end = 4, weeks = "1-19周")
        )

        val snapshot = build(now, listOf(fridayCourse))

        assertEquals(WidgetScheduleStatus.NO_COURSES, snapshot.status)
        assertEquals(LocalDate.of(2026, 3, 20), snapshot.nextCourse?.date)
        assertEquals("friday", snapshot.nextCourse?.title)
    }

    @Test
    fun coursesBeyondSevenDaysAreNotAdvertisedAsNextCourse() {
        val now = LocalDateTime.of(2026, 3, 16, 12, 0)
        val nextMondayOnly = course(
            "next-monday",
            occurrence("next-monday", day = 1, weeks = "3周")
        )

        val snapshot = ScheduleWidgetSnapshotBuilder.build(
            now = now,
            courses = listOf(nextMondayOnly),
            classPeriods = periods,
            semesterStartMonday = semesterStart,
            semesterEndDate = semesterEnd,
            futureSearchDays = 6
        )

        assertNull(snapshot.nextCourse)
    }

    private fun build(now: LocalDateTime, courses: List<ScheduleCourse>) =
        ScheduleWidgetSnapshotBuilder.build(
            now = now,
            courses = courses,
            classPeriods = periods,
            semesterStartMonday = semesterStart,
            semesterEndDate = semesterEnd
        )

    private fun course(title: String, occurrence: CourseOccurrence) = ScheduleCourse(
        id = title,
        title = title,
        room = "理科楼 210",
        teacher = "张老师",
        colorHex = "#3F7DF6",
        occurrences = listOf(occurrence)
    )

    private fun occurrence(
        courseId: String,
        day: Int,
        start: Int = 1,
        end: Int = 2,
        weeks: String
    ) = CourseOccurrence(
        id = "$courseId-$day-$start",
        courseId = courseId,
        dayOfWeek = day,
        startSection = start,
        endSection = end,
        weekText = weeks,
        note = ""
    )
}
