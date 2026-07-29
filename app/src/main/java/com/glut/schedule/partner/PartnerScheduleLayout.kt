package com.glut.schedule.partner

import java.time.LocalTime

enum class PartnerOverlapKind {
    NONE,
    PARTIAL,
    EXACT,
    SAME_COURSE
}

data class PartnerFreeSegment(
    val startTime: String,
    val endTime: String
)

data class PartnerDisplayGroup(
    val courses: List<PartnerCourse>,
    val kind: PartnerOverlapKind,
    val dayOfWeek: Int,
    val startTime: String,
    val endTime: String
)

fun classifyPartnerOverlap(first: PartnerCourse, second: PartnerCourse): PartnerOverlapKind {
    if (first.ownerColor == second.ownerColor || first.dayOfWeek != second.dayOfWeek) {
        return PartnerOverlapKind.NONE
    }
    val firstStart = LocalTime.parse(first.startTime)
    val firstEnd = LocalTime.parse(first.endTime)
    val secondStart = LocalTime.parse(second.startTime)
    val secondEnd = LocalTime.parse(second.endTime)
    if (firstStart >= secondEnd || secondStart >= firstEnd) return PartnerOverlapKind.NONE

    val exactTime = firstStart == secondStart && firstEnd == secondEnd
    if (!exactTime) return PartnerOverlapKind.PARTIAL

    val sameTitle = first.title.normalizedCourseText() == second.title.normalizedCourseText()
    val sameRoom = first.room.orEmpty().normalizedCourseText() == second.room.orEmpty().normalizedCourseText()
    return if (sameTitle && sameRoom) PartnerOverlapKind.SAME_COURSE else PartnerOverlapKind.EXACT
}

/**
 * 先合并双方所有忙碌区间，再取补集，确保相邻课程不会被错误拆成两个忙碌段。
 */
fun commonFreeSegments(
    dayOfWeek: Int,
    week: Int,
    dayStart: String,
    dayEnd: String,
    courses: List<PartnerCourse>
): List<PartnerFreeSegment> {
    val rangeStart = LocalTime.parse(dayStart)
    val rangeEnd = LocalTime.parse(dayEnd)
    require(rangeStart < rangeEnd) { "每日时间范围无效" }

    val busy = courses.asSequence()
        .filter { it.dayOfWeek == dayOfWeek && week in it.weeks }
        .map {
            TimeRange(
                start = maxOf(rangeStart, LocalTime.parse(it.startTime)),
                end = minOf(rangeEnd, LocalTime.parse(it.endTime))
            )
        }
        .filter { it.start < it.end }
        .sortedBy { it.start }
        .toList()

    val merged = mutableListOf<TimeRange>()
    busy.forEach { next ->
        val last = merged.lastOrNull()
        if (last == null || next.start > last.end) {
            merged += next
        } else if (next.end > last.end) {
            merged[merged.lastIndex] = last.copy(end = next.end)
        }
    }

    val free = mutableListOf<PartnerFreeSegment>()
    var cursor = rangeStart
    merged.forEach { interval ->
        if (cursor < interval.start) {
            free += PartnerFreeSegment(cursor.toString(), interval.start.toString())
        }
        if (interval.end > cursor) cursor = interval.end
    }
    if (cursor < rangeEnd) {
        free += PartnerFreeSegment(cursor.toString(), rangeEnd.toString())
    }
    return free
}

fun partnerDisplayGroups(week: Int, courses: List<PartnerCourse>): List<PartnerDisplayGroup> {
    return courses
        .filter { week in it.weeks }
        .groupBy { it.dayOfWeek }
        .toSortedMap()
        .flatMap { (day, dayCourses) ->
            val sorted = dayCourses.sortedWith(
                compareBy<PartnerCourse> { LocalTime.parse(it.startTime) }
                    .thenBy { LocalTime.parse(it.endTime) }
                    .thenBy { it.title }
            )
            val connectedGroups = mutableListOf<MutableList<PartnerCourse>>()
            var currentEnd: LocalTime? = null
            sorted.forEach { course ->
                val start = LocalTime.parse(course.startTime)
                val end = LocalTime.parse(course.endTime)
                if (connectedGroups.isEmpty() || currentEnd == null || start >= currentEnd) {
                    connectedGroups += mutableListOf(course)
                    currentEnd = end
                } else {
                    connectedGroups.last() += course
                    if (end > currentEnd) currentEnd = end
                }
            }
            connectedGroups.map { group ->
                val groupKind = when {
                    group.size == 1 -> PartnerOverlapKind.NONE
                    group.size == 2 -> classifyPartnerOverlap(group[0], group[1])
                        .takeUnless { it == PartnerOverlapKind.NONE }
                        ?: PartnerOverlapKind.PARTIAL
                    else -> multiCourseGroupKind(group)
                }
                PartnerDisplayGroup(
                    courses = group,
                    kind = groupKind,
                    dayOfWeek = day,
                    startTime = group.minOf { it.startTime },
                    endTime = group.maxOf { it.endTime }
                )
            }
        }
}

fun partnerCardTimeRange(group: PartnerDisplayGroup): Pair<String, String> {
    val displayedCourse = group.courses.first()
    return if (group.kind == PartnerOverlapKind.PARTIAL) {
        displayedCourse.startTime to displayedCourse.endTime
    } else {
        group.startTime to group.endTime
    }
}

fun canGeneratePartnerInvite(
    hasCourses: Boolean,
    isBusy: Boolean,
    hasActiveInvite: Boolean
): Boolean = hasCourses && !isBusy && !hasActiveInvite

private fun multiCourseGroupKind(group: List<PartnerCourse>): PartnerOverlapKind {
    val crossOwnerKinds = group.indices.flatMap { first ->
        ((first + 1) until group.size).map { second ->
            classifyPartnerOverlap(group[first], group[second])
        }
    }.filter { it != PartnerOverlapKind.NONE }
    return when {
        crossOwnerKinds.isEmpty() -> PartnerOverlapKind.PARTIAL
        crossOwnerKinds.all { it == PartnerOverlapKind.SAME_COURSE } -> PartnerOverlapKind.SAME_COURSE
        crossOwnerKinds.all { it == PartnerOverlapKind.EXACT } -> PartnerOverlapKind.EXACT
        else -> PartnerOverlapKind.PARTIAL
    }
}

private data class TimeRange(val start: LocalTime, val end: LocalTime)

private fun String.normalizedCourseText(): String =
    trim().replace(Regex("""\s+"""), " ").lowercase()
