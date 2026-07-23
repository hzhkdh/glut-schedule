package com.glut.schedule

import com.glut.schedule.data.model.ClassPeriod
import com.glut.schedule.data.model.defaultClassPeriods
import com.glut.schedule.data.model.guilinClassPeriods
import com.glut.schedule.data.model.nanningClassPeriods
import com.glut.schedule.data.model.validateClassPeriods
import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.data.settings.decodeClassPeriods
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
}
