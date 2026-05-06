package com.glut.schedule

import com.glut.schedule.service.academic.AcademicWebScripts
import org.junit.Assert.assertTrue
import org.junit.Test

class AcademicWebScriptTest {
    @Test
    fun clickTimetableMenuItemScriptHasExpectedContent() {
        val script = AcademicWebScripts.clickTimetableMenuItem()
        assertTrue(script.contains("本学期课表"))
        assertTrue(script.contains("个人课表"))
        assertTrue(script.indexOf("个人课表") < script.indexOf("本学期课表"))
        assertTrue(script.contains("click"))
        assertTrue(script.contains("MouseEvent"))
        assertTrue(script.contains("closest"))
    }

    @Test
    fun openTimetablePageScriptSearchesCorrectMenuText() {
        val script = AcademicWebScripts.openTimetablePage()
        assertTrue(script.contains("本学期课表"))
        assertTrue(script.contains("个人课表"))
        assertTrue(script.indexOf("个人课表") < script.indexOf("本学期课表"))
        assertTrue(script.contains("click"))
        assertTrue(script.contains("MouseEvent"))
        assertTrue(script.contains("closest"))
    }

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
    fun directTimetableNavigationUsesCurrentUserLinksOrMenuOnly() {
        val script = AcademicWebScripts.navigateToDirectTimetableUrl()

        assertTrue(script.contains("showTimetable.do"))
        assertTrue(script.contains("timetableType=STUDENT"))
        assertTrue(script.contains("个人课表"))
        assertTrue(script.contains("本学期课表"))
        assertTrue(!script.contains("id=712170"))
    }
}
