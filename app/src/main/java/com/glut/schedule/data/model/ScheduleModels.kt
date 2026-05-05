package com.glut.schedule.data.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

const val MIN_ACADEMIC_WEEK = 1
const val MAX_ACADEMIC_WEEK = 22
val DEFAULT_SEMESTER_START_MONDAY: LocalDate = LocalDate.of(2026, 3, 9)

fun clampAcademicWeek(week: Int): Int = week.coerceIn(MIN_ACADEMIC_WEEK, MAX_ACADEMIC_WEEK)

fun visibleDayCount(showWeekend: Boolean): Int = if (showWeekend) 7 else 5

fun normalizeSemesterStartMonday(date: LocalDate): LocalDate {
    return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
}

fun academicWeekForDate(date: LocalDate, semesterStartMonday: LocalDate): Int {
    val normalizedStart = normalizeSemesterStartMonday(semesterStartMonday)
    val daysFromStart = ChronoUnit.DAYS.between(normalizedStart, date)
    val rawWeek = Math.floorDiv(daysFromStart, 7L).toInt() + 1
    return clampAcademicWeek(rawWeek)
}

fun scheduleWeekForNumber(weekNumber: Int, semesterStartMonday: LocalDate): ScheduleWeek {
    val clampedWeekNumber = clampAcademicWeek(weekNumber)
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
    fun previous(): ScheduleWeek {
        val clampedNumber = clampAcademicWeek(number - 1)
        return copy(number = clampedNumber, monday = monday.minusDays(((number - clampedNumber) * 7).toLong()))
    }

    fun next(): ScheduleWeek {
        val clampedNumber = clampAcademicWeek(number + 1)
        return copy(number = clampedNumber, monday = monday.plusDays(((clampedNumber - number) * 7).toLong()))
    }

    fun dateFor(dayOfWeek: Int): LocalDate = monday.plusDays((dayOfWeek - 1).toLong())
}

data class ClassPeriod(
    val section: Int,
    val startsAt: String,
    val endsAt: String
)

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
    val normalized = weekText
        .replace("第", "")
        .replace(" ", "")
        .replace("，", ",")
        .trim()

    if (normalized.isBlank() || normalized == "全周") return true

    val requiresOdd = normalized.contains("单周")
    val requiresEven = normalized.contains("双周")
    if (requiresOdd && weekNumber % 2 == 0) return false
    if (requiresEven && weekNumber % 2 != 0) return false

    val spans = Regex("""\d{1,2}(?:-\d{1,2})?""").findAll(normalized).mapNotNull { match ->
        val token = match.value
        if ("-" in token) {
            val parts = token.split("-")
            val start = parts.getOrNull(0)?.toIntOrNull()
            val end = parts.getOrNull(1)?.toIntOrNull()
            if (start != null && end != null && start <= end) start..end else null
        } else {
            token.toIntOrNull()?.let { it..it }
        }
    }.toList()

    if (spans.isEmpty()) return true
    return spans.any { weekNumber in it }
}

data class ScheduleCourse(
    val id: String,
    val title: String,
    val room: String,
    val teacher: String,
    val colorHex: String,
    val occurrences: List<CourseOccurrence>
)

data class CourseBlock(
    val course: ScheduleCourse,
    val occurrence: CourseOccurrence
)

fun defaultClassPeriods(): List<ClassPeriod> = listOf(
    ClassPeriod(1, "08:30", "09:15"),
    ClassPeriod(2, "09:20", "10:05"),
    ClassPeriod(3, "10:25", "11:10"),
    ClassPeriod(4, "11:15", "12:00"),
    ClassPeriod(5, "14:30", "15:15"),
    ClassPeriod(6, "15:20", "16:05"),
    ClassPeriod(7, "16:25", "17:10"),
    ClassPeriod(8, "17:15", "18:00"),
    ClassPeriod(9, "18:30", "19:15"),
    ClassPeriod(10, "19:20", "20:05"),
    ClassPeriod(11, "20:10", "20:55"),
    ClassPeriod(12, "21:00", "21:45")
)

