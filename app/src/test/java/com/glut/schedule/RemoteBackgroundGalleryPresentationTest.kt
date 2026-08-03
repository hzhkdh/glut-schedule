package com.glut.schedule

import com.glut.schedule.ui.pages.canDismissRemoteBackgroundPreview
import com.glut.schedule.ui.pages.remoteBackgroundDownloadBadgeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteBackgroundGalleryPresentationTest {
    @Test
    fun downloadBadgeHidesZeroAndShowsPositiveCount() {
        assertNull(remoteBackgroundDownloadBadgeText(0))
        assertEquals("6", remoteBackgroundDownloadBadgeText(6))
    }

    @Test
    fun previewCannotDismissWhileOriginalIsDownloading() {
        assertTrue(canDismissRemoteBackgroundPreview(isDownloading = false))
        assertFalse(canDismissRemoteBackgroundPreview(isDownloading = true))
    }
}
