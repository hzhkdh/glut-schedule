package com.glut.schedule.data.model

import com.glut.schedule.data.settings.CampusType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

const val MIN_ACADEMIC_WEEK = 1
const val MAX_ACADEMIC_WEEK = 22
private val weekSpanPattern = Regex("""\d{1,2}(?:[-－—]\d{1,2})?""")
val DEFAULT_SEMESTER_START_MONDAY: LocalDate = LocalDate.of(2026, 3, 9)

/** Estimate semester end date when教务 system doesn't provide it.
 *  Spring semesters (Feb–Jul start) typically run ~19 weeks.
 *  Fall semesters (Aug–Jan start) typically run ~20 weeks. */
fun defaultSemesterEndDate(startMonday: LocalDate): LocalDate {
    val weeks = if (startMonday.monthValue in 2..7) 18 else 19
    return startMonday.plusWeeks(weeks.toLong()).plusDays(6)
}

/** Backward-compatible shortcut for the hardcoded default start date. */
val DEFAULT_SEMESTER_END_DATE: LocalDate get() = defaultSemesterEndDate(DEFAULT_SEMESTER_START_MONDAY)

fun clampAcademicWeek(week: Int): Int = week.coerceIn(MIN_ACADEMIC_WEEK, MAX_ACADEMIC_WEEK)

fun clampAcademicWeek(week: Int, maxWeek: Int): Int {
    val boundedMaxWeek = maxWeek.coerceIn(MIN_ACADEMIC_WEEK, MAX_ACADEMIC_WEEK)
    return week.coerceIn(MIN_ACADEMIC_WEEK, boundedMaxWeek)
}

fun visibleDayCount(showWeekend: Boolean): Int = if (showWeekend) 7 else 5

fun normalizeSemesterStartMonday(date: LocalDate): LocalDate {
    return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
}

fun academicWeekForDate(
    date: LocalDate,
    semesterStartMonday: LocalDate,
    maxWeek: Int = MAX_ACADEMIC_WEEK
): Int {
    val normalizedStart = normalizeSemesterStartMonday(semesterStartMonday)
    val daysFromStart = ChronoUnit.DAYS.between(normalizedStart, date)
    val rawWeek = Math.floorDiv(daysFromStart, 7L).toInt() + 1
    return clampAcademicWeek(rawWeek, maxWeek)
}

fun academicMaxWeekForCalendar(semesterStartMonday: LocalDate, semesterEndDate: LocalDate): Int {
    return academicWeekForDate(semesterEndDate, semesterStartMonday)
}

fun scheduleWeekForNumber(
    weekNumber: Int,
    semesterStartMonday: LocalDate,
    maxWeek: Int = MAX_ACADEMIC_WEEK
): ScheduleWeek {
    val clampedWeekNumber = clampAcademicWeek(weekNumber, maxWeek)
    val normalizedStart = normalizeSemesterStartMonday(semesterStartMonday)
    return ScheduleWeek(
        number = clampedWeekNumber,
        monday = normalizedStart.plusDays(((clampedWeekNumber - 1) * 7).toLong())
    )
}

data class ScheduleWeek(
    val number: Int,
    val monday: LocalDate
) {
    fun previous(maxWeek: Int = MAX_ACADEMIC_WEEK): ScheduleWeek {
        val clampedNumber = clampAcademicWeek(number - 1, maxWeek)
        return copy(number = clampedNumber, monday = monday.minusDays(((number - clampedNumber) * 7).toLong()))
    }

    fun next(maxWeek: Int = MAX_ACADEMIC_WEEK): ScheduleWeek {
        val clampedNumber = clampAcademicWeek(number + 1, maxWeek)
        return copy(number = clampedNumber, monday = monday.plusDays(((clampedNumber - number) * 7).toLong()))
    }

    fun dateFor(dayOfWeek: Int): LocalDate = monday.plusDays((dayOfWeek - 1).toLong())
}

data class ClassPeriod(
    val section: Int,
    val startsAt: String,
    val endsAt: String
)

/** 课时标签（简化：午1/午2，其余用数字，7-14映射为5-12） */
fun ClassPeriod.periodLabel(): String = when (section) {
    5 -> "午1"
    6 -> "午2"
    else -> if (section > 6) "${section - 2}" else "$section"
}

/** 将内部节次号反向映射为显示节次标签。
 *  桂林（hasNoon=true）: 5→午1, 6→午2, 7-14→5-12
 *  南宁（hasNoon=false）: 直排 1-11 */
fun displaySectionLabel(section: Int, hasNoon: Boolean = true): String = when {
    hasNoon && section == 5 -> "午1"
    hasNoon && section == 6 -> "午2"
    hasNoon && section > 6 -> "${section - 2}"
    else -> "$section"
}

data class CourseOccurrence(
    val id: String,
    val courseId: String,
    val dayOfWeek: Int,
    val startSection: Int,
    val endSection: Int,
    val weekText: String,
    val note: String
) {
    val sectionSpan: Int
        get() = endSection - startSection + 1
}

fun CourseOccurrence.isActiveInWeek(weekNumber: Int): Boolean {
    return isWeekTextActive(weekText, clampAcademicWeek(weekNumber))
}

fun isWeekTextActive(weekText: String, weekNumber: Int): Boolean {
    return weekNumber in academicWeeksForText(weekText)
}

