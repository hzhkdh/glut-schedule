package com.glut.schedule

import com.glut.schedule.data.model.ExamInfo
import com.glut.schedule.service.greeting.DrawerGreetingContext
import com.glut.schedule.service.greeting.DrawerGreetingPlanner
import com.glut.schedule.service.greeting.GreetingCategory
import com.glut.schedule.service.greeting.builtInGreetingTemplates
import com.glut.schedule.service.holiday.CalendarDayInfo
import com.glut.schedule.service.holiday.CalendarDayKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random

class DrawerGreetingPlannerTest {
    private val templates = builtInGreetingTemplates()

    @Test
    fun unfinishedTodayExamExcludesAllOrdinaryCategories() {
        val now = LocalDateTime.of(2026, 7, 26, 9, 0)
        val planner = DrawerGreetingPlanner(Random(1))
        val result = planner.next(
            context = context(
                now = now,
                name = "张三",
                exams = listOf(exam("高等数学", now.toLocalDate(), "10:00", "12:00"))
            ),
            templates = templates
        )

        assertEquals(GreetingCategory.EXAM_TODAY, result.category)
        assertTrue(result.text.contains("高等数学"))
    }

    @Test
    fun urgentExamStillWinsDuringLateNightAndHoliday() {
        val now = LocalDateTime.of(2026, 10, 1, 1, 0)
        val result = DrawerGreetingPlanner(Random(0)).next(
            context = context(
                now = now,
                exams = listOf(exam("高等数学", now.toLocalDate(), "08:00", "10:00")),
                calendarDay = CalendarDayInfo(CalendarDayKind.HOLIDAY, "国庆节")
            ),
            templates = templates
        )

        assertEquals(GreetingCategory.EXAM_TODAY, result.category)
    }

    @Test
    fun tomorrowExamStillWinsDuringLateNightAndHoliday() {
        val now = LocalDateTime.of(2026, 7, 27, 1, 0)
        val result = DrawerGreetingPlanner(Random(0)).next(
            context = context(
                now = now,
                exams = listOf(exam("大学英语", now.toLocalDate().plusDays(1))),
                calendarDay = CalendarDayInfo(CalendarDayKind.HOLIDAY, "测试节日")
            ),
            templates = templates
        )

        assertEquals(GreetingCategory.EXAM_TOMORROW, result.category)
    }

    @Test
    fun lateNightIsExclusiveWhenThereIsNoUrgentExam() {
        val now = LocalDateTime.of(2026, 7, 26, 4, 59)
        val result = DrawerGreetingPlanner(Random(0)).next(
            context = context(now),
            templates = templates
        )

        assertEquals(GreetingCategory.LATE_NIGHT, result.category)
        assertTrue(result.text in templates.forCategory(GreetingCategory.LATE_NIGHT))
    }

    @Test
    fun namedHolidayWinsOverWeekendAndRendersItsName() {
        val now = LocalDateTime.of(2026, 10, 3, 12, 0)
        val result = DrawerGreetingPlanner(Random(0)).next(
            context = context(
                now = now,
                calendarDay = CalendarDayInfo(CalendarDayKind.HOLIDAY, "国庆节")
            ),
            templates = templates
        )

        assertEquals(GreetingCategory.HOLIDAY, result.category)
        assertTrue(result.text.contains("国庆节"))
    }

    @Test
    fun adjustedWorkdaySuppressesWeekendButUnknownCalendarFallsBackToWeekday() {
        val saturday = LocalDateTime.of(2026, 8, 1, 12, 0)
        val adjusted = DrawerGreetingPlanner(Random(0)).eligibleCategories(
            context(
                now = saturday,
                calendarDay = CalendarDayInfo(CalendarDayKind.ADJUSTED_WORKDAY)
            ),
            templates
        )
        val unknown = DrawerGreetingPlanner(Random(0)).eligibleCategories(
            context(
                now = saturday,
                calendarDay = CalendarDayInfo(CalendarDayKind.UNKNOWN)
            ),
            templates
        )

        assertFalse(GreetingCategory.WEEKEND in adjusted)
        assertEquals(listOf(GreetingCategory.WEEKEND), unknown)
    }

    @Test
    fun endedTodayExamIsSkippedAndTomorrowExamWins() {
        val now = LocalDateTime.of(2026, 7, 26, 18, 0)
        val planner = DrawerGreetingPlanner(Random(2))
        val result = planner.next(
            context = context(
                now = now,
                exams = listOf(
                    exam("大学英语", now.toLocalDate(), "08:00", "10:00"),
                    exam("数据结构", now.toLocalDate().plusDays(1), "09:00", "11:00")
                )
            ),
            templates = templates
        )

        assertEquals(GreetingCategory.EXAM_TOMORROW, result.category)
        assertTrue(result.text.contains("数据结构"))
    }

