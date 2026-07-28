package com.glut.schedule

import com.glut.schedule.ui.pages.selectedWeekAfterCalendarRefresh
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ScheduleRefreshWeekSelectionTest {
    @Test
    fun refreshPreservesSelectedWeekWhenNewCalendarStillContainsIt() {
        assertEquals(
            8,
            selectedWeekAfterCalendarRefresh(
                selectedWeek = 8,
                semesterStartMonday = LocalDate.of(2026, 9, 7),
                semesterEndDate = LocalDate.of(2027, 1, 24)
            )
        )
    }

    @Test
    fun refreshClampsSelectedWeekOnlyWhenNewCalendarIsShorter() {
        assertEquals(
            4,
            selectedWeekAfterCalendarRefresh(
                selectedWeek = 12,
                semesterStartMonday = LocalDate.of(2026, 9, 7),
                semesterEndDate = LocalDate.of(2026, 10, 4)
            )
        )
    }
}
