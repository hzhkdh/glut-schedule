package com.glut.schedule

import com.glut.schedule.ui.pages.importCompletionMessage
import com.glut.schedule.ui.pages.isAuthenticatedNanningResponse
import com.glut.schedule.ui.pages.shouldClearAcademicData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
