package com.glut.schedule

import com.glut.schedule.data.model.AcademicSemester
import com.glut.schedule.data.model.SemesterSeason
import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.service.academic.AcademicSemesterViewPlanner
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class AcademicSemesterViewPlannerTest {
    @Test
    fun historicalViewStartsAtWeekOne() {
        assertEquals(
            1,
            AcademicSemesterViewPlanner.weekFor(
                semester = semester(isCurrent = false),
                today = LocalDate.of(2026, 4, 1),
                fallbackStart = LocalDate.of(2026, 3, 2),
                fallbackEnd = LocalDate.of(2026, 7, 12)
            )
        )
    }

    @Test
    fun currentViewUsesTargetSemesterCalendar() {
        val current = semester(isCurrent = true).copy(
            semesterStartDate = LocalDate.of(2026, 3, 2),
            semesterEndDate = LocalDate.of(2026, 7, 12)
        )

        assertEquals(
            5,
            AcademicSemesterViewPlanner.weekFor(
                semester = current,
                today = LocalDate.of(2026, 4, 1),
                fallbackStart = LocalDate.of(2025, 9, 1),
                fallbackEnd = LocalDate.of(2026, 1, 18)
            )
        )
    }

    private fun semester(isCurrent: Boolean) = AcademicSemester.create(
        CampusType.GUILIN, 2026, "46", SemesterSeason.SPRING, "1", isCurrent
    )
}
