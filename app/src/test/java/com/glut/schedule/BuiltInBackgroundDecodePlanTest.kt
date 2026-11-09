package com.glut.schedule

import com.glut.schedule.ui.components.calculateBuiltInBackgroundDecodePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInBackgroundDecodePlanTest {
    @Test fun veryLargeScreenStillUsesBoundedBackgroundMemory() {
        val plan = calculateBuiltInBackgroundDecodePlan(3840, 4770, 2160, 4800)
        assertTrue("极高分屏也不能为装饰性背景分配近乎原图的70MiB", plan.byteSize <= 24_000_000L)
        assertEquals(0.45, plan.decodedWidth.toDouble() / plan.decodedHeight, 0.002)
    }

    @Test fun tallScreenKeepsEnoughPixelsAndAvoidsFullOriginal() {
        val plan = calculateBuiltInBackgroundDecodePlan(3840, 4770, 1080, 2400)
        assertTrue(plan.decodedWidth >= 1080)
        assertTrue(plan.decodedHeight >= 2400)
        assertEquals(0, plan.region.top)
        assertTrue(plan.region.left > 0)
        assertTrue(plan.byteSize < 24L * 1024 * 1024)
    }

    @Test fun wideTabletCropsVerticallyBeforeDecode() {
        val plan = calculateBuiltInBackgroundDecodePlan(3840, 4770, 2560, 1600)
        assertEquals(3840, plan.region.width)
        assertTrue(plan.region.top > 0)
        assertEquals(1, plan.sampleSize)
        assertTrue(plan.decodedWidth >= 2560 && plan.decodedHeight >= 1600)
        assertTrue(plan.byteSize < 40L * 1024 * 1024)
    }

    @Test fun imageAndPdfTargetsNeverChooseBlurrySample() {
        for ((width, height) in listOf(1080 to 3156, 1190 to 1684, 720 to 2104)) {
            val plan = calculateBuiltInBackgroundDecodePlan(3840, 4770, width, height)
            assertTrue(plan.decodedWidth >= width && plan.decodedHeight >= height)
            assertTrue(plan.byteSize < 24L * 1024 * 1024)
            assertTrue(plan.region.width / (plan.sampleSize * 2) < width ||
                plan.region.height / (plan.sampleSize * 2) < height)
        }
    }

    @Test fun sourceSmallerThanTargetIsNotArtificiallyUpscaled() {
        val plan = calculateBuiltInBackgroundDecodePlan(400, 600, 1080, 1620)
        assertEquals(1, plan.sampleSize)
        assertEquals(400, plan.decodedWidth)
        assertEquals(600, plan.decodedHeight)
        assertEquals(400L * 600 * 4, plan.byteSize)
    }
}
