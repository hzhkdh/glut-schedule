package com.glut.schedule

import com.glut.schedule.ui.pages.AcademicImportUiState
import com.glut.schedule.ui.pages.hasConfirmedAcademicLogin
import com.glut.schedule.ui.pages.shouldShowAcademicDownloadButton
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcademicImportUiStateTest {
    @Test
    fun loginPageCookieDoesNotConfirmAcademicLogin() {
        assertFalse(
            hasConfirmedAcademicLogin(
                cookie = "JSESSIONID=abc123; Path=/academic; HttpOnly",
                currentUrl = "https://cas.glut.edu.cn/portal/login.html",
                isLoginFormVisible = true
            )
        )
        assertFalse(
            hasConfirmedAcademicLogin(
                cookie = "JSESSIONID=abc123; Path=/academic; HttpOnly",
                currentUrl = "",
                isLoginFormVisible = true
            )
        )
    }

    @Test
    fun academicFramePageWithLoginFormDoesNotConfirmAcademicLogin() {
        assertFalse(
            hasConfirmedAcademicLogin(
                cookie = "JSESSIONID=abc123; Path=/academic; HttpOnly",
                currentUrl = "http://jw.glut.edu.cn/academic/preGotoAffairFrame.do#/menu",
                isLoginFormVisible = true
            )
        )
    }

    @Test
    fun academicFramePageWithCookieAndNoLoginFormConfirmsAcademicLogin() {
        assertTrue(
            hasConfirmedAcademicLogin(
                cookie = "JSESSIONID=abc123; Path=/academic; HttpOnly",
                currentUrl = "http://jw.glut.edu.cn/academic/preGotoAffairFrame.do#/menu",
                isLoginFormVisible = false
            )
        )
    }

    @Test
    fun downloadButtonOnlyShowsAfterLoginSessionExists() {
        assertFalse(shouldShowAcademicDownloadButton(AcademicImportUiState(hasSession = false)))
        assertTrue(shouldShowAcademicDownloadButton(AcademicImportUiState(hasSession = true)))
    }
}
