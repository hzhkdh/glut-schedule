package com.glut.schedule

import com.glut.schedule.service.academic.AcademicExamService
import com.glut.schedule.service.academic.ApiProbeService
import com.glut.schedule.service.parser.GlutExamParser
import org.junit.Assert.assertEquals
import org.junit.Test

class AcademicExamServiceTest {
    @Test
    fun selectExamResultPrefersParseableExamJsonFromProbeResults() {
        val service = AcademicExamService(GlutExamParser())
        val examJson = """
            {"data":[{"courseName":"高等数学","examDate":"2026-06-20","examTime":"09:00-11:00","examRoom":"03101","seatNumber":"18"}]}
        """.trimIndent()
        val result = ApiProbeService.ProbeResult(
            url = "http://jw.glut.edu.cn/academic/student/examination/queryExam.do",
            method = "POST",
            httpCode = 200,
            contentType = "application/json",
            body = examJson,
            bodyLength = examJson.length
        )

        val selected = service.selectExamDataFromProbeResults(listOf(result))

        assertEquals("http://jw.glut.edu.cn/academic/student/examination/queryExam.do", selected?.url)
        assertEquals(1, selected?.exams?.size)
        assertEquals("高等数学", selected?.exams?.first()?.courseName)
    }
}
