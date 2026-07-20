package com.glut.schedule.widget

import java.time.LocalDateTime
import java.time.LocalTime

object ScheduleWidgetRefreshPlanner {
    fun nextRefreshAt(
        now: LocalDateTime,
        snapshot: WidgetScheduleSnapshot
    ): LocalDateTime {
        val midnight = now.toLocalDate().plusDays(1).atStartOfDay()
        val courseBoundaries = snapshot.todayCourses.asSequence().flatMap { course ->
            sequenceOf(course.startTime, course.endTime).mapNotNull { value ->
                runCatching { course.date.atTime(LocalTime.parse(value)) }.getOrNull()
            }
        }

        return (courseBoundaries + sequenceOf(midnight))
            .filter { it.isAfter(now) }
            .minOrNull()
            ?: midnight
    }
}
