package com.glut.schedule.data.model

import java.time.Duration
import java.time.LocalTime

private const val COURSE_TIME_STATS_FNV_OFFSET_BASIS = 0x811C9DC5L
private const val COURSE_TIME_STATS_FNV_PRIME = 0x01000193
private const val COURSE_TIME_STATS_COLOR_DISTANCE = 95
private const val COURSE_TIME_STATS_COLOR_PROBE_STEP = 5

// 与微信端保持完全相同的统计图专用调色板；不复用课程卡片的 12 色映射。
private val courseTimeStatsPalette = listOf(
    "#2563EB", "#DC2626", "#16A34A", "#D97706", "#7C3AED", "#0891B2",
    "#DB2777", "#65A30D", "#EA580C", "#4F46E5", "#0D9488", "#BE123C",
    "#9333EA", "#0284C7", "#CA8A04", "#059669", "#C2410C", "#6D28D9",
    "#0E7490", "#E11D48", "#15803D", "#B45309", "#0369A1", "#A21CAF"
)

enum class CourseTimeDimension(val title: String) {
    COURSE("课程"),
    ROOM("教室"),
    TEACHER("教师")
}

enum class CourseTimeStatsUnavailableReason {
    MISSING_MAX_WEEK,
    MISSING_CLASS_PERIODS,
    INVALID_WEEK_TEXT
}

data class CourseTimeSemesterSource(
    val semesterId: String,
    val semesterLabel: String,
    val isCurrent: Boolean,
    val isDownloaded: Boolean,
    val portalMaxWeek: Int?,
    val courses: List<ScheduleCourse>,
    val classPeriods: List<ClassPeriod>
)

data class CourseTimeStatsExcludedSemester(
    val semesterId: String,
    val semesterLabel: String,
    val reason: CourseTimeStatsUnavailableReason
)

data class CourseTimeStatsCoverage(
    val eligibleSemesters: Int,
    val downloadedSemesters: Int,
    val excludedSemesters: List<CourseTimeStatsExcludedSemester>
)

data class CourseTimeStatsItem(
    val key: String,
    val label: String,
    val minutes: Int,
    val share: Double,
    val colorHex: String
)

data class CourseTimeStatsResult(
    val totalMinutes: Int,
    val items: List<CourseTimeStatsItem>,
    val distribution: List<CourseTimeStatsItem>,
    val coverage: CourseTimeStatsCoverage
)

object CourseTimeStatsCalculator {
    private val teacherSeparator = Regex("""[、，,；;/／]+""")
    private val courseClassSuffix = Regex("""\s*@\S+$""")
    private val whitespace = Regex("""\s+""")
    private val validWeekCharacters = Regex("""^[0-9单双周全第,\-－—~至，、；;\s]+$""")
    private val weekRange = Regex("""^(\d{1,2})(?:-(\d{1,2}))?$""")

    fun calculate(
        sources: List<CourseTimeSemesterSource>,
        dimension: CourseTimeDimension
    ): CourseTimeStatsResult {
        val downloadedSources = sources.filter { it.isDownloaded }
        val totals = linkedMapOf<String, MutableStatsItem>()
        val excluded = mutableListOf<CourseTimeStatsExcludedSemester>()
        var eligibleCount = 0

        downloadedSources.forEach { source ->
            if (source.courses.isEmpty()) {
                eligibleCount += 1
                return@forEach
            }

            val sourceTotals = aggregateSemester(source, dimension)
            if (sourceTotals is SemesterAggregation.Unavailable) {
                excluded += CourseTimeStatsExcludedSemester(
                    semesterId = source.semesterId,
                    semesterLabel = source.semesterLabel,
                    reason = sourceTotals.reason
                )
                return@forEach
            }

            eligibleCount += 1
            (sourceTotals as SemesterAggregation.Available).items.forEach { item ->
                val current = totals[item.key]
                if (current == null) {
                    // sources 按新到旧传入，首次出现的写法就是跨学期汇总时展示的名称。
                    totals[item.key] = item.copy()
                } else {
                    current.minutes += item.minutes
                }
            }
        }

        val totalMinutes = totals.values.sumOf { it.minutes }
        val sortedTotals = totals.values
            .sortedWith(compareByDescending<MutableStatsItem> { it.minutes }.thenBy { it.key })
        val colors = allocateCourseTimeStatsColors(dimension, sortedTotals.map { it.key })
        val items = sortedTotals
            .mapIndexed { index, item ->
                CourseTimeStatsItem(
                    key = item.key,
                    label = item.label,
                    minutes = item.minutes,
                    share = if (totalMinutes == 0) 0.0 else item.minutes.toDouble() / totalMinutes,
                    colorHex = colors[index]
                )
            }

        return CourseTimeStatsResult(
            totalMinutes = totalMinutes,
            items = items,
            distribution = items.toList(),
            coverage = CourseTimeStatsCoverage(
                eligibleSemesters = eligibleCount,
                downloadedSemesters = downloadedSources.size,
                excludedSemesters = excluded
            )
        )
    }

