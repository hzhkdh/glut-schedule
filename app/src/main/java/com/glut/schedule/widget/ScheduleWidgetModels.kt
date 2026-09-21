package com.glut.schedule.widget

import com.glut.schedule.data.model.ClassPeriod
import com.glut.schedule.data.model.CourseBlock
import com.glut.schedule.data.model.ManualDayCopyRule
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.academicWeekForDate
import com.glut.schedule.data.model.isActiveInWeek
import com.glut.schedule.data.model.manualCopyBlocksForWeek
import com.glut.schedule.data.model.scheduleWeekForNumber
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

internal fun widgetHeaderWeekText(status: WidgetScheduleStatus, currentWeek: Int, weekday: String): String =
    if (status == WidgetScheduleStatus.READY || status == WidgetScheduleStatus.NO_COURSES) {
        "第 $currentWeek 周 · $weekday"
    } else {
        weekday
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
    val colorHex: String,
    val stableId: Long = 0L
)

data class WidgetScheduleSnapshot(
    val status: WidgetScheduleStatus,
    val today: LocalDate,
    val currentWeek: Int,
    val todayCourses: List<WidgetCourseItem> = emptyList(),
    val tomorrowCourses: List<WidgetCourseItem> = emptyList()
)

object ScheduleWidgetSnapshotBuilder {
    fun build(
        now: LocalDateTime,
        courses: List<ScheduleCourse>,
        classPeriods: List<ClassPeriod>,
        semesterStartMonday: LocalDate,
        semesterEndDate: LocalDate,
        manualDayCopies: List<ManualDayCopyRule> = emptyList()
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
            // 手动调休的副本与首页走同一个纯函数：源日期课程整天复制到目标日期。
            // 不接这一步，首页有的课在小组件里会凭空消失。
            val blocks = courses.flatMap { course ->
                course.occurrences.asSequence()
                    .filter { it.isActiveInWeek(week) }
                    .map { occurrence -> CourseBlock(course = course, occurrence = occurrence) }
                    .toList()
            } + manualCopyBlocksForWeek(
                courses = courses,
                rules = manualDayCopies,
                weekNumber = week,
                weekMonday = scheduleWeekForNumber(week, semesterStartMonday).monday
            )
            return blocks
                .filter { it.occurrence.dayOfWeek == day }
                .map { block ->
                    val occurrence = block.occurrence
                    WidgetCourseItem(
                        date = date,
                        title = block.course.title,
                        room = block.course.room,
                        teacher = block.course.teacher,
                        startSection = occurrence.startSection,
                        endSection = occurrence.endSection,
                        startTime = periodsBySection[occurrence.startSection]?.startsAt.orEmpty(),
                        endTime = periodsBySection[occurrence.endSection]?.endsAt.orEmpty(),
                        colorHex = block.course.colorHex,
                        // 日期占高位、排课 ID 占低位，跨刷新保持稳定且不同日期不会复用。
                        // 副本的 occurrence.id 带 `-manual-copy-<规则>` 后缀，不会与原课次撞 ID。
                        stableId = (date.toEpochDay() shl 32) xor
                            (occurrence.id.hashCode().toLong() and 0xFFFF_FFFFL)
                    )
                }
                .sortedWith(compareBy(WidgetCourseItem::startSection, WidgetCourseItem::endSection, WidgetCourseItem::title))
        }

        // 小组件只展示尚未结束的当日课程；结束时刻触发刷新后应立即从卡片中移除。
        val todayCourses = coursesFor(today).filter { it.isStillRelevantAt(now.toLocalTime()) }
        val tomorrowCourses = coursesFor(today.plusDays(1))

        return WidgetScheduleSnapshot(
            status = if (todayCourses.isEmpty()) WidgetScheduleStatus.NO_COURSES else WidgetScheduleStatus.READY,
            today = today,
            currentWeek = currentWeek,
            todayCourses = todayCourses,
            tomorrowCourses = tomorrowCourses
        )
    }
}

private fun WidgetCourseItem.isStillRelevantAt(now: LocalTime): Boolean {
    val end = runCatching { LocalTime.parse(endTime) }.getOrNull()
    return end == null || end.isAfter(now)
}
