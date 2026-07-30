package com.glut.schedule

import com.glut.schedule.data.settings.PartnerScheduleViewMode
import org.junit.Assert.assertEquals
import org.junit.Test

class PartnerScheduleViewModeTest {

    @Test
    fun storedViewModeDefaultsToCombinedForMissingOrUnknownValues() {
        assertEquals(PartnerScheduleViewMode.COMBINED, PartnerScheduleViewMode.fromStorageValue(null))
        assertEquals(PartnerScheduleViewMode.COMBINED, PartnerScheduleViewMode.fromStorageValue("unknown"))
        assertEquals(PartnerScheduleViewMode.PARTNER, PartnerScheduleViewMode.fromStorageValue("partner"))
    }
}
