package com.glut.schedule

import com.glut.schedule.service.chaoxing.*
import org.junit.Assert.*
import org.junit.Test

class HomeworkDisplayTest {
    @Test fun combinesSubjectAndExactStatus() {
        val a = CachedHomework("1", "1", "同名课", "练习", "未交", null)
        val b = a.copy(classId = "2", status = "待批阅")
        assertEquals(listOf(a), filterHomework(listOf(a, b), "未交", a.subjectKey()))
        assertTrue(filterHomework(listOf(a, b), "未交", b.subjectKey()).isEmpty())
        assertEquals(2, filterHomework(listOf(a, b), "", "").size)
    }
    @Test fun remainingTimeHandlesDeadlineEdgesAndSubmittedWork() {
        assertEquals("已截止", remainingHomeworkTime(1000, "未交", 1000))
        assertEquals("即将截止", remainingHomeworkTime(2000, "未交", 1000))
        assertEquals(null, remainingHomeworkTime(null, "未交", 1000))
        assertEquals(null, remainingHomeworkTime(2000, "待批阅", 1000))
        assertEquals("剩余 1 天 0 小时", remainingHomeworkTime(86_401_000, "未交", 1000))
    }
}