    private fun aggregateSemester(
        source: CourseTimeSemesterSource,
        dimension: CourseTimeDimension
    ): SemesterAggregation {
        val maxWeek = source.portalMaxWeek
        if (maxWeek == null || maxWeek !in 1..30) {
            return SemesterAggregation.Unavailable(CourseTimeStatsUnavailableReason.MISSING_MAX_WEEK)
        }

        val periods = source.classPeriods.associateBy { it.section }
        if (periods.size != source.classPeriods.size) {
            return SemesterAggregation.Unavailable(CourseTimeStatsUnavailableReason.MISSING_CLASS_PERIODS)
        }

        val totals = linkedMapOf<String, MutableStatsItem>()
        val reliableCourseKeys = source.courses
            .filter(::hasReliableTeachingMetadata)
            .mapTo(mutableSetOf(), ::normalizedCourseKey)
        for (course in source.courses) {
            for (occurrence in course.occurrences) {
                if (isPendingEmptyPlaceholder(course, occurrence, reliableCourseKeys)) {
                    // 周次课表可能用“待确认 + 空教室”补齐单双周网格；同名真实排课存在时，
                    // 这类记录只是占位周次，不能进入课程、教室或教师任一统计维度。
                    continue
                }
                val minutesPerWeek = occurrenceMinutes(occurrence, periods)
                    ?: return SemesterAggregation.Unavailable(
                        CourseTimeStatsUnavailableReason.MISSING_CLASS_PERIODS
                    )
                val activeWeeks = parseWeeksStrict(occurrence.weekText, maxWeek)
                    ?: return SemesterAggregation.Unavailable(
                        CourseTimeStatsUnavailableReason.INVALID_WEEK_TEXT
                    )
                val occurrenceMinutes = minutesPerWeek * activeWeeks.size
                labelsFor(course, occurrence, dimension).forEach { label ->
                    val key = normalizeKey(label)
                    val item = totals.getOrPut(key) {
                        MutableStatsItem(key = key, label = label, minutes = 0)
                    }
                    item.minutes += occurrenceMinutes
                }
            }
        }
        return SemesterAggregation.Available(totals.values.toList())
    }

    private fun occurrenceMinutes(
        occurrence: CourseOccurrence,
        periods: Map<Int, ClassPeriod>
    ): Int? {
        if (occurrence.startSection < 1 || occurrence.endSection < occurrence.startSection) return null
        var total = 0L
        for (section in occurrence.startSection..occurrence.endSection) {
            val period = periods[section] ?: return null
            val start = parseTime(period.startsAt) ?: return null
            val end = parseTime(period.endsAt) ?: return null
            val minutes = Duration.between(start, end).toMinutes()
            if (minutes <= 0) return null
            // 每节课单独计时，因此相邻节次之间的课间不会进入统计。
            total += minutes
        }
        return total.takeIf { it <= Int.MAX_VALUE }?.toInt()
    }

    private fun parseWeeksStrict(weekText: String, maxWeek: Int): List<Int>? {
        val raw = weekText.trim()
        if (raw.isBlank() || raw == "全周") return (1..maxWeek).toList()
        if (!raw.matches(validWeekCharacters)) return null

        val normalized = raw
            .replace("第", "")
            .replace("周", "")
            .replace("至", "-")
            .replace('－', '-')
            .replace('—', '-')
            .replace('~', '-')
            .replace("，", ",")
            .replace("、", ",")
            .replace("；", ",")
            .replace(";", ",")
            .replace(whitespace, "")

        val weeks = mutableListOf<Int>()
        for (segment in normalized.split(',')) {
            if (segment.isBlank()) return null
            val odd = segment.contains("单")
            val even = segment.contains("双")
            if (odd && even) return null
            val rangeText = segment.replace("单", "").replace("双", "")
            val baseWeeks = if (rangeText.isBlank()) {
                (1..maxWeek).toList()
            } else {
                val match = weekRange.matchEntire(rangeText) ?: return null
                val start = match.groupValues[1].toInt()
                val end = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: start
                if (start > end) return null
                (start..end).filter { it in 1..maxWeek }
            }
            weeks += baseWeeks.filter { (!odd || it % 2 == 1) && (!even || it % 2 == 0) }
        }
        return weeks.distinct().sorted().takeIf { it.isNotEmpty() }
    }

    private fun labelsFor(
        course: ScheduleCourse,
        occurrence: CourseOccurrence,
        dimension: CourseTimeDimension
    ): List<String> = when (dimension) {
        CourseTimeDimension.COURSE -> listOf(normalizedCourseLabel(course))
        CourseTimeDimension.ROOM -> listOf(
            cleanLabel(occurrence.note.ifBlank { course.room }).ifBlank { "未填写教室" }
        )
        CourseTimeDimension.TEACHER -> {
            val rawTeacher = cleanLabel(course.teacher)
            if (rawTeacher.isBlank() || rawTeacher == "待确认") {
                listOf("未填写教师")
            } else {
                rawTeacher.split(teacherSeparator)
                    .map(::cleanLabel)
                    .filter { it.isNotBlank() }
                    .distinctBy(::normalizeKey)
                    .ifEmpty { listOf("未填写教师") }
            }
        }
    }

