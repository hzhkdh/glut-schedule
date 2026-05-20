package com.glut.schedule

import com.glut.schedule.service.academic.shouldUseExistingAcademicCookie
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcademicLoginPolicyTest {
    @Test
    fun refreshUsesExistingWebViewCookieBeforeSilentPasswordLogin() {
        assertTrue(shouldUseExistingAcademicCookie("JSESSIONID=abc123; Path=/academic"))
        assertTrue(shouldUseExistingAcademicCookie("route=server1; CASTGC=ticket"))
        assertFalse(shouldUseExistingAcademicCookie(""))
        assertFalse(shouldUseExistingAcademicCookie("remember-me=false"))
    }
}
