package com.glut.schedule.service.academic

import com.glut.schedule.data.model.AcademicSemester
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.SemesterSeason
import com.glut.schedule.data.model.academicMaxWeekForCalendar
import com.glut.schedule.data.model.academicWeekForDate
import com.glut.schedule.data.model.defaultSemesterEndDate
import com.glut.schedule.service.parser.AcademicScheduleParser
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

data class AcademicSemesterCalendarEstimate(
    val startMonday: LocalDate,
    val endDate: LocalDate,
    val currentWeekNumber: Int
)

enum class AcademicSemesterCalendarSource {
    PORTAL,
    WEEKLY,
    ESTIMATED
}

data class ResolvedAcademicSemesterCalendar(
    val startMonday: LocalDate,
    val endDate: LocalDate,
    val currentWeekNumber: Int,
    val source: AcademicSemesterCalendarSource
)

object AcademicSemesterCalendarResolver {
    fun resolve(
        semester: AcademicSemester,
        today: LocalDate,
        directCalendar: ApiProbeService.AcademicCalendar?,
        weeklyStartMonday: LocalDate?,
        portalMaxWeek: Int?
    ): ResolvedAcademicSemesterCalendar {
        val directEnd = directCalendar?.semesterEndDate
        if (directCalendar != null &&
            directEnd != null &&
            directEnd >= directCalendar.semesterStartMonday &&
            matchesSemester(directCalendar.semesterStartMonday, semester)
        ) {
            return resolved(
                directCalendar.semesterStartMonday,
                directEnd,
                today,
                AcademicSemesterCalendarSource.PORTAL
            )
        }

        if (weeklyStartMonday != null &&
            portalMaxWeek != null &&
            portalMaxWeek > 0 &&
            matchesSemester(weeklyStartMonday, semester)
        ) {
            return resolved(
                weeklyStartMonday,
                weeklyStartMonday.plusDays(portalMaxWeek * 7L - 1L),
                today,
                AcademicSemesterCalendarSource.WEEKLY
            )
        }

        val estimate = AcademicSemesterCalendarEstimator.estimate(semester, today)
        return ResolvedAcademicSemesterCalendar(
            startMonday = estimate.startMonday,
            endDate = estimate.endDate,
            currentWeekNumber = estimate.currentWeekNumber,
            source = AcademicSemesterCalendarSource.ESTIMATED
        )
    }

    /**
     * 门户校历只描述“当前学期”，提前晋升下一学期时仍可能返回上一学期。
     * 因此必须同时匹配学年和春秋季，避免把完整但属于旧学期的一对日期写入新学期。
     */
    private fun matchesSemester(startMonday: LocalDate, semester: AcademicSemester): Boolean {
        if (startMonday.year != semester.portalYear) return false
        return when (semester.season) {
            SemesterSeason.SPRING -> startMonday.monthValue in 1..7
            SemesterSeason.AUTUMN -> startMonday.monthValue in 8..12
        }
    }

    private fun resolved(
        startMonday: LocalDate,
        endDate: LocalDate,
        today: LocalDate,
        source: AcademicSemesterCalendarSource
    ) = ResolvedAcademicSemesterCalendar(
        startMonday = startMonday,
        endDate = endDate,
        currentWeekNumber = academicWeekForDate(
            today,
            startMonday,
            academicMaxWeekForCalendar(startMonday, endDate)
        ),
        source = source
    )
}

object AcademicSemesterCalendarEstimator {
    fun estimate(semester: AcademicSemester, today: LocalDate): AcademicSemesterCalendarEstimate {
        val startMonth = if (semester.season == SemesterSeason.AUTUMN) 9 else 3
        val startMonday = LocalDate.of(semester.portalYear, startMonth, 1)
            .with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
        val endDate = defaultSemesterEndDate(startMonday)
        return AcademicSemesterCalendarEstimate(
            startMonday = startMonday,
            endDate = endDate,
            currentWeekNumber = academicWeekForDate(
                today,
                startMonday,
                academicMaxWeekForCalendar(startMonday, endDate)
            )
        )
    }
}

data class AcademicSemesterCurrentImportDecision(
    val responseKind: AcademicSemesterResponseKind,
    val courses: List<ScheduleCourse>
) {
    val canReplace: Boolean
        get() = responseKind == AcademicSemesterResponseKind.VALID_EMPTY_SCHEDULE ||
            responseKind == AcademicSemesterResponseKind.VALID_NON_EMPTY_SCHEDULE
}

object AcademicSemesterCurrentImportPlanner {
    fun parse(body: String, parser: AcademicScheduleParser): AcademicSemesterCurrentImportDecision {
        val structuralKind = AcademicSemesterResponseValidator.classify(body, courseCount = 0)
        if (structuralKind == AcademicSemesterResponseKind.AUTHENTICATION_EXPIRED ||
            structuralKind == AcademicSemesterResponseKind.INVALID_STRUCTURE) {
            return AcademicSemesterCurrentImportDecision(structuralKind, emptyList())
        }
        val courses = runCatching { parser.parsePersonalSchedule(body) }.getOrElse {
            return AcademicSemesterCurrentImportDecision(
                AcademicSemesterResponseKind.INVALID_STRUCTURE,
                emptyList()
            )
        }
        return AcademicSemesterCurrentImportDecision(
            responseKind = AcademicSemesterResponseValidator.classify(body, courses.size),
            courses = courses
        )
    }
}

object AcademicSemesterViewPlanner {
    fun weekFor(
        semester: AcademicSemester,
        today: LocalDate,
        fallbackStart: LocalDate,
        fallbackEnd: LocalDate
    ): Int {
        if (!semester.isCurrent) return 1
        val start = semester.semesterStartDate ?: fallbackStart
        val end = semester.semesterEndDate ?: fallbackEnd
        return academicWeekForDate(today, start, academicMaxWeekForCalendar(start, end))
    }
}
