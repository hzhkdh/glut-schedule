package com.glut.schedule

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.glut.schedule.ui.pages.canLeaveRemoteArtwork
import com.glut.schedule.ui.pages.constrainArtworkOffset
import com.glut.schedule.ui.pages.remoteBackgroundCacheBadgeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteBackgroundGalleryPresentationTest {
    @Test
    fun cacheBadgeHidesZeroAndShowsPositiveCount() {
        assertNull(remoteBackgroundCacheBadgeText(0))
        assertEquals("6", remoteBackgroundCacheBadgeText(6))
    }

    @Test
    fun artworkCannotCloseWhileOriginalIsDownloadingOrSaving() {
        assertTrue(canLeaveRemoteArtwork(isDownloading = false, isSaving = false))
        assertFalse(canLeaveRemoteArtwork(isDownloading = true, isSaving = false))
        assertFalse(canLeaveRemoteArtwork(isDownloading = false, isSaving = true))
    }

    @Test
    fun artworkPanIsDisabledAtBaseScaleAndClampedWhenZoomed() {
        assertEquals(
            Offset.Zero,
            constrainArtworkOffset(Offset(120f, -80f), scale = 1f, viewport = IntSize(300, 500))
        )
        assertEquals(
            Offset(300f, -500f),
            constrainArtworkOffset(Offset(999f, -999f), scale = 3f, viewport = IntSize(300, 500))
        )
    }
}
