package com.glut.schedule

import com.glut.schedule.ui.components.shouldUseCustomBackground
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
}
