package com.glut.schedule

import com.glut.schedule.service.academic.AcademicImportConfig
import com.glut.schedule.service.academic.hasUsableAcademicCookie
import com.glut.schedule.service.academic.isAcademicPage
import com.glut.schedule.service.academic.sanitizeDebugUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AcademicImportTest {
    @Test
    fun academicPageDetectionOnlyAcceptsGlutAcademicHost() {
        assertTrue(isAcademicPage("http://jw.glut.edu.cn/academic/preGotoAffairFrame.do#/menu"))
        assertTrue(isAcademicPage("http://jw.glut.edu.cn/academic/manager/coursearrange/showTimetable.do?id=1"))
        assertFalse(isAcademicPage("https://cas.glut.edu.cn/portal/login.html"))
        assertFalse(isAcademicPage("http://example.com/academic/preGotoAffairFrame.do"))
    }

    @Test
    fun cookieIsUsableWhenItContainsSessionLikeToken() {
        assertTrue(hasUsableAcademicCookie("JSESSIONID=abc123; Path=/academic; HttpOnly"))
        assertTrue(hasUsableAcademicCookie("route=server1; JSESSIONID=abc123"))
        assertFalse(hasUsableAcademicCookie(""))
        assertFalse(hasUsableAcademicCookie("remember-me=false"))
    }

    @Test
    fun importConfigPointsToKnownAcademicPages() {
        assertTrue(AcademicImportConfig.loginUrl.contains("jw.glut.edu.cn/academic"))
        assertTrue(AcademicImportConfig.timetableUrlPatterns.any { it.containsMatchIn("showTimetable.do?id=123&timetableType=STUDENT") })
    }

    @Test
    fun directTimetableUrlIncludesRequiredPersonalTimetableParameters() {
        val url = AcademicImportConfig.directTimetableUrl

        assertEquals("712170", AcademicImportConfig.extractStudentId(url))
        assertEquals("46", AcademicImportConfig.extractYearId(url))
        assertEquals("1", AcademicImportConfig.extractTermId(url))
        assertTrue(url.contains("timetableType=STUDENT"))
        assertTrue(url.contains("sectionType=BASE"))
    }

    @Test
    fun directTimetableUrlBuilderKeepsStudentYearAndTermTogether() {
        val url = AcademicImportConfig.buildStudentTimetableUrl(
            studentId = "100",
            yearId = "47",
            termId = "2"
        )

        assertTrue(url.startsWith("http://jw.glut.edu.cn/academic/manager/coursearrange/showTimetable.do?"))
        assertEquals("100", AcademicImportConfig.extractStudentId(url))
        assertEquals("47", AcademicImportConfig.extractYearId(url))
        assertEquals("2", AcademicImportConfig.extractTermId(url))
        assertTrue(url.contains("timetableType=STUDENT"))
        assertTrue(url.contains("sectionType=BASE"))
    }

    @Test
    fun debugUrlSanitizerRedactsLoginCredentials() {
        val sanitized = sanitizeDebugUrl(
            "http://jw.glut.edu.cn/academic/j_acegi_security_check?j_username=3242050858113&j_password=secret&j_captcha=undefined"
        )

        assertFalse(sanitized.contains("3242050858113"))
        assertFalse(sanitized.contains("secret"))
        assertTrue(sanitized.contains("j_username=<redacted>"))
        assertTrue(sanitized.contains("j_password=<redacted>"))
    }
}
