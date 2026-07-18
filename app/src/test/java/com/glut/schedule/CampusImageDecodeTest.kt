package com.glut.schedule

import com.glut.schedule.ui.pages.calculateCampusImageSampleSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CampusImageDecodeTest {
    @Test
    fun largeImagesAreSampledBelowTheDecodedPixelLimit() {
        assertEquals(2, calculateCampusImageSampleSize(4_000, 4_000, 8_000_000))
        assertEquals(4, calculateCampusImageSampleSize(10_000, 4_000, 8_000_000))
    }

    @Test
    fun invalidImageBoundsAreRejected() {
        assertNull(calculateCampusImageSampleSize(0, 800, 8_000_000))
        assertNull(calculateCampusImageSampleSize(800, -1, 8_000_000))
    }
}
