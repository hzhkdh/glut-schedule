package com.glut.schedule

import com.glut.schedule.data.model.ExamInfo
import com.glut.schedule.ui.pages.examDateStatus
import com.glut.schedule.ui.pages.isExamUpcoming
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExamScheduleDisplayTest {
    private val today: LocalDate = LocalDate.of(2026, 5, 20)

    @Test
    fun examDateStatusLabelsTodayTomorrowAndFutureDates() {
        assertEquals("今天", examDateStatus(today, today))
        assertEquals("明天", examDateStatus(today.plusDays(1), today))
        assertEquals("还有 3 天", examDateStatus(today.plusDays(3), today))
    }

    @Test
    fun futureExamIsUpcoming() {
        val exam = exam(date = today.plusDays(1), endTime = "09:40")

        assertTrue(isExamUpcoming(exam, LocalDateTime.of(today, java.time.LocalTime.of(12, 0))))
    }

    @Test
    fun todayExamBeforeEndTimeIsUpcoming() {
        val exam = exam(date = today, endTime = "14:20")

        assertTrue(isExamUpcoming(exam, LocalDateTime.of(today, java.time.LocalTime.of(12, 30))))
    }

    @Test
    fun todayExamAfterEndTimeIsDiscarded() {
        val exam = exam(date = today, endTime = "14:20")

        assertFalse(isExamUpcoming(exam, LocalDateTime.of(today, java.time.LocalTime.of(14, 21))))
    }

    @Test
    fun pastDateExamIsDiscarded() {
        val exam = exam(date = today.minusDays(1), endTime = "18:00")

        assertFalse(isExamUpcoming(exam, LocalDateTime.of(today, java.time.LocalTime.of(9, 0))))
    }

    @Test
    fun todayExamWithUnparseableEndTimeIsKept() {
        val exam = exam(date = today, endTime = "下午")

        assertTrue(isExamUpcoming(exam, LocalDateTime.of(today, java.time.LocalTime.of(20, 0))))
    }

    private fun exam(date: LocalDate, endTime: String): ExamInfo {
        return ExamInfo(
            id = "id-$date-$endTime",
            courseName = "数字逻辑",
            examDate = date,
            startTime = "12:30",
            endTime = endTime,
            location = "06206D",
            seatNumber = "",
            examType = "正常考试",
            note = ""
        )
    }
}
