package com.glut.schedule

import com.glut.schedule.ui.components.scheduleHeaderPrimaryText
import org.junit.Assert.assertEquals
import org.junit.Test

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
}
