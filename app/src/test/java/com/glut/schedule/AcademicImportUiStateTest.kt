package com.glut.schedule

import com.glut.schedule.ui.pages.AcademicImportUiState
import com.glut.schedule.ui.pages.shouldShowAcademicDownloadButton
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcademicImportUiStateTest {
    @Test
    fun downloadButtonOnlyShowsAfterLoginSessionExists() {
        assertFalse(shouldShowAcademicDownloadButton(AcademicImportUiState(hasSession = false)))
        assertTrue(shouldShowAcademicDownloadButton(AcademicImportUiState(hasSession = true)))
    }
}
