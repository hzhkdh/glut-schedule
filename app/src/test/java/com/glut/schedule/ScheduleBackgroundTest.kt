package com.glut.schedule

import com.glut.schedule.ui.components.BackgroundSwitchResult
import com.glut.schedule.ui.components.BackgroundImageOrientation
import com.glut.schedule.ui.components.BuiltInScheduleBackground
import com.glut.schedule.ui.components.ImageCropRegion
import com.glut.schedule.data.model.NormalizedCropRect
import com.glut.schedule.data.model.snapBackgroundDimAmount
import com.glut.schedule.ui.components.calculateCropTransform
import com.glut.schedule.ui.components.calculateBackgroundDecodePlan
import com.glut.schedule.ui.components.calculateBitmapSampleSize
import com.glut.schedule.ui.components.backgroundBitmapByteSize
import com.glut.schedule.ui.components.backgroundCacheKey
import com.glut.schedule.ui.components.backgroundDiskCacheFile
import com.glut.schedule.ui.components.sha256Hex
import com.glut.schedule.ui.components.trimBackgroundDiskCache
import com.glut.schedule.ui.components.calculateNormalizedCenterCrop
import com.glut.schedule.ui.components.calculateDecodeTargetSize
import com.glut.schedule.ui.components.calculatePreviewSampleSize
import com.glut.schedule.ui.components.calculateLegacyRegionDecodePlan
import com.glut.schedule.ui.components.mapOrientedCropToRaw
import com.glut.schedule.ui.components.cropRectFromTransform
import com.glut.schedule.ui.components.shouldCommitCustomBackgroundUri
import com.glut.schedule.ui.components.shouldUseCustomBackground
import com.glut.schedule.ui.components.scheduleFirstFrameReady
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScheduleBackgroundTest {
    @Test
    fun customBackgroundKeepsLaunchWindowUntilTargetBitmapIsReady() {
        assertFalse(
            scheduleFirstFrameReady(
                isInitialized = false,
                backgroundUri = "content://gallery/artwork",
                customBitmapAvailable = false
            )
        )
        assertFalse(
            scheduleFirstFrameReady(
                isInitialized = true,
                backgroundUri = "content://gallery/artwork",
                customBitmapAvailable = false
            )
        )
        assertTrue(
            scheduleFirstFrameReady(
                isInitialized = true,
                backgroundUri = "content://gallery/artwork",
                customBitmapAvailable = true
            )
        )
        assertTrue(
            scheduleFirstFrameReady(
                isInitialized = true,
                backgroundUri = BuiltInScheduleBackground.FLOWER.storageValue,
                customBitmapAvailable = false
            )
        )
    }
    @Test
    fun legacyPanoramaSamplingUsesSelectedRegionInsteadOfWholeImage() {
        val plan = calculateLegacyRegionDecodePlan(
            rawWidth = 24000,
            rawHeight = 6000,
            targetWidth = 1080,
            targetHeight = 2400,
            crop = NormalizedCropRect(0.44375f, 0f, 0.55625f, 1f),
            orientation = BackgroundImageOrientation.NORMAL
        )

        assertEquals(ImageCropRegion(10650, 0, 13350, 6000), plan.rawRegion)
        assertEquals(2700, plan.orientedCropWidth)
        assertEquals(6000, plan.orientedCropHeight)
        assertEquals(2, plan.sampleSize)
    }

    @Test
    fun rotatedVisualCropMapsBackToEncodedImageCoordinates() {
        val rawCrop = mapOrientedCropToRaw(
            crop = NormalizedCropRect(0.1f, 0.2f, 0.4f, 0.8f),
            orientation = BackgroundImageOrientation.ROTATE_90
        )

        assertEquals(0.2f, rawCrop.left, 0.00001f)
        assertEquals(0.6f, rawCrop.top, 0.00001f)
        assertEquals(0.8f, rawCrop.right, 0.00001f)
        assertEquals(0.9f, rawCrop.bottom, 0.00001f)
    }

    @Test
    fun rotatedPanoramaStillSamplesFromVisualCropDimensions() {
        val plan = calculateLegacyRegionDecodePlan(
            rawWidth = 6000,
            rawHeight = 24000,
            targetWidth = 1080,
            targetHeight = 2400,
            crop = NormalizedCropRect(0.44375f, 0f, 0.55625f, 1f),
            orientation = BackgroundImageOrientation.ROTATE_90
        )

        assertEquals(ImageCropRegion(0, 10650, 6000, 13350), plan.rawRegion)
        assertEquals(2700, plan.orientedCropWidth)
        assertEquals(6000, plan.orientedCropHeight)
        assertEquals(2, plan.sampleSize)
    }

    @Test
    fun centerCropIsStoredAsNormalizedSourceCoordinates() {
        val crop = calculateNormalizedCenterCrop(4000, 3000, 1080, 2400)

        assertEquals(0.33125f, crop.left, 0.00001f)
        assertEquals(0f, crop.top, 0.00001f)
        assertEquals(0.66875f, crop.right, 0.00001f)
        assertEquals(1f, crop.bottom, 0.00001f)
    }

    @Test
    fun cropTransformRoundTripsSavedCrop() {
        val expected = NormalizedCropRect(0.125f, 0f, 0.375f, 1f)
        val transform = calculateCropTransform(
            crop = expected,
            imageWidth = 2000f,
            imageHeight = 1000f,
            viewportWidth = 500f,
            viewportHeight = 1000f
        )

        val actual = cropRectFromTransform(
            imageWidth = 2000f,
            imageHeight = 1000f,
            viewportWidth = 500f,
            viewportHeight = 1000f,
            zoom = transform.zoom,
            offsetX = transform.offsetX,
            offsetY = transform.offsetY
        )

        assertEquals(expected.left, actual.left, 0.00001f)
        assertEquals(expected.top, actual.top, 0.00001f)
        assertEquals(expected.right, actual.right, 0.00001f)
        assertEquals(expected.bottom, actual.bottom, 0.00001f)
    }

    @Test
    fun dimAmountIsClampedAndSnappedToFivePercentSteps() {
        assertEquals(0f, snapBackgroundDimAmount(-0.2f), 0.00001f)
        assertEquals(0.55f, snapBackgroundDimAmount(0.534f), 0.00001f)
        assertEquals(0.8f, snapBackgroundDimAmount(1f), 0.00001f)
    }

    @Test
    fun cacheKeyChangesWhenSameImageIsRecropped() {
        val first = backgroundCacheKey(
            "content://images/background",
            NormalizedCropRect(0f, 0f, 0.5f, 1f),
            1080,
            2400
        )
        val second = backgroundCacheKey(
            "content://images/background",
            NormalizedCropRect(0.5f, 0f, 1f, 1f),
            1080,
            2400
        )

        assertTrue(first != second)
    }

    @Test
    fun diskCacheFileSeparatesSourceAndCropWithoutExposingUri() {
        val directory = File("build/tmp/background-cache-test")
        val uri = "content://private/gallery/image"
        val first = backgroundDiskCacheFile(directory, uri, "$uri|crop-a|1080x2400")
        val second = backgroundDiskCacheFile(directory, uri, "$uri|crop-b|1080x2400")

        assertTrue(first.name != second.name)
        assertTrue(first.name.startsWith(sha256Hex(uri)))
        assertFalse(first.name.contains("content://"))
    }

    @Test
    fun diskCacheTrimDeletesOldestFilesAndAbandonedTemps() {
        val directory = File("build/tmp/background-cache-trim-${System.nanoTime()}").apply { mkdirs() }
        try {
            val oldest = directory.resolve("old.render").apply { writeBytes(ByteArray(6)); setLastModified(1L) }
            val newest = directory.resolve("new.render").apply { writeBytes(ByteArray(6)); setLastModified(2L) }
            val temporary = directory.resolve("abandoned.tmp").apply { writeBytes(ByteArray(2)) }

            trimBackgroundDiskCache(directory, maximumBytes = 6L)

            assertFalse(oldest.exists())
            assertTrue(newest.exists())
            assertFalse(temporary.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun bitmapCacheCostIsMeasuredInBytes() {
        assertEquals(1080 * 2400 * 4, backgroundBitmapByteSize(1080, 2400))
        assertEquals(Int.MAX_VALUE, backgroundBitmapByteSize(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun customBackgroundRequiresNonBlankUri() {
        assertFalse(shouldUseCustomBackground(""))
        assertFalse(shouldUseCustomBackground("   "))
        assertTrue(shouldUseCustomBackground("content://images/background"))
    }

    @Test
    fun builtInBackgroundMarkersDoNotUseExternalImageDecoder() {
        assertFalse(shouldUseCustomBackground(BuiltInScheduleBackground.FLOWER.storageValue))
        assertEquals(
            BuiltInScheduleBackground.FLOWER,
            BuiltInScheduleBackground.fromStorageValue("")
        )
        assertEquals(
            BuiltInScheduleBackground.FLOWER,
            BuiltInScheduleBackground.fromStorageValue("builtin://flower")
        )
        assertEquals(null, BuiltInScheduleBackground.fromStorageValue("content://images/background"))
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
    fun cropPreviewCapsVeryLargeImagesWithPowerOfTwoSampling() {
        assertEquals(8, calculatePreviewSampleSize(12000, 9000, 2048))
        assertEquals(1, calculatePreviewSampleSize(1080, 1920, 2048))
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