    private fun cleanLabel(value: String): String = value.trim().replace(whitespace, " ")

    private fun normalizedCourseLabel(course: ScheduleCourse): String =
        cleanLabel(course.title)
            .replace(courseClassSuffix, "")
            .ifBlank { "未命名课程" }

    private fun normalizedCourseKey(course: ScheduleCourse): String =
        normalizeKey(normalizedCourseLabel(course))

    private fun hasReliableTeachingMetadata(course: ScheduleCourse): Boolean {
        val teacher = cleanLabel(course.teacher)
        if (teacher.isBlank() || teacher == "待确认") return false
        return course.occurrences.any { occurrence -> effectiveRoom(course, occurrence).isNotBlank() }
    }

    private fun isPendingEmptyPlaceholder(
        course: ScheduleCourse,
        occurrence: CourseOccurrence,
        reliableCourseKeys: Set<String>
    ): Boolean {
        val teacher = cleanLabel(course.teacher)
        val teacherPending = teacher.isBlank() || teacher == "待确认"
        return teacherPending &&
            effectiveRoom(course, occurrence).isBlank() &&
            normalizedCourseKey(course) in reliableCourseKeys
    }

    private fun effectiveRoom(course: ScheduleCourse, occurrence: CourseOccurrence): String =
        cleanLabel(occurrence.note.ifBlank { course.room })

    private fun normalizeKey(value: String): String = buildString {
        cleanLabel(value).forEach { char ->
            append(if (char in 'A'..'Z') char.lowercaseChar() else char)
        }
    }

    private data class MutableStatsItem(
        val key: String,
        val label: String,
        var minutes: Int
    )

    private sealed interface SemesterAggregation {
        data class Available(val items: List<MutableStatsItem>) : SemesterAggregation
        data class Unavailable(
            val reason: CourseTimeStatsUnavailableReason
        ) : SemesterAggregation
    }

    private fun parseTime(value: String): LocalTime? {
        if (!Regex("""(?:[01]\d|2[0-3]):[0-5]\d""").matches(value)) return null
        return runCatching { LocalTime.parse(value) }.getOrNull()
    }
}

/**
 * 按环形图顺序确定性分配颜色。前 24 项不复用；更多分类可复用，
 * 但首尾和任意相邻扇区都不能使用相同或视觉距离过近的颜色。
 */
internal fun allocateCourseTimeStatsColors(
    dimension: CourseTimeDimension,
    keys: List<String>
): List<String> {
    if (keys.isEmpty()) return emptyList()
    val assigned = IntArray(keys.size) { -1 }
    val used = BooleanArray(courseTimeStatsPalette.size)
    val uniqueLimit = minOf(keys.size, courseTimeStatsPalette.size)

    fun assign(index: Int): Boolean {
        if (index == keys.size) return true
        val baseIndex = (courseTimeStatsHash("${dimension.name.lowercase()}:${keys[index]}") %
            courseTimeStatsPalette.size).toInt()
        for (probe in courseTimeStatsPalette.indices) {
            val candidate = (baseIndex + probe * COURSE_TIME_STATS_COLOR_PROBE_STEP) %
                courseTimeStatsPalette.size
            if (index < uniqueLimit && used[candidate]) continue
            if (index > 0 && courseTimeStatsColorsTooClose(candidate, assigned[index - 1])) continue
            if (index == keys.lastIndex && keys.size > 1 &&
                courseTimeStatsColorsTooClose(candidate, assigned[0])) continue

            assigned[index] = candidate
            val newlyUsed = !used[candidate]
            if (newlyUsed) used[candidate] = true
            if (assign(index + 1)) return true
            if (newlyUsed) used[candidate] = false
            assigned[index] = -1
        }
        return false
    }

    check(assign(0)) { "无法为课时统计环形图分配可区分颜色" }
    return assigned.map(courseTimeStatsPalette::get)
}

private fun courseTimeStatsHash(value: String): Long {
    var hash = COURSE_TIME_STATS_FNV_OFFSET_BASIS
    value.forEach { char ->
        hash = (hash xor char.code.toLong()) * COURSE_TIME_STATS_FNV_PRIME
        hash = hash and 0xFFFFFFFFL
    }
    return hash
}

private fun courseTimeStatsColorsTooClose(leftIndex: Int, rightIndex: Int): Boolean {
    if (leftIndex < 0 || rightIndex < 0) return false
    val left = courseTimeStatsPalette[leftIndex]
    val right = courseTimeStatsPalette[rightIndex]
    val distanceSquared = listOf(1, 3, 5).sumOf { start ->
        val difference = left.substring(start, start + 2).toInt(16) -
            right.substring(start, start + 2).toInt(16)
        difference * difference
    }
    return distanceSquared < COURSE_TIME_STATS_COLOR_DISTANCE * COURSE_TIME_STATS_COLOR_DISTANCE
}
