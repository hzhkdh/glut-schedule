package com.glut.schedule

import com.glut.schedule.data.model.ClassPeriod
import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.ManualDayCopyRule
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.widget.ScheduleWidgetSnapshotBuilder
import com.glut.schedule.widget.WidgetScheduleStatus
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
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
        assertEquals(snapshot.todayCourses.size, snapshot.todayCourses.map { it.stableId }.distinct().size)
        assertEquals(snapshot.todayCourses.map { it.stableId }, build(now, courses).todayCourses.map { it.stableId })
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
    }

    @Test
    fun noCourseTodayDoesNotBuildAnUnrelatedCourseReminder() {
        val now = LocalDateTime.of(2026, 3, 18, 12, 0) // Wednesday of week 2.
        val fridayCourse = course(
            "friday",
            occurrence("friday", day = 5, start = 3, end = 4, weeks = "1-19周")
        )

        val snapshot = build(now, listOf(fridayCourse))

        assertEquals(WidgetScheduleStatus.NO_COURSES, snapshot.status)
    }

    @Test
    fun finishedTodayCoursesAreRemovedWhileLaterCoursesRemain() {
        val now = LocalDateTime.of(2026, 3, 16, 13, 3)
        val morning = course("morning", occurrence("morning", day = 1, start = 3, end = 4, weeks = "1-19周"))
        val afternoon = course("afternoon", occurrence("afternoon", day = 1, start = 7, end = 8, weeks = "1-19周"))

        val snapshot = build(now, listOf(morning, afternoon))

        assertEquals(listOf("afternoon"), snapshot.todayCourses.map { it.title })
    }

    // 2026-03-16 是学期第 2 周周一，其周三为 2026-03-18。
    @Test
    fun manualDayCopyCoursesAppearOnTheTargetDayInTheWidget() {
        val now = LocalDateTime.of(2026, 3, 18, 8, 0) // 第 2 周周三
        val mondayCourse = course(
            "monday",
            occurrence("monday", day = 1, start = 1, end = 2, weeks = "1-19周")
        )

        val snapshot = build(
            now = now,
            courses = listOf(mondayCourse),
            manualDayCopies = listOf(
                ManualDayCopyRule(
                    sourceDate = LocalDate.of(2026, 3, 16),
                    targetDate = LocalDate.of(2026, 3, 18)
                )
            )
        )

        // 首页把「周一的课整天复制到周三」，小组件必须跟着显示，否则首页有的课在桌面凭空消失。
        assertEquals(listOf("monday"), snapshot.todayCourses.map { it.title })
    }

    @Test
    fun manualDayCopyDoesNotLeakIntoOtherDaysOrOtherWeeks() {
        val rule = ManualDayCopyRule(
            sourceDate = LocalDate.of(2026, 3, 16),
            targetDate = LocalDate.of(2026, 3, 18)
        )
        val mondayCourse = course(
            "monday",
            occurrence("monday", day = 1, start = 1, end = 2, weeks = "1-19周")
        )

        // 同日但规则未命中（周四）
        assertEquals(
            emptyList<String>(),
            build(
                now = LocalDateTime.of(2026, 3, 19, 8, 0),
                courses = listOf(mondayCourse),
                manualDayCopies = listOf(rule)
            ).todayCourses.map { it.title }
        )
        // 下一周的周三：副本只在目标日所在的那一周生成
        assertEquals(
            emptyList<String>(),
            build(
                now = LocalDateTime.of(2026, 3, 25, 8, 0),
                courses = listOf(mondayCourse),
                manualDayCopies = listOf(rule)
            ).todayCourses.map { it.title }
        )
    }

    @Test
    fun manualDayCopyCoexistsWithExistingCourseAndKeepsStableIdsDistinct() {
        val now = LocalDateTime.of(2026, 3, 18, 8, 0)
        val mondayCourse = course(
            "monday",
            occurrence("monday", day = 1, start = 1, end = 2, weeks = "1-19周")
        )
        val wednesdayCourse = course(
            "wednesday",
            occurrence("wednesday", day = 3, start = 3, end = 4, weeks = "1-19周")
        )

        val snapshot = build(
            now = now,
            courses = listOf(mondayCourse, wednesdayCourse),
            manualDayCopies = listOf(
                ManualDayCopyRule(
                    sourceDate = LocalDate.of(2026, 3, 16),
                    targetDate = LocalDate.of(2026, 3, 18)
                )
            )
        )

        // 目标日原有课程保留，副本并列出现，按节次排序。
        assertEquals(listOf("monday", "wednesday"), snapshot.todayCourses.map { it.title })
        assertEquals(
            snapshot.todayCourses.size,
            snapshot.todayCourses.map { it.stableId }.distinct().size
        )
    }

    private fun build(
        now: LocalDateTime,
        courses: List<ScheduleCourse>,
        manualDayCopies: List<ManualDayCopyRule> = emptyList()
    ) =
        ScheduleWidgetSnapshotBuilder.build(
            now = now,
            courses = courses,
            classPeriods = periods,
            semesterStartMonday = semesterStart,
            semesterEndDate = semesterEnd,
            manualDayCopies = manualDayCopies
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