    @Test
    fun examBetweenTwoAndSevenDaysCanEnterOrdinaryPoolButDayEightCannot() {
        val now = LocalDateTime.of(2026, 7, 27, 18, 0)
        val near = DrawerGreetingPlanner(Random(0)).eligibleCategories(
            context(now, exams = listOf(exam("近代史", now.toLocalDate().plusDays(7)))),
            templates
        )
        val far = DrawerGreetingPlanner(Random(0)).eligibleCategories(
            context(now, exams = listOf(exam("近代史", now.toLocalDate().plusDays(8)))),
            templates
        )

        assertTrue(GreetingCategory.EXAM_UPCOMING in near)
        assertFalse(GreetingCategory.EXAM_UPCOMING in far)
    }

    @Test
    fun semesterUsesEndingCategoryOnlyWithinLastThirtyDays() {
        val now = LocalDateTime.of(2026, 7, 1, 9, 0)
        val planner = DrawerGreetingPlanner(Random(0))
        val ordinary = planner.eligibleCategories(
            context(now, semesterEnd = now.toLocalDate().plusDays(31)),
            templates
        )
        val ending = planner.eligibleCategories(
            context(now, semesterEnd = now.toLocalDate().plusDays(30)),
            templates
        )

        assertTrue(GreetingCategory.SEMESTER_WEEK in ordinary)
        assertFalse(GreetingCategory.SEMESTER_ENDING in ordinary)
        assertTrue(GreetingCategory.SEMESTER_ENDING in ending)
    }

    @Test
    fun consecutiveOpenAvoidsSameRenderedSentenceWhenAlternativeExists() {
        val now = LocalDateTime.of(2026, 7, 26, 8, 0)
        val planner = DrawerGreetingPlanner(Random(3))
        val first = planner.next(context(now, name = "张三"), templates)
        val second = planner.next(context(now, name = "张三"), templates, previousText = first.text)

        assertNotEquals(first.text, second.text)
    }

    @Test
    fun dynamicGreetingAnimatesOnEveryOpen() {
        val planner = DrawerGreetingPlanner(Random(0))
        val greetingContext = context(LocalDateTime.of(2026, 7, 26, 8, 0), name = "张三")
        val first = planner.next(greetingContext, templates)
        val second = planner.next(greetingContext, templates, previousText = first.text)

        assertTrue(first.animate)
        assertTrue(second.animate)
    }

    @Test
    fun ordinaryAccessibilityServiceDoesNotDisableTypewriter() {
        assertTrue(
            shouldRunDrawerGreetingAnimation(
                requested = true,
                animatorsEnabled = true,
                touchExplorationEnabled = false
            )
        )
        assertFalse(
            shouldRunDrawerGreetingAnimation(
                requested = true,
                animatorsEnabled = true,
                touchExplorationEnabled = true
            )
        )
    }

    @Test
    fun timePeriodBoundariesAreStable() {
        val planner = DrawerGreetingPlanner(Random(0))

        assertEquals("晚上", planner.periodFor(LocalDateTime.of(2026, 7, 26, 4, 59)))
        assertEquals("清晨", planner.periodFor(LocalDateTime.of(2026, 7, 26, 5, 0)))
        assertEquals("清晨", planner.periodFor(LocalDateTime.of(2026, 7, 26, 7, 59)))
        assertEquals("早上", planner.periodFor(LocalDateTime.of(2026, 7, 26, 8, 0)))
        assertEquals("中午", planner.periodFor(LocalDateTime.of(2026, 7, 26, 11, 0)))
        assertEquals("下午", planner.periodFor(LocalDateTime.of(2026, 7, 26, 14, 0)))
        assertEquals("晚上", planner.periodFor(LocalDateTime.of(2026, 7, 26, 18, 0)))
        assertEquals("晚上", planner.periodFor(LocalDateTime.of(2026, 7, 26, 23, 59)))
    }

    @Test
    fun missingPersonalAndSemesterDataFallsBackToBrandSlogan() {
        val now = LocalDateTime.of(2026, 7, 27, 9, 0)
        val result = DrawerGreetingPlanner(Random(0)).next(
            context = DrawerGreetingContext(
                studentName = "",
                exams = emptyList(),
                now = now,
                semesterStart = null,
                semesterEnd = null
            ),
            templates = templates
        )

        assertEquals(DrawerGreetingPlanner.STATIC_SLOGAN, result.text)
        assertNull(result.category)
        assertFalse(result.animate)
    }

    private fun context(
        now: LocalDateTime,
        name: String = "",
        exams: List<ExamInfo> = emptyList(),
        semesterEnd: LocalDate = now.toLocalDate().plusDays(60),
        calendarDay: CalendarDayInfo = CalendarDayInfo(CalendarDayKind.UNKNOWN)
    ) = DrawerGreetingContext(
        studentName = name,
        exams = exams,
        now = now,
        semesterStart = now.toLocalDate().minusDays(28),
        semesterEnd = semesterEnd,
        calendarDay = calendarDay
    )

    private fun exam(
        name: String,
        date: LocalDate,
        start: String = "09:00",
        end: String = "11:00"
    ) = ExamInfo(
        id = "$name-$date",
        courseName = name,
        examDate = date,
        startTime = start,
        endTime = end,
        location = "教一楼",
        seatNumber = "",
        examType = "期末考试",
        note = ""
    )
}