fun academicWeeksForText(weekText: String, maxWeek: Int = 22): List<Int> {
    val normalized = weekText
        .replace("第", "")
        .replace(" ", "")
        .replace("，", ",")
        .replace("、", ",")
        .replace("；", ",")
        .replace(";", ",")
        .trim()

    if (maxWeek < 1) return emptyList()
    if (normalized.isBlank() || normalized == "全周") return (1..maxWeek).toList()

    val parsed = normalized.split(',').flatMap { segment ->
        val requiresOdd = segment.contains("单")
        val requiresEven = segment.contains("双")
        val spans = weekSpanPattern.findAll(segment).mapNotNull { match ->
            val token = match.value.replace('－', '-').replace('—', '-')
            if ("-" in token) {
                val parts = token.split("-")
                val start = parts.getOrNull(0)?.toIntOrNull()
                val end = parts.getOrNull(1)?.toIntOrNull()
                if (start != null && end != null && start <= end) start..end else null
            } else {
                token.toIntOrNull()?.let { it..it }
            }
        }.toList()
        val baseWeeks = if (spans.isEmpty() && (requiresOdd || requiresEven)) {
            (1..maxWeek).toList()
        } else {
            spans.flatMap { it.toList() }
        }
        baseWeeks
            .filter { it in 1..maxWeek }
            .filter { !requiresOdd || it % 2 == 1 }
            .filter { !requiresEven || it % 2 == 0 }
    }
    return (parsed.ifEmpty { (1..maxWeek).toList() }).distinct().sorted()
}

data class ScheduleCourse(
    val id: String,
    val title: String,
    val room: String,
    val teacher: String,
    val colorHex: String,
    val occurrences: List<CourseOccurrence>
)

fun historicalAcademicMaxWeek(
    portalMaxWeek: Int?,
    courses: List<ScheduleCourse>
): Int {
    val derivedMaxWeek = courses.asSequence()
        .flatMap { it.occurrences.asSequence() }
        .flatMap { occurrence ->
            Regex("""\d{1,2}""").findAll(occurrence.weekText)
                .mapNotNull { it.value.toIntOrNull() }
        }
        .maxOrNull()
        ?: 20
    return (portalMaxWeek ?: derivedMaxWeek).coerceIn(MIN_ACADEMIC_WEEK, MAX_ACADEMIC_WEEK)
}

fun academicMaxWeekForSemester(
    isCurrentSemester: Boolean,
    portalMaxWeek: Int?,
    courses: List<ScheduleCourse>,
    semesterStartMonday: LocalDate,
    semesterEndDate: LocalDate
): Int {
    return if (isCurrentSemester) {
        academicMaxWeekForCalendar(semesterStartMonday, semesterEndDate)
    } else {
        historicalAcademicMaxWeek(portalMaxWeek, courses)
    }
}

data class CourseBlock(
    val course: ScheduleCourse,
    val occurrence: CourseOccurrence
)

fun defaultClassPeriods(): List<ClassPeriod> = guilinClassPeriods()

fun defaultClassPeriods(campus: CampusType): List<ClassPeriod> = when (campus) {
    CampusType.GUILIN -> guilinClassPeriods()
    CampusType.NANNING -> nanningClassPeriods()
}

fun validateClassPeriods(campus: CampusType, periods: List<ClassPeriod>): Boolean {
    val expectedSections = defaultClassPeriods(campus).map { it.section }
    if (periods.map { it.section } != expectedSections) return false

    val parsedTimes = periods.map { period ->
        val start = parseClassPeriodTime(period.startsAt) ?: return false
        val end = parseClassPeriodTime(period.endsAt) ?: return false
        if (!start.isBefore(end)) return false
        start to end
    }
    return parsedTimes.zipWithNext().all { (current, next) ->
        !next.first.isBefore(current.second)
    }
}

private fun parseClassPeriodTime(value: String): LocalTime? {
    if (!Regex("""(?:[01]\d|2[0-3]):[0-5]\d""").matches(value)) return null
    return runCatching { LocalTime.parse(value) }.getOrNull()
}

fun guilinClassPeriods(): List<ClassPeriod> = listOf(
    ClassPeriod(1, "08:30", "09:15"),
    ClassPeriod(2, "09:20", "10:05"),
    ClassPeriod(3, "10:25", "11:10"),
    ClassPeriod(4, "11:15", "12:00"),
    ClassPeriod(5, "12:30", "13:15"), // 中午1（教务节次=5）
    ClassPeriod(6, "13:20", "14:05"), // 中午2（教务节次=6）
    ClassPeriod(7, "14:30", "15:15"), // 第5节
    ClassPeriod(8, "15:20", "16:05"), // 第6节
    ClassPeriod(9, "16:25", "17:10"), // 第7节
    ClassPeriod(10, "17:15", "18:00"), // 第8节
    ClassPeriod(11, "18:30", "19:15"), // 第9节
    ClassPeriod(12, "19:20", "20:05"), // 第10节
    ClassPeriod(13, "20:10", "20:55"), // 第11节
    ClassPeriod(14, "21:00", "21:45"), // 第12节
)

/** 中午时段节次号（教务用5/6表示中午1/2，设置>显示中午 开关控制） */
val NOON_SECTIONS = setOf(5, 6)

fun nanningClassPeriods(): List<ClassPeriod> = listOf(
    ClassPeriod(1, "08:40", "09:20"),
    ClassPeriod(2, "09:25", "10:05"),
    ClassPeriod(3, "10:25", "11:05"),
    ClassPeriod(4, "11:10", "11:50"),
    ClassPeriod(5, "14:30", "15:10"),
    ClassPeriod(6, "15:15", "15:55"),
    ClassPeriod(7, "16:05", "16:45"),
    ClassPeriod(8, "16:50", "17:30"),
    ClassPeriod(9, "19:30", "20:10"),
    ClassPeriod(10, "20:15", "20:55"),
    ClassPeriod(11, "21:00", "21:40")
)
