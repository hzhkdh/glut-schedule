package com.glut.schedule

import com.glut.schedule.ui.pages.shouldStackHistoryEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchoolHistoryTimelineLayoutTest {
    @Test fun keepsDateAndTextBesideEachOtherWhenSpaceAllows() {
        assertFalse(shouldStackHistoryEvent(eventWidthDp = 288f, dateWidthDp = 100f, fontScale = 1f))
    }

    @Test fun stacksWhenLongDateLeavesTooLittleSpaceForText() {
        assertTrue(shouldStackHistoryEvent(eventWidthDp = 235f, dateWidthDp = 100f, fontScale = 1f))
    }

    @Test fun stacksAtLargeTextScaleEvenOnWideScreens() {
        assertTrue(shouldStackHistoryEvent(eventWidthDp = 440f, dateWidthDp = 100f, fontScale = 1.2f))
    }
}
