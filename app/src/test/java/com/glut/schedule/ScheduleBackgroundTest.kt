package com.glut.schedule

import com.glut.schedule.ui.components.BackgroundSwitchResult
import com.glut.schedule.ui.components.ImageCropRegion
import com.glut.schedule.ui.components.calculateBackgroundDecodePlan
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
        assertEquals(1080 to 2400, calculateDecodeTargetSize(1350, 3000, 1080, 2400))
        assertEquals(720 to 1280, calculateDecodeTargetSize(720, 1280, 1080, 2400))
    }

    @Test
    fun landscapeBackgroundIsCenterCroppedAtNativeScreenResolution() {
        val plan = calculateBackgroundDecodePlan(4000, 3000, 1080, 2400)

        assertEquals(3200, plan.scaledWidth)
        assertEquals(2400, plan.scaledHeight)
        assertEquals(ImageCropRegion(1060, 0, 2140, 2400), plan.crop)
        assertEquals(1080, plan.outputWidth)
        assertEquals(2400, plan.outputHeight)
    }

    @Test
    fun smallBackgroundIsCroppedWithoutDecodeUpscaling() {
        val plan = calculateBackgroundDecodePlan(720, 1280, 1080, 2400)

        assertEquals(720, plan.scaledWidth)
        assertEquals(1280, plan.scaledHeight)
        assertEquals(ImageCropRegion(72, 0, 648, 1280), plan.crop)
        assertEquals(576, plan.outputWidth)
        assertEquals(1280, plan.outputHeight)
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
