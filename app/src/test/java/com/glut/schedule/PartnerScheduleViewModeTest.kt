package com.glut.schedule

import com.glut.schedule.data.settings.PartnerScheduleViewMode
import com.glut.schedule.partner.ImportedPartnerProfile
import com.glut.schedule.partner.PartnerCourse
import com.glut.schedule.partner.PartnerIdentityColor
import com.glut.schedule.partner.PartnerScheduleSnapshot
import com.glut.schedule.partner.PartnerScheduleUiState
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class PartnerScheduleViewModeTest {

    @Test
    fun storedViewModeDefaultsToCombinedForMissingOrUnknownValues() {
        assertEquals(PartnerScheduleViewMode.COMBINED, PartnerScheduleViewMode.fromStorageValue(null))
        assertEquals(PartnerScheduleViewMode.COMBINED, PartnerScheduleViewMode.fromStorageValue("unknown"))
        assertEquals(PartnerScheduleViewMode.PARTNER, PartnerScheduleViewMode.fromStorageValue("partner"))
    }

    @Test
    fun combinedModeWithOneSlotComparesMineWithThatPartner() {
        val mine = course("我的课程", PartnerIdentityColor.BLUE)
        val ta1 = profile("ta-1", "TA1课程", PartnerIdentityColor.PINK)

        val state = PartnerScheduleUiState(
            viewMode = PartnerScheduleViewMode.COMBINED,
            ownCourses = listOf(mine),
            profiles = listOf(ta1),
            selectedProfileId = ta1.id
        )

        assertEquals(listOf("我的课程", "TA1课程"), state.displayedCourses.map { it.title })
    }

    @Test
    fun twoSlotsOnlyCompareMineWithTheSelectedPartner() {
        val mine = course("我的课程", PartnerIdentityColor.BLUE)
        val ta1 = profile("ta-1", "TA1课程", PartnerIdentityColor.PINK)
        val ta2 = profile("ta-2", "TA2课程", PartnerIdentityColor.PURPLE)

        val combined = PartnerScheduleUiState(
            viewMode = PartnerScheduleViewMode.COMBINED,
            ownCourses = listOf(mine),
            profiles = listOf(ta1, ta2),
            selectedProfileId = ta2.id
        )
        val partnerOnly = combined.copy(viewMode = PartnerScheduleViewMode.PARTNER)

        assertEquals(listOf("我的课程", "TA2课程"), combined.displayedCourses.map { it.title })
        assertEquals(listOf("TA2课程"), partnerOnly.displayedCourses.map { it.title })
    }

    private fun profile(
        id: String,
        title: String,
        color: PartnerIdentityColor
    ): ImportedPartnerProfile {
        val snapshot = PartnerScheduleSnapshot(
            identityColor = color,
            campus = "guilin-yanshan",
            semesterStartMonday = LocalDate.of(2026, 9, 7),
            semesterEndDate = LocalDate.of(2027, 1, 17),
            courses = listOf(course(title, color))
        )
        return ImportedPartnerProfile(id, id, snapshot, color)
    }

    private fun course(title: String, color: PartnerIdentityColor): PartnerCourse = PartnerCourse(
        id = title,
        title = title,
        room = null,
        teacher = null,
        dayOfWeek = 1,
        startSection = 1,
        endSection = 2,
        weeks = listOf(1),
        startTime = "08:30",
        endTime = "10:05",
        ownerColor = color
    )
}
