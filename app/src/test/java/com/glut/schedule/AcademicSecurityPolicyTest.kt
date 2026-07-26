package com.glut.schedule

import com.glut.schedule.service.academic.AcademicUrlPolicy
import com.glut.schedule.service.academic.ApiProbeService
import com.glut.schedule.ui.pages.effectiveRememberPassword
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AcademicSecurityPolicyTest {
    @Test
    fun importProbeUsesOnlyRequiredEndpoints() {
        val requests = ApiProbeService.buildImportProbeRequests("http://jw.glut.edu.cn")

        assertTrue(requests.size <= 8)
        assertFalse(requests.any { (url, _) ->
            url.contains("currentTodayPlan") ||
                url.contains("myTodo") ||
                url.contains("/student/timetable/") ||
                url.contains("/student/coursetable/")
        })
        assertTrue(requests.any { (url, _) -> url.contains("currcourse.jsdo") })
        assertTrue(requests.any { (url, _) -> url.contains("studentQueryAllExam.do") })
    }


    @Test
    fun officialAcademicAndOaUrlsAreAllowed() {
        assertTrue(AcademicUrlPolicy.isAllowedSessionUrl("http://jw.glut.edu.cn/academic/personal/framePage.do"))
        assertTrue(AcademicUrlPolicy.isAllowedSessionUrl("http://jw.glutnn.cn/academic/index_frame.jsp"))
        assertTrue(AcademicUrlPolicy.isAllowedSessionUrl("http://ca.glut.edu.cn:8888/zfca/tojw"))
    }

    @Test
    fun externalHostLookalikeAndUnexpectedPortAreRejected() {
        assertFalse(AcademicUrlPolicy.isAllowedSessionUrl("http://evil.example/academic/personal/framePage.do"))
        assertFalse(AcademicUrlPolicy.isAllowedSessionUrl("http://jw.glut.edu.cn.evil.example/academic/personal/framePage.do"))
        assertFalse(AcademicUrlPolicy.isAllowedSessionUrl("http://jw.glut.edu.cn:8888/academic/personal/framePage.do"))
        assertFalse(AcademicUrlPolicy.isAllowedSessionUrl("https://jw.glut.edu.cn/academic/personal/framePage.do"))
    }

    @Test
    fun redirectsAreResolvedThenValidated() {
        assertEquals(
            "http://jw.glut.edu.cn/academic/preGotoAffairFrame.do",
            AcademicUrlPolicy.resolveAllowedRedirect(
                "http://ca.glut.edu.cn:8888/zfca/tojw",
                "http://jw.glut.edu.cn/academic/preGotoAffairFrame.do"
            )
        )
        assertEquals(
            "http://jw.glut.edu.cn/academic/personal/framePage.do",
            AcademicUrlPolicy.resolveAllowedRedirect(
                "http://jw.glut.edu.cn/academic/preGotoAffairFrame.do",
                "/academic/personal/framePage.do"
            )
        )
        assertNull(
            AcademicUrlPolicy.resolveAllowedRedirect(
                "http://jw.glut.edu.cn/academic/preGotoAffairFrame.do",
                "http://attacker.example/collect"
            )
        )
    }

    @Test
    fun menuParserDropsExternalExamLinksBeforeTheyCanReceiveCookies() {
        val body = """
            {
              "children": [
                {"moduleName":"考试安排","moduleLink":"http://attacker.example/collect"},
                {"moduleName":"考试查询","moduleLink":"/academic/student/examination/queryExam.do"}
              ]
            }
        """.trimIndent()

        assertEquals(
            listOf("http://jw.glut.edu.cn/academic/student/examination/queryExam.do"),
            ApiProbeService.extractExamUrlsFromMenuResponse(body)
        )
    }

    @Test
    fun rememberPasswordRequiresEncryptedStorage() {
        assertTrue(effectiveRememberPassword(requested = true, secureStorageAvailable = true))
        assertFalse(effectiveRememberPassword(requested = true, secureStorageAvailable = false))
        assertFalse(effectiveRememberPassword(requested = false, secureStorageAvailable = true))
    }
}
