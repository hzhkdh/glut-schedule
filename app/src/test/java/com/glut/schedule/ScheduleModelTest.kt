package com.glut.schedule

import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.ScheduleWeek
import com.glut.schedule.data.model.academicWeekForDate
import com.glut.schedule.data.model.historicalAcademicMaxWeek
import com.glut.schedule.data.model.academicMaxWeekForCalendar
import com.glut.schedule.data.model.academicMaxWeekForSemester
import com.glut.schedule.data.model.visibleDayCount
import com.glut.schedule.data.model.defaultClassPeriods
import com.glut.schedule.data.model.scheduleWeekForNumber
import com.glut.schedule.data.model.clampAcademicWeek
import com.glut.schedule.data.model.isActiveInWeek
import com.glut.schedule.data.settings.CampusType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ScheduleModelTest {
    @Test
    fun historicalMaximumWeekPrefersPortalDropdownOverCourseOccurrences() {
        val course = ScheduleCourse(
            id = "internship",
            title = "实习",
            room = "",
            teacher = "教师",
            colorHex = "#4477AA",
            occurrences = listOf(
                CourseOccurrence("occ", "internship", 1, 1, 2, "1-16周", "")
            )
        )

        assertEquals(19, historicalAcademicMaxWeek(portalMaxWeek = 19, courses = listOf(course)))
        assertEquals(16, historicalAcademicMaxWeek(portalMaxWeek = null, courses = listOf(course)))
    }

    @Test
    fun historicalMaximumWeekIgnoresPersistedCalendarDates() {
        val course = ScheduleCourse(
            id = "internship",
            title = "实习",
            room = "",
            teacher = "教师",
            colorHex = "#4477AA",
            occurrences = listOf(
                CourseOccurrence("occ", "internship", 1, 1, 2, "1-16周", "")
            )
        )

        assertEquals(
            19,
            academicMaxWeekForSemester(
                isCurrentSemester = false,
                portalMaxWeek = 19,
                courses = listOf(course),
                semesterStartMonday = LocalDate.of(2024, 9, 2),
                semesterEndDate = LocalDate.of(2024, 12, 22)
            )
        )
    }
    @Test
    fun scheduleWeek_movesBySevenDaysAndUpdatesWeekNumber() {
        val current = ScheduleWeek(number = 9, monday = LocalDate.of(2026, 5, 4))

        assertEquals(8, current.previous().number)
        assertEquals(LocalDate.of(2026, 4, 27), current.previous().monday)
        assertEquals(10, current.next().number)
        assertEquals(LocalDate.of(2026, 5, 11), current.next().monday)
    }

    @Test
    fun defaultClassPeriodsCoverFourteenSections() {
        val periods = defaultClassPeriods()

        assertEquals(14, periods.size)
        assertEquals("08:30", periods.first().startsAt)
        assertEquals("21:45", periods.last().endsAt)
    }

    @Test
    fun defaultClassPeriodsUseTheSelectedCampusTemplate() {
        val guilin = defaultClassPeriods(CampusType.GUILIN)
        val nanning = defaultClassPeriods(CampusType.NANNING)

        assertEquals((1..14).toList(), guilin.map { it.section })
        assertEquals("08:30", guilin.first().startsAt)
        assertEquals((1..11).toList(), nanning.map { it.section })
        assertEquals("08:40", nanning.first().startsAt)
    }

    @Test
    fun occurrenceHeightUsesSectionSpan() {
        val occurrence = CourseOccurrence(
            id = "logic-1",
            courseId = "logic",
            dayOfWeek = 2,
            startSection = 1,
            endSection = 2,
            weekText = "1-16周",
            note = ""
        )

        assertEquals(2, occurrence.sectionSpan)
    }

    @Test
    fun academicWeekIsClampedToNormalSemesterRange() {
        assertEquals(1, clampAcademicWeek(-1))
        assertEquals(1, clampAcademicWeek(0))
        assertEquals(1, clampAcademicWeek(1))
        assertEquals(9, clampAcademicWeek(9))
        assertEquals(22, clampAcademicWeek(22))
        assertEquals(22, clampAcademicWeek(23))
        assertEquals(22, clampAcademicWeek(100))
    }

    @Test
    fun academicWeekUsesConfiguredSemesterStartMonday() {
        val semesterStartMonday = LocalDate.of(2026, 3, 9)

        assertEquals(1, academicWeekForDate(LocalDate.of(2026, 3, 9), semesterStartMonday))
        assertEquals(1, academicWeekForDate(LocalDate.of(2026, 3, 15), semesterStartMonday))
        assertEquals(2, academicWeekForDate(LocalDate.of(2026, 3, 16), semesterStartMonday))
        assertEquals(9, academicWeekForDate(LocalDate.of(2026, 5, 5), semesterStartMonday))
    }

    @Test
    fun academicMaxWeekUsesCalendarEndDate() {
        val semesterStartMonday = LocalDate.of(2026, 3, 9)
        val semesterEndDate = LocalDate.of(2026, 7, 19)

        assertEquals(19, academicMaxWeekForCalendar(semesterStartMonday, semesterEndDate))
        assertEquals(19, academicWeekForDate(LocalDate.of(2026, 7, 27), semesterStartMonday, maxWeek = 19))
    }

    @Test
    fun scheduleWeekForNumberUsesConfiguredSemesterStartMonday() {
        val semesterStartMonday = LocalDate.of(2026, 3, 9)

        assertEquals(LocalDate.of(2026, 3, 9), scheduleWeekForNumber(1, semesterStartMonday).monday)
        assertEquals(LocalDate.of(2026, 5, 4), scheduleWeekForNumber(9, semesterStartMonday).monday)
    }

    @Test
    fun visibleDayCountDefaultsToWeekdaysOnlyUnlessWeekendIsEnabled() {
        assertEquals(5, visibleDayCount(showWeekend = false))
        assertEquals(7, visibleDayCount(showWeekend = true))
    }

    @Test
    fun occurrenceActiveWeekParsesRangesSinglesAndOddEvenWeeks() {
        assertTrue(occurrenceWithWeek("1-12周").isActiveInWeek(9))
        assertTrue(!occurrenceWithWeek("1-12周").isActiveInWeek(13))
        assertTrue(occurrenceWithWeek("第16周").isActiveInWeek(16))
        assertTrue(!occurrenceWithWeek("第16周").isActiveInWeek(15))
        assertTrue(occurrenceWithWeek("3-12,16-17").isActiveInWeek(16))
        assertTrue(!occurrenceWithWeek("3-12,16-17").isActiveInWeek(13))
        assertTrue(occurrenceWithWeek("5-13单周").isActiveInWeek(9))
        assertTrue(!occurrenceWithWeek("5-13单周").isActiveInWeek(10))
        assertTrue(occurrenceWithWeek("6-14双周").isActiveInWeek(10))
        assertTrue(!occurrenceWithWeek("6-14双周").isActiveInWeek(11))
        assertTrue(occurrenceWithWeek("1,4").isActiveInWeek(4))
        assertTrue(!occurrenceWithWeek("1,4").isActiveInWeek(2))
    }

    @Test
    fun occurrenceActiveWeekAppliesParityToEachSegmentOnly() {
        val occurrence = occurrenceWithWeek("6-14双,16-18")

        assertTrue(occurrence.isActiveInWeek(6))
        assertTrue(!occurrence.isActiveInWeek(7))
        assertTrue(occurrence.isActiveInWeek(14))
        assertTrue(occurrence.isActiveInWeek(16))
        assertTrue(occurrence.isActiveInWeek(17))
        assertTrue(occurrence.isActiveInWeek(18))
        assertTrue(!occurrence.isActiveInWeek(19))
    }

    @Test
    fun occurrenceActiveWeekAcceptsChineseRangeSeparators() {
        assertTrue(occurrenceWithWeek("6－14双周").isActiveInWeek(10))
        assertTrue(occurrenceWithWeek("16—18周").isActiveInWeek(17))
    }

    private fun occurrenceWithWeek(weekText: String): CourseOccurrence {
        return CourseOccurrence(
            id = "occ-$weekText",
            courseId = "course-$weekText",
            dayOfWeek = 1,
            startSection = 1,
            endSection = 2,
            weekText = weekText,
            note = ""
        )
    }
}
