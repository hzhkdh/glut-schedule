package com.glut.schedule

import com.glut.schedule.service.academic.AcademicWebScripts
import org.junit.Assert.assertTrue
import org.junit.Test

class AcademicWebScriptTest {
    @Test
    fun currentHtmlScriptReturnsDocumentHtml() {
        val script = AcademicWebScripts.currentPageHtml()
        assertTrue(script.contains("document.documentElement.outerHTML"))
        assertTrue(script.contains("iframe,frame"))
        assertTrue(script.contains("contentWindow.document"))
    }

    @Test
    fun interceptApiResponsesScriptHooksXHR() {
        val script = AcademicWebScripts.interceptApiResponses()
        assertTrue(script.contains("XMLHttpRequest"))
        assertTrue(script.contains("__scheduleAppResponses"))
        assertTrue(script.contains("glut.edu.cn"))
    }

    @Test
    fun detectLoginFormScriptLooksForPasswordInputsAndLoginText() {
        val script = AcademicWebScripts.detectLoginForm()

        assertTrue(script.contains("input"))
        assertTrue(script.contains("password"))
        assertTrue(script.contains("账号登录"))
        assertTrue(script.contains("iframe,frame"))
    }

    @Test
    fun captureLoginCredentialsScriptHooksSubmitAndPasswordClick() {
        val script = AcademicWebScripts.captureLoginCredentials()

        assertTrue(script.contains("AndroidCredentialCapture"))
        assertTrue(script.contains("saveCredentials"))
        assertTrue(script.contains("submit"))
        assertTrue(script.contains("click"))
        assertTrue(script.contains("password"))
        assertTrue(script.contains("j_username"))
        assertTrue(script.contains("j_password"))
    }
}
