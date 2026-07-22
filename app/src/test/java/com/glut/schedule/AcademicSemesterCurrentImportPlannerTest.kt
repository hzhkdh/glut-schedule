package com.glut.schedule

import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.service.academic.AcademicSemesterCurrentImportPlanner
import com.glut.schedule.service.academic.AcademicSemesterResponseKind
import com.glut.schedule.service.parser.AcademicScheduleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcademicSemesterCurrentImportPlannerTest {
    @Test
    fun invalidSuccessfulHtmlIsRejectedBeforeParserAndCannotReplaceCache() {
        val parser = CountingParser(listOf(course()))

        val decision = AcademicSemesterCurrentImportPlanner.parse(
            "<html><h1>系统公告</h1></html>",
            parser
        )

        assertEquals(AcademicSemesterResponseKind.INVALID_STRUCTURE, decision.responseKind)
        assertFalse(decision.canReplace)
        assertEquals(0, parser.calls)
    }

    @Test
    fun validEmptyScheduleCanExplicitlyReplaceWithEmptyCourses() {
        val decision = AcademicSemesterCurrentImportPlanner.parse(
            "<table id=\"manualArrangeCourseTable\"></table>",
            CountingParser(emptyList())
        )

        assertEquals(AcademicSemesterResponseKind.VALID_EMPTY_SCHEDULE, decision.responseKind)
        assertTrue(decision.canReplace)
        assertTrue(decision.courses.isEmpty())
    }

    private class CountingParser(private val result: List<ScheduleCourse>) : AcademicScheduleParser {
        var calls = 0
        override fun parsePersonalSchedule(html: String): List<ScheduleCourse> {
            calls++
            return result
        }
    }

    private fun course() = ScheduleCourse(
        id = "current",
        title = "当前课程",
        room = "A101",
        teacher = "教师",
        colorHex = "#4477AA",
        occurrences = listOf(CourseOccurrence("current-occ", "current", 1, 1, 2, "1-16周", ""))
    )
}
