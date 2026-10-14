package com.glut.schedule

import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.AutomaticScheduleDisplay
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.automaticScheduleDisplay
import com.glut.schedule.data.settings.CampusType
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleAutoDisplayTest {
    @Test
    fun guilinNoonAndWeekendCoursesEnableTheirCorrespondingAreas() {
        val courses = listOf(
            course(day = 2, start = 5, end = 6),
            course(day = 6, start = 9, end = 10)
        )

        assertEquals(
            AutomaticScheduleDisplay(showWeekend = true, showNoon = true),
            automaticScheduleDisplay(courses, CampusType.GUILIN)
        )
    }

    @Test
    fun nanningAndGraduateRawFifthAndSixthSectionsAreNotNoon() {
        val courses = listOf(course(day = 3, start = 5, end = 6))

        assertEquals(AutomaticScheduleDisplay(), automaticScheduleDisplay(courses, CampusType.NANNING))
        assertEquals(AutomaticScheduleDisplay(), automaticScheduleDisplay(courses, CampusType.GRADUATE))
    }

    @Test
    fun crossNoonRangeAndSundayCourseAreDetected() {
        val courses = listOf(
            course(day = 1, start = 4, end = 7),
            course(day = 7, start = 1, end = 2)
        )

        assertEquals(
            AutomaticScheduleDisplay(showWeekend = true, showNoon = true),
            automaticScheduleDisplay(courses, CampusType.GUILIN)
        )
    }

    private fun course(day: Int, start: Int, end: Int): ScheduleCourse = ScheduleCourse(
        id = "course-$day-$start-$end",
        title = "测试课程",
        room = "",
        teacher = "",
        colorHex = "#000000",
        occurrences = listOf(
            CourseOccurrence(
                id = "occurrence-$day-$start-$end",
                courseId = "course-$day-$start-$end",
                dayOfWeek = day,
                startSection = start,
                endSection = end,
                weekText = "1-16",
                note = ""
            )
        )
    )
}
