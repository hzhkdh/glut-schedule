package com.glut.schedule

import com.glut.schedule.data.model.ExamInfo
import com.glut.schedule.ui.pages.ExamDateUrgency
import com.glut.schedule.ui.pages.ExamDisplayState
import com.glut.schedule.ui.pages.examDateStatus
import com.glut.schedule.ui.pages.examDisplayState
import com.glut.schedule.ui.pages.examGroupDateStatus
import com.glut.schedule.ui.pages.examsForDisplay
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
    fun examDateStatusLabelsAndClassifiesExamDates() {
        assertEquals("今天考试", examDateStatus(today, today).label)
        assertEquals(ExamDateUrgency.Today, examDateStatus(today, today).urgency)
        assertEquals("还有 1 天", examDateStatus(today.plusDays(1), today).label)
        assertEquals(ExamDateUrgency.Soon, examDateStatus(today.plusDays(3), today).urgency)
        assertEquals(ExamDateUrgency.Later, examDateStatus(today.plusDays(8), today).urgency)
        assertEquals(ExamDateUrgency.Completed, examDateStatus(today.minusDays(1), today).urgency)
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

    @Test
    fun completedExamGetsCompletedDisplayState() {
        val exam = exam(date = today, startTime = "12:30", endTime = "14:20")

        val state = examDisplayState(exam, LocalDateTime.of(today, java.time.LocalTime.of(14, 21)))

        assertEquals(ExamDisplayState.Completed, state)
    }

    @Test
    fun todayGroupWithOnlyCompletedExamsIsMarkedCompleted() {
        val display = examsForDisplay(
            listOf(exam(date = today, startTime = "12:30", endTime = "14:20")),
            LocalDateTime.of(today, java.time.LocalTime.of(14, 21))
        )

        val status = examGroupDateStatus(today, today, display)

        assertEquals("已结束", status.label)
        assertEquals(ExamDateUrgency.Completed, status.urgency)
    }

    @Test
    fun examsForDisplayKeepsCompletedExamsAtEnd() {
        val completed = exam(date = today.minusDays(1), startTime = "15:00", endTime = "17:00", courseName = "已考科目")
        val upcomingLater = exam(date = today.plusDays(2), startTime = "08:30", endTime = "10:30", courseName = "后天科目")
        val upcomingSoon = exam(date = today.plusDays(1), startTime = "09:00", endTime = "11:00", courseName = "明天科目")

        val display = examsForDisplay(
            listOf(completed, upcomingLater, upcomingSoon),
            LocalDateTime.of(today, java.time.LocalTime.of(12, 0))
        )

        assertEquals(listOf("明天科目", "后天科目", "已考科目"), display.map { it.exam.courseName })
        assertEquals(
            listOf(ExamDisplayState.Upcoming, ExamDisplayState.Upcoming, ExamDisplayState.Completed),
            display.map { it.state }
        )
    }

    @Test
    fun completedExamsSortedDescendingByDate() {
        // 先结束的考试应该排在后面（降序：最近结束的在前，最早结束的在后）
        val olderCompleted = exam(date = today.minusDays(5), startTime = "10:00", endTime = "12:00", courseName = "最早考完")
        val newerCompleted = exam(date = today.minusDays(2), startTime = "14:00", endTime = "16:00", courseName = "最近考完")

        val display = examsForDisplay(
            listOf(olderCompleted, newerCompleted),
            LocalDateTime.of(today, java.time.LocalTime.of(18, 0))
        )

        assertEquals(
            listOf("最近考完", "最早考完"),
            display.map { it.exam.courseName }
        )
    }

    @Test
    fun completedExamsSameDaySortedDescendingByStartTime() {
        // 同一天的已结束考试，先开始的排在后面
        val earlierOnSameDay = exam(date = today.minusDays(3), startTime = "08:00", endTime = "10:00", courseName = "早开始")
        val laterOnSameDay = exam(date = today.minusDays(3), startTime = "14:00", endTime = "16:00", courseName = "晚开始")

        val display = examsForDisplay(
            listOf(earlierOnSameDay, laterOnSameDay),
            LocalDateTime.of(today, java.time.LocalTime.of(18, 0))
        )

        assertEquals(
            listOf("晚开始", "早开始"),
            display.map { it.exam.courseName }
        )
    }

    private fun exam(date: LocalDate, endTime: String): ExamInfo {
        return exam(date = date, startTime = "12:30", endTime = endTime)
    }

    private fun exam(
        date: LocalDate,
        startTime: String,
        endTime: String,
        courseName: String = "数字逻辑"
    ): ExamInfo {
        return ExamInfo(
            id = "id-$date-$startTime-$endTime-$courseName",
            courseName = courseName,
            examDate = date,
            startTime = startTime,
            endTime = endTime,
            location = "06206D",
            seatNumber = "",
            examType = "正常考试",
            note = ""
        )
    }
}
