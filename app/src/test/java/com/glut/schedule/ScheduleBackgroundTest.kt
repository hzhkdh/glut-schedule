package com.glut.schedule

import com.glut.schedule.ui.components.BackgroundSwitchResult
import com.glut.schedule.ui.components.calculateBitmapSampleSize
import com.glut.schedule.ui.components.calculateDecodeTargetSize
import com.glut.schedule.ui.components.shouldCommitCustomBackgroundUri
import com.glut.schedule.ui.components.shouldUseCustomBackground
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleBackgroundTest {
    @Test
    fun customBackgroundRequiresNonBlankUri() {
        assertFalse(shouldUseCustomBackground(""))
        assertFalse(shouldUseCustomBackground("   "))
        assertTrue(shouldUseCustomBackground("content://images/background"))
    }

    @Test
    fun decodeTargetSizeFitsScreenWithoutUpscaling() {
        assertEquals(1080 to 810, calculateDecodeTargetSize(4000, 3000, 1080, 2400))
        assertEquals(720 to 1280, calculateDecodeTargetSize(720, 1280, 1080, 2400))
    }

    @Test
    fun bitmapSampleSizeUsesPowerOfTwoDownsampling() {
        assertEquals(2, calculateBitmapSampleSize(4000, 3000, 1080, 2400))
        assertEquals(1, calculateBitmapSampleSize(720, 1280, 1080, 2400))
    }

    @Test
    fun customBackgroundUriCommitsOnlyAfterPreloadSuccess() {
        assertEquals(
            BackgroundSwitchResult.Commit,
            shouldCommitCustomBackgroundUri("content://images/background", preloadSucceeded = true)
        )
        assertEquals(
            BackgroundSwitchResult.KeepCurrent,
            shouldCommitCustomBackgroundUri("content://images/background", preloadSucceeded = false)
        )
        assertEquals(
            BackgroundSwitchResult.Clear,
            shouldCommitCustomBackgroundUri("", preloadSucceeded = false)
        )
    }
}
