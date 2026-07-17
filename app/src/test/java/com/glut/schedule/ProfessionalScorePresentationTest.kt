package com.glut.schedule

import com.glut.schedule.ui.pages.formatProfessionalScoreNumber
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfessionalScorePresentationTest {
    @Test
    fun weightedScoreKeepsTwoDecimalPrecisionAndTrimsTrailingZeros() {
        assertEquals("23.75", formatProfessionalScoreNumber(95.0 * 0.25))
        assertEquals("23.5", formatProfessionalScoreNumber(23.50))
        assertEquals("20", formatProfessionalScoreNumber(20.00))
    }
}
