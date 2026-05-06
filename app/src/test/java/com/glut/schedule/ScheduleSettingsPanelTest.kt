package com.glut.schedule

import com.glut.schedule.ui.pages.parseSemesterStartInput
import com.glut.schedule.ui.pages.formatSemesterStartInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ScheduleSettingsPanelTest {
    @Test
    fun semesterStartInputKeepsSlashSeparatorsFixed() {
        assertEquals("2026/03/09", formatSemesterStartInput("20260309"))
        assertEquals("2026/03/09", formatSemesterStartInput("202603/09"))
        assertEquals("2026/03", formatSemesterStartInput("202603"))
    }

    @Test
    fun semesterStartInputAcceptsSlashOrDashDates() {
        assertEquals(LocalDate.of(2026, 3, 9), parseSemesterStartInput("2026/3/9"))
        assertEquals(LocalDate.of(2026, 3, 9), parseSemesterStartInput("2026-03-09"))
    }

    @Test
    fun semesterStartInputRejectsInvalidDates() {
        assertNull(parseSemesterStartInput(""))
        assertNull(parseSemesterStartInput("2026/2/30"))
        assertNull(parseSemesterStartInput("not-a-date"))
    }
}
