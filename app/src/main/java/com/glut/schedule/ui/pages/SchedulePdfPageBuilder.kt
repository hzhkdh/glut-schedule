package com.glut.schedule.ui.pages

import com.glut.schedule.data.model.ClassPeriod
import com.glut.schedule.data.model.CourseBlock
import com.glut.schedule.data.model.ScheduleWeek
import com.glut.schedule.data.model.SemesterAdjustment
import com.glut.schedule.data.model.adjustmentMarkerFor
import com.glut.schedule.data.model.displaySectionLabel
import com.glut.schedule.data.model.visibleDayCount
import com.glut.schedule.service.export.PdfCourseSlot
import com.glut.schedule.service.export.PdfDayHeader
import com.glut.schedule.service.export.PdfPeriodRow
import com.glut.schedule.service.export.PdfWeekPage
import com.glut.schedule.ui.components.overlappingCourseBlockGroups
import com.glut.schedule.ui.components.scheduleCalendarDays
import com.glut.schedule.ui.components.scheduleDayMarker
import com.glut.schedule.ui.components.scheduleRowIndex
import com.glut.schedule.ui.components.visiblePeriodRows
import java.time.LocalDate

/**
 * 把一周的课前区块整理成 PDF 页面模型。
 *
 * 行号、列号、冲突分组全部复用课表网格的同一批纯函数，保证 PDF 的排布口径与屏幕一致：
 * 关闭「显示中午」后午 1/午 2 两行不占位，关闭「显示周末」后只有 5 列。
 */
internal fun buildPdfWeekPage(
    semesterName: String,
    week: ScheduleWeek,
    blocks: List<CourseBlock>,
    periods: List<ClassPeriod>,
    showNoon: Boolean,
    hasNoonSections: Boolean,
    showWeekend: Boolean,
    showCalendarDates: Boolean,
    holidayDates: Set<LocalDate>,
    manualAdjustmentDates: Set<LocalDate>,
    adjustments: List<SemesterAdjustment>,
    showTeacher: Boolean,
    showRoom: Boolean,
    pageNumber: Int = 1,
    pageCount: Int = 1
): PdfWeekPage {
    val effectiveShowNoon = showNoon || !hasNoonSections
    val rows = visiblePeriodRows(periods, effectiveShowNoon)
    val dayCount = visibleDayCount(showWeekend)
    val visibleSections = rows.mapTo(mutableSetOf()) { it.section }

    val visibleBlocks = blocks.filter { block ->
        block.occurrence.dayOfWeek in 1..dayCount &&
            block.occurrence.startSection in visibleSections
    }
    val slots = overlappingCourseBlockGroups(visibleBlocks).mapNotNull { group ->
        // 冲突组与屏幕一致：只展示当前可见的那一张，右下角标门数。
        val active = group.first()
        val rowIndex = scheduleRowIndex(active.occurrence.startSection, rows) ?: return@mapNotNull null
        val spanSections = (active.occurrence.startSection..active.occurrence.endSection)
            .count { it in visibleSections }
        PdfCourseSlot(
            rowIndex = rowIndex,
            rowSpan = spanSections.coerceAtLeast(1),
            columnIndex = active.occurrence.dayOfWeek - 1,
            title = active.course.title,
            room = if (showRoom) {
                active.occurrence.note.ifBlank { active.course.room }
            } else {
                ""
            },
            teacher = if (showTeacher) active.course.teacher else "",
            colorHex = active.course.colorHex,
            marker = adjustmentMarkerFor(
                occurrence = active.occurrence,
                courseTitle = active.course.title,
                weekNumber = week.number,
                adjustments = adjustments
            ),
            conflictCount = group.size
        )
    }

    // 传一个不可能落在本周的日期，避免把「今天」高亮画进打印稿。
    val days = scheduleCalendarDays(
        week = week,
        today = week.monday.minusDays(7),
        dayCount = dayCount,
        showCalendarDates = showCalendarDates
    ).map { day ->
        PdfDayHeader(
            weekday = day.name,
            dateText = day.date?.dayOfMonth?.toString(),
            marker = scheduleDayMarker(day.date, holidayDates, manualAdjustmentDates)?.label
        )
    }

    val rowsData = rows.map { period ->
        PdfPeriodRow(
            label = displaySectionLabel(period.section, hasNoonSections),
            timeText = listOf(period.startsAt, period.endsAt)
                .filter { it.isNotBlank() }
                .joinToString("–")
        )
    }

    val weekEnd = week.monday.plusDays(6)
    val subtitle = "${week.monday.monthValue}月${week.monday.dayOfMonth}日 – " +
        "${weekEnd.monthValue}月${weekEnd.dayOfMonth}日"
    return PdfWeekPage(
        title = "${semesterName.ifBlank { "课表" }} · 第 ${week.number} 周",
        subtitle = subtitle,
        days = days,
        rows = rowsData,
        slots = slots,
        footer = "由「桂工课表」导出 · 第 ${week.number} 周",
        monthText = "${week.monday.monthValue}月",
        pageNumber = pageNumber,
        pageCount = pageCount
    )
}