fun sampleCourses(): List<ScheduleCourse> = CourseColorMapper.assignColors(listOf(
    ScheduleCourse(
        id = "digital-logic",
        title = "数字逻辑",
        room = "06408D",
        teacher = "卢佩",
        colorHex = CourseColorMapper.colorForCourse("digital-logic", "数字逻辑"),
        occurrences = listOf(
            CourseOccurrence("digital-logic-tue-1", "digital-logic", 2, 1, 2, "1-16周", ""),
            CourseOccurrence("digital-logic-mon-9", "digital-logic", 1, 9, 10, "1-16周", "")
        )
    ),
    ScheduleCourse(
        id = "embedded",
        title = "嵌入式系统",
        room = "06104D",
        teacher = "蒋志军",
        colorHex = CourseColorMapper.colorForCourse("embedded", "嵌入式系统"),
        occurrences = listOf(
            CourseOccurrence("embedded-wed-1", "embedded", 3, 1, 2, "1-16周", ""),
            CourseOccurrence("embedded-thu-3", "embedded", 4, 3, 4, "1-16周", "")
        )
    ),
    ScheduleCourse(
        id = "english",
        title = "大学英语4",
        room = "04322Y",
        teacher = "莫梓",
        colorHex = CourseColorMapper.colorForCourse("english", "大学英语4"),
        occurrences = listOf(
            CourseOccurrence("english-mon-3", "english", 1, 3, 4, "单周", ""),
            CourseOccurrence("english-thu-1", "english", 4, 1, 2, "1-16周", "")
        )
    ),
    ScheduleCourse(
        id = "machine-learning",
        title = "机器学习",
        room = "014104J",
        teacher = "石凯",
        colorHex = CourseColorMapper.colorForCourse("machine-learning", "机器学习"),
        occurrences = listOf(
            CourseOccurrence("ml-tue-3", "machine-learning", 2, 3, 4, "1-16周", ""),
            CourseOccurrence("ml-tue-5", "machine-learning", 2, 5, 6, "1-16周", "")
        )
    ),
    ScheduleCourse(
        id = "politics",
        title = "习近平新时代中国特色社会主义思想概论",
        room = "07120D",
        teacher = "梁英",
        colorHex = CourseColorMapper.colorForCourse("politics", "习近平新时代中国特色社会主义思想概论"),
        occurrences = listOf(
            CourseOccurrence("politics-wed-3", "politics", 3, 3, 4, "1-16周", ""),
            CourseOccurrence("politics-mon-5", "politics", 1, 5, 6, "1-16周", "")
        )
    ),
    ScheduleCourse(
        id = "os",
        title = "操作系统B",
        room = "02418J",
        teacher = "张攀峰",
        colorHex = CourseColorMapper.colorForCourse("os", "操作系统B"),
        occurrences = listOf(
            CourseOccurrence("os-thu-5", "os", 4, 5, 6, "1-16周", ""),
            CourseOccurrence("os-thu-7", "os", 4, 7, 8, "1-16周", "")
        )
    ),
    ScheduleCourse(
        id = "java",
        title = "面向对象程序设计Java",
        room = "04525D",
        teacher = "邓韬",
        colorHex = CourseColorMapper.colorForCourse("java", "面向对象程序设计Java"),
        occurrences = listOf(
            CourseOccurrence("java-wed-7", "java", 3, 7, 8, "1-16周", ""),
            CourseOccurrence("java-tue-9", "java", 2, 9, 10, "1-16周", "")
        )
    ),
    ScheduleCourse(
        id = "algorithm",
        title = "算法设计与分析",
        room = "05207D",
        teacher = "敬超",
        colorHex = CourseColorMapper.colorForCourse("algorithm", "算法设计与分析"),
        occurrences = listOf(
            CourseOccurrence("algorithm-tue-7", "algorithm", 2, 7, 8, "1-16周", ""),
            CourseOccurrence("algorithm-thu-9", "algorithm", 4, 9, 10, "1-16周", "")
        )
    )
))
