package com.glut.schedule

import com.glut.schedule.ui.components.scheduleHeaderPrimaryText
import com.glut.schedule.ui.components.scheduleGridMonthHeaderStartPaddingDp
import com.glut.schedule.ui.components.scheduleGridMonthHeaderTopPaddingDp
import com.glut.schedule.ui.components.scheduleGridMonthText
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ScheduleHeaderTest {
    @Test
    fun headerPrimaryTextShowsDayOnlyForCurrentWeek() {
        assertEquals("第9周 周日", scheduleHeaderPrimaryText(9, 9, "周日"))
    }

    @Test
    fun headerPrimaryTextMarksNonCurrentWeekWithEnglishParentheses() {
        assertEquals("第6周(非本周)", scheduleHeaderPrimaryText(6, 9, "周日"))
        assertEquals("第14周(非本周)", scheduleHeaderPrimaryText(14, 9, "周日"))
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
}
