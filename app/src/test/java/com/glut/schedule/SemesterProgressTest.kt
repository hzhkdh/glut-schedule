package com.glut.schedule

import com.glut.schedule.ui.pages.calculateSemesterProgress
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class SemesterProgressTest {
    @Test
    fun historicalSemesterProgressUsesHistoricalStartDate() {
        val progress = calculateSemesterProgress(
            today = LocalDate.of(2025, 3, 10),
            semesterStartDate = LocalDate.of(2024, 9, 2),
            semesterEndDate = LocalDate.of(2025, 1, 12)
        )

        assertEquals(133L, progress.elapsedDays)
        assertEquals(0L, progress.remainingDays)
        assertEquals(1f, progress.percent)
    }
}
