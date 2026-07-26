package com.glut.schedule

import com.glut.schedule.data.model.ExamInfo
import com.glut.schedule.ui.pages.InitialExamImportData
import com.glut.schedule.ui.pages.importInitialExams
import com.glut.schedule.ui.pages.importCompletionMessage
import com.glut.schedule.ui.pages.isAuthenticatedNanningResponse
import com.glut.schedule.ui.pages.shouldClearAcademicData
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DirectLoginSafetyTest {

    @Test
    fun onlyChangingToAnotherKnownStudentClearsAcademicCache() {
        assertFalse(shouldClearAcademicData("", "20240001"))
        assertFalse(shouldClearAcademicData(" 20240001 ", "20240001"))
        assertTrue(shouldClearAcademicData("20240001", "20240002"))
    }

    @Test
    fun successfulHttpCodeWithLoginFormIsNotAuthenticated() {
        assertFalse(
            isAuthenticatedNanningResponse(
                httpCode = 200,
                location = "",
                body = """<form action="affairLogin.do"><input name="j_username"></form>"""
            )
        )
        assertFalse(isAuthenticatedNanningResponse(200, "", "<html>普通页面</html>"))
        assertTrue(
            isAuthenticatedNanningResponse(
                httpCode = 200,
                location = "",
                body = """{"schoolCalendarStartDate":"2026-09-07","whichweek":1}"""
            )
        )
        assertTrue(
            isAuthenticatedNanningResponse(
                httpCode = 302,
                location = "/academic/personal/framePage.do",
                body = ""
            )
        )
    }

    @Test
    fun completionMessageNamesFailedModules() {
        assertEquals("导入完成", importCompletionMessage(emptyList()))
        assertEquals(
            "部分导入失败：成绩、教学计划；已保留原缓存",
            importCompletionMessage(listOf("成绩", "教学计划", "成绩"))
        )
    }

    @Test
    fun emptyInitialExamResultDoesNotOverwriteExistingCache() = runTest {
        var replacedExams: List<ExamInfo>? = null
        var savedUrl: String? = null

        val result = importInitialExams(
            fetch = {
                Result.success(
                    InitialExamImportData(
                        successfulUrl = "http://jw.glut.edu.cn/academic/empty",
                        exams = emptyList()
                    )
                )
            },
            replaceExams = { replacedExams = it },
            saveSuccessfulUrl = { savedUrl = it }
        )

        assertTrue(result.isFailure)
        assertEquals(null, replacedExams)
        assertEquals(null, savedUrl)
    }

    @Test
    fun successfulInitialExamResultPersistsExamsAndReusableUrl() = runTest {
        val exam = ExamInfo(
            id = "exam-1",
            courseName = "高等数学",
            examDate = LocalDate.of(2026, 7, 30),
            startTime = "09:00",
            endTime = "11:00",
            location = "03101",
            seatNumber = "18",
            examType = "期末考试",
            note = ""
        )
        var replacedExams: List<ExamInfo>? = null
        var savedUrl = ""

        val result = importInitialExams(
            fetch = {
                Result.success(
                    InitialExamImportData(
                        successfulUrl = "http://jw.glut.edu.cn/academic/student/examination/queryExam.do",
                        exams = listOf(exam)
                    )
                )
            },
            replaceExams = { replacedExams = it },
            saveSuccessfulUrl = { savedUrl = it }
        )

        assertEquals(1, result.getOrThrow())
        assertEquals(listOf(exam), replacedExams)
        assertEquals(
            "http://jw.glut.edu.cn/academic/student/examination/queryExam.do",
            savedUrl
        )
    }
}
