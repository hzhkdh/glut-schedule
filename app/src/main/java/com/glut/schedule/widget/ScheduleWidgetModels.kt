package com.glut.schedule.widget

import com.glut.schedule.data.model.ClassPeriod
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.academicWeekForDate
import com.glut.schedule.data.model.isActiveInWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

enum class WidgetScheduleStatus {
    READY,
    NO_COURSES,
    NO_DATA,
    BEFORE_SEMESTER,
    OUTSIDE_SEMESTER,
    READ_ERROR
}

data class WidgetCourseItem(
    val date: LocalDate,
    val title: String,
    val room: String,
    val teacher: String,
    val startSection: Int,
    val endSection: Int,
    val startTime: String,
    val endTime: String,
    val colorHex: String
)

data class WidgetScheduleSnapshot(
    val status: WidgetScheduleStatus,
    val today: LocalDate,
    val currentWeek: Int,
    val todayCourses: List<WidgetCourseItem> = emptyList(),
    val tomorrowCourses: List<WidgetCourseItem> = emptyList(),
    val nextCourse: WidgetCourseItem? = null
)

object ScheduleWidgetSnapshotBuilder {
    fun build(
        now: LocalDateTime,
        courses: List<ScheduleCourse>,
        classPeriods: List<ClassPeriod>,
        semesterStartMonday: LocalDate,
        semesterEndDate: LocalDate,
        futureSearchDays: Int = 7
    ): WidgetScheduleSnapshot {
        val today = now.toLocalDate()
        val currentWeek = academicWeekForDate(today, semesterStartMonday)
        if (courses.isEmpty()) {
            return WidgetScheduleSnapshot(
                status = WidgetScheduleStatus.NO_DATA,
                today = today,
                currentWeek = currentWeek
            )
        }
        if (today.isBefore(semesterStartMonday)) {
            return WidgetScheduleSnapshot(
                status = WidgetScheduleStatus.BEFORE_SEMESTER,
                today = today,
                currentWeek = currentWeek
            )
        }
        if (today.isAfter(semesterEndDate)) {
            return WidgetScheduleSnapshot(
                status = WidgetScheduleStatus.OUTSIDE_SEMESTER,
                today = today,
                currentWeek = currentWeek
            )
        }

        val periodsBySection = classPeriods.associateBy { it.section }
        fun coursesFor(date: LocalDate): List<WidgetCourseItem> {
            if (date.isBefore(semesterStartMonday) || date.isAfter(semesterEndDate)) return emptyList()
            val week = academicWeekForDate(date, semesterStartMonday)
            val day = date.dayOfWeek.value
            return courses.flatMap { course ->
                course.occurrences.asSequence()
                    .filter { it.dayOfWeek == day && it.isActiveInWeek(week) }
                    .map { occurrence ->
                        WidgetCourseItem(
                            date = date,
                            title = course.title,
                            room = course.room,
                            teacher = course.teacher,
                            startSection = occurrence.startSection,
                            endSection = occurrence.endSection,
                            startTime = periodsBySection[occurrence.startSection]?.startsAt.orEmpty(),
                            endTime = periodsBySection[occurrence.endSection]?.endsAt.orEmpty(),
                            colorHex = course.colorHex
                        )
                    }.toList()
            }.sortedWith(compareBy(WidgetCourseItem::startSection, WidgetCourseItem::endSection, WidgetCourseItem::title))
        }

        val todayCourses = coursesFor(today)
        val tomorrowCourses = coursesFor(today.plusDays(1))
        val nextCourse = (0..futureSearchDays)
            .asSequence()
            .flatMap { offset -> coursesFor(today.plusDays(offset.toLong())).asSequence() }
            .firstOrNull { item -> item.date != today || item.isStillRelevantAt(now.toLocalTime()) }

        return WidgetScheduleSnapshot(
            status = if (todayCourses.isEmpty()) WidgetScheduleStatus.NO_COURSES else WidgetScheduleStatus.READY,
            today = today,
            currentWeek = currentWeek,
            todayCourses = todayCourses,
            tomorrowCourses = tomorrowCourses,
            nextCourse = nextCourse
        )
    }
}

private fun WidgetCourseItem.isStillRelevantAt(now: LocalTime): Boolean {
    val end = runCatching { LocalTime.parse(endTime) }.getOrNull()
    return end == null || end.isAfter(now)
}
