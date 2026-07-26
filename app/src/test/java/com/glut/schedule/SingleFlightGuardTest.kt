package com.glut.schedule

import com.glut.schedule.ui.SingleFlightGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleFlightGuardTest {
    @Test
    fun rejectsDuplicateUntilCurrentOperationFinishes() {
        val guard = SingleFlightGuard()

        assertTrue(guard.tryStart())
        assertFalse(guard.tryStart())

        guard.finish()
        assertTrue(guard.tryStart())
    }
}
