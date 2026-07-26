package com.glut.schedule

import com.glut.schedule.data.model.ClassPeriod
import com.glut.schedule.data.model.defaultClassPeriods
import com.glut.schedule.data.model.guilinClassPeriods
import com.glut.schedule.data.model.nanningClassPeriods
import com.glut.schedule.data.model.validateClassPeriods
import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.data.settings.ClassPeriodProfile
import com.glut.schedule.data.settings.GuilinSubCampus
import com.glut.schedule.data.settings.decodeClassPeriods
import com.glut.schedule.data.settings.decodeClassPeriodOverrides
import com.glut.schedule.data.settings.encodeClassPeriods
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassPeriodSettingsTest {
    @Test
    fun existingCampusDefaultsAreValid() {
        assertTrue(validateClassPeriods(CampusType.GUILIN, guilinClassPeriods()))
        assertTrue(validateClassPeriods(CampusType.NANNING, nanningClassPeriods()))
    }

    @Test
    fun validationRequiresEveryCampusSectionInOrder() {
        assertFalse(
            validateClassPeriods(
                CampusType.GUILIN,
                guilinClassPeriods().dropLast(1)
            )
        )
        assertFalse(
            validateClassPeriods(
                CampusType.NANNING,
                nanningClassPeriods().mapIndexed { index, period ->
                    if (index == 5) period.copy(section = 7) else period
                }
            )
        )
    }

    @Test
    fun validationRejectsInvalidTimesAndOverlaps() {
        val invalidFormat = guilinClassPeriods().toMutableList().apply {
            this[0] = this[0].copy(startsAt = "8:30")
        }
        val reversed = guilinClassPeriods().toMutableList().apply {
            this[0] = this[0].copy(startsAt = "09:15", endsAt = "09:15")
        }
        val overlap = guilinClassPeriods().toMutableList().apply {
            this[1] = this[1].copy(startsAt = "09:10")
        }

        assertFalse(validateClassPeriods(CampusType.GUILIN, invalidFormat))
        assertFalse(validateClassPeriods(CampusType.GUILIN, reversed))
        assertFalse(validateClassPeriods(CampusType.GUILIN, overlap))
    }

    @Test
    fun encodingRoundTripsUsingCampusValidatedRows() {
        val custom = defaultClassPeriods(CampusType.NANNING).mapIndexed { index, period ->
            if (index == 0) period.copy(startsAt = "08:35", endsAt = "09:15") else period
        }

        val encoded = encodeClassPeriods(custom)

        assertTrue(encoded.all { it.count { character -> character == '\u0000' } == 2 })
        assertEquals(custom, decodeClassPeriods(CampusType.NANNING, encoded))
    }

    @Test
    fun decodingDamagedOrWrongCampusValuesFallsBackToNoOverride() {
        val damaged = encodeClassPeriods(guilinClassPeriods()).toMutableSet().apply {
            remove(first())
            add("1\u000008:30\u0000not-a-time")
        }

        assertNull(decodeClassPeriods(CampusType.GUILIN, damaged))
        assertNull(
            decodeClassPeriods(
                CampusType.NANNING,
                encodeClassPeriods(guilinClassPeriods())
            )
        )
    }

    @Test
    fun decodingRejectsDuplicateSectionsInsteadOfSilentlyDroppingRows() {
        val entries = encodeClassPeriods(nanningClassPeriods()).toMutableSet().apply {
            add("1\u000008:45\u000009:20")
        }

        assertNull(decodeClassPeriods(CampusType.NANNING, entries))
    }

    @Test
    fun eachClassPeriodProfileUsesItsOwnDefaultTemplate() {
        assertEquals("08:30", defaultClassPeriods(ClassPeriodProfile.GUILIN_YANSHAN).first().startsAt)
        assertEquals("08:20", defaultClassPeriods(ClassPeriodProfile.GUILIN_PINGFENG).first().startsAt)
        assertEquals("08:40", defaultClassPeriods(ClassPeriodProfile.NANNING).first().startsAt)
    }

    @Test
    fun legacyGuilinOverrideBelongsOnlyToTheSelectedSubCampus() {
        val legacy = guilinClassPeriods().map {
            if (it.section == 1) it.copy(startsAt = "08:10", endsAt = "08:55") else it
        }

        val overrides = decodeClassPeriodOverrides(
            selectedSubCampus = GuilinSubCampus.PINGFENG,
            legacyGuilinEntries = encodeClassPeriods(legacy),
            yanshanEntries = emptySet(),
            pingfengEntries = emptySet(),
            nanningEntries = emptySet()
        )

        assertNull(overrides[ClassPeriodProfile.GUILIN_YANSHAN])
        assertEquals(legacy, overrides[ClassPeriodProfile.GUILIN_PINGFENG])
    }

    @Test
    fun explicitProfileOverrideWinsOverLegacyGuilinValue() {
        val legacy = guilinClassPeriods()
        val explicitPingfeng = defaultClassPeriods(ClassPeriodProfile.GUILIN_PINGFENG).map {
            if (it.section == 1) it.copy(startsAt = "08:00", endsAt = "08:45") else it
        }

        val overrides = decodeClassPeriodOverrides(
            selectedSubCampus = GuilinSubCampus.PINGFENG,
            legacyGuilinEntries = encodeClassPeriods(legacy),
            yanshanEntries = emptySet(),
            pingfengEntries = encodeClassPeriods(explicitPingfeng),
            nanningEntries = emptySet()
        )

        assertEquals(explicitPingfeng, overrides[ClassPeriodProfile.GUILIN_PINGFENG])
    }
}
