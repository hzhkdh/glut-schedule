package com.glut.schedule

import com.glut.schedule.data.model.AcademicSemester
import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.SemesterSeason
import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.service.academic.AcademicSemesterImportPayload
import com.glut.schedule.service.academic.AcademicSemesterCalendarEstimator
import com.glut.schedule.service.academic.AcademicSemesterProbePlanner
import com.glut.schedule.service.academic.AcademicSemesterResponseKind
import com.glut.schedule.service.parser.AcademicSemesterCatalogPlan
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AcademicSemesterProbePlannerTest {
    @Test
    fun futureCalendarEstimateUsesPromotedSemesterInsteadOfPreviousCalendar() {
        val next = semester(2025, SemesterSeason.AUTUMN, isCurrent = true)

        val calendar = AcademicSemesterCalendarEstimator.estimate(
            semester = next,
            today = LocalDate.of(2025, 7, 22)
        )

        assertEquals(LocalDate.of(2025, 9, 1), calendar.startMonday)
        assertEquals(LocalDate.of(2026, 1, 18), calendar.endDate)
        assertEquals(1, calendar.currentWeekNumber)
    }

    @Test
    fun nonEmptyProbePromotesOnlyImmediateNextAndReusesPayloadWithoutOldCalendar() {
        val selected = semester(2025, SemesterSeason.SPRING, isCurrent = true)
        val next = semester(2025, SemesterSeason.AUTUMN, isCurrent = false).copy(
            semesterStartDate = LocalDate.of(2025, 2, 24),
            semesterEndDate = LocalDate.of(2025, 7, 6)
        )
        val payload = payload(listOf(course()))

        val decision = AcademicSemesterProbePlanner.decide(
            AcademicSemesterCatalogPlan(listOf(selected), next),
            Result.success(payload)
        )

        assertEquals(listOf(selected.id, next.id), decision.catalog.map { it.id })
        assertEquals(1, decision.catalog.count { it.isCurrent })
        assertEquals(next.id, decision.currentSemester.id)
        assertNull(decision.currentSemester.semesterStartDate)
        assertNull(decision.currentSemester.semesterEndDate)
        assertSame(payload, decision.promotedPayload)
    }

    @Test
    fun failedOrEmptyProbeKeepsPortalSelectedAsOnlyCurrent() {
        val selected = semester(2025, SemesterSeason.SPRING, isCurrent = true)
        val next = semester(2025, SemesterSeason.AUTUMN, isCurrent = false)
        val plan = AcademicSemesterCatalogPlan(listOf(selected), next)

        val failed = AcademicSemesterProbePlanner.decide(
            plan,
            Result.failure(IllegalStateException("登录状态已失效"))
        )
        val empty = AcademicSemesterProbePlanner.decide(plan, Result.success(payload(emptyList())))

        listOf(failed, empty).forEach { decision ->
            assertEquals(listOf(selected.id), decision.catalog.map { it.id })
            assertEquals(selected.id, decision.currentSemester.id)
            assertEquals(1, decision.catalog.count { it.isCurrent })
            assertNull(decision.promotedPayload)
        }
        assertTrue(empty.currentSemester.isCurrent)
    }

    private fun semester(year: Int, season: SemesterSeason, isCurrent: Boolean) = AcademicSemester.create(
        campus = CampusType.GUILIN,
        portalYear = year,
        portalYearId = (year - 1980).toString(),
        season = season,
        portalTermId = if (season == SemesterSeason.SPRING) "1" else "2",
        isCurrent = isCurrent
    )

    private fun payload(courses: List<ScheduleCourse>) = AcademicSemesterImportPayload(
        courses = courses,
        adjustments = emptyList(),
        currcourseHtml = "<table id=\"manualArrangeCourseTable\"></table>",
        timetableHtml = "",
        responseKind = if (courses.isEmpty()) {
            AcademicSemesterResponseKind.VALID_EMPTY_SCHEDULE
        } else {
            AcademicSemesterResponseKind.VALID_NON_EMPTY_SCHEDULE
        }
    )

    private fun course() = ScheduleCourse(
        id = "future",
        title = "未来课程",
        room = "A101",
        teacher = "教师",
        colorHex = "#4477AA",
        occurrences = listOf(CourseOccurrence("future-occ", "future", 1, 1, 2, "1-16周", ""))
    )
}
