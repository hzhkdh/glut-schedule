package com.glut.schedule

import com.glut.schedule.ui.components.scheduleHeaderPrimaryText
import com.glut.schedule.ui.components.isWeekTitleClickable
import com.glut.schedule.ui.components.scheduleGridMonthHeaderStartPaddingDp
import com.glut.schedule.ui.components.scheduleGridMonthHeaderTopPaddingDp
import com.glut.schedule.ui.components.scheduleGridMonthText
import com.glut.schedule.ui.components.scheduleCalendarDays
import com.glut.schedule.data.model.ScheduleWeek
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ScheduleHeaderTest {
    @Test
    fun headerPrimaryTextShowsDayOnlyForCurrentWeek() {
        assertEquals("第9周 周日", scheduleHeaderPrimaryText(9, 9, "周日"))
    }

    @Test
    fun currentSemesterHeaderMarksNonCurrentWeekWithEnglishParentheses() {
        assertEquals("第6周(非本周)", scheduleHeaderPrimaryText(6, 9, "周日"))
        assertEquals("第14周(非本周)", scheduleHeaderPrimaryText(14, 9, "周日"))
    }

    @Test
    fun historicalHeaderUsesPlainWeekTitleAndDisablesClick() {
        assertEquals("第3周", scheduleHeaderPrimaryText(3, 9, "周日", isHistorical = true))
        assertEquals(false, isWeekTitleClickable(isHistorical = true))
        assertEquals(true, isWeekTitleClickable(isHistorical = false))
    }

    @Test
    fun gridMonthTextUsesWeekMondayMonth() {
        assertEquals("5月", scheduleGridMonthText(LocalDate.of(2026, 5, 11)))
        assertEquals("7月", scheduleGridMonthText(LocalDate.of(2026, 7, 13)))
    }

    @Test
    fun gridMonthHeaderKeepsMonthAlignedWithWeekdayRow() {
        assertEquals(15, scheduleGridMonthHeaderStartPaddingDp())
        assertEquals(6, scheduleGridMonthHeaderTopPaddingDp())
    }

    @Test
    fun calendarDaysExposeDatesAndHighlightOnlyTheActualToday() {
        val currentWeek = ScheduleWeek(1, LocalDate.of(2026, 7, 27))
        val currentDays = scheduleCalendarDays(
            week = currentWeek,
            today = LocalDate.of(2026, 7, 30),
            dayCount = 5,
            showCalendarDates = true
        )

        assertEquals(listOf("一", "二", "三", "四", "五"), currentDays.map { it.name })
        assertEquals(listOf(27, 28, 29, 30, 31), currentDays.map { it.date?.dayOfMonth })
        assertEquals(listOf(false, false, false, true, false), currentDays.map { it.isToday })

        val otherWeek = scheduleCalendarDays(
            week = ScheduleWeek(2, LocalDate.of(2026, 8, 3)),
            today = LocalDate.of(2026, 7, 30),
            dayCount = 7,
            showCalendarDates = true
        )
        assertEquals(7, otherWeek.size)
        assertEquals(false, otherWeek.any { it.isToday })
    }
}
