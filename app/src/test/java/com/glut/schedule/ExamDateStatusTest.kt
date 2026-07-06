package com.glut.schedule

import com.glut.schedule.ui.pages.ExamDateUrgency
import com.glut.schedule.ui.pages.examDateStatus
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class ExamDateStatusTest {
    private val today: LocalDate = LocalDate.of(2026, 7, 4)

    @Test
    fun examDateStatusMarksTodayAsUrgent() {
        val status = examDateStatus(today, today)

        assertEquals("今天考试", status.label)
        assertEquals(ExamDateUrgency.Today, status.urgency)
    }

    @Test
    fun examDateStatusMarksOneToSevenDaysAsSoon() {
        val status = examDateStatus(today.plusDays(7), today)

        assertEquals("还有 7 天", status.label)
        assertEquals(ExamDateUrgency.Soon, status.urgency)
    }

    @Test
    fun examDateStatusMarksFutureDatesAfterSevenDaysAsLater() {
        val status = examDateStatus(today.plusDays(8), today)

        assertEquals("还有 8 天", status.label)
        assertEquals(ExamDateUrgency.Later, status.urgency)
    }

    @Test
    fun examDateStatusMarksPastDatesAsCompleted() {
        val status = examDateStatus(today.minusDays(1), today)

        assertEquals("已结束", status.label)
        assertEquals(ExamDateUrgency.Completed, status.urgency)
    }
}
