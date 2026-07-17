package com.glut.schedule

import com.glut.schedule.ui.navigation.DrawerItem
import com.glut.schedule.ui.navigation.prepareDrawerSelection
import org.junit.Assert.assertEquals
import org.junit.Test

class DrawerNavigationTest {
    @Test
    fun enteringProfessionalScoreResetsAcademicYearBeforeReturningDestination() {
        var resetCalls = 0

        val destination = prepareDrawerSelection(
            current = DrawerItem.Score,
            target = DrawerItem.ProfessionalScore,
            onProfessionalScoreEntered = { resetCalls++ }
        )

        assertEquals(1, resetCalls)
        assertEquals(DrawerItem.ProfessionalScore, destination)
    }

    @Test
    fun stayingOnProfessionalScoreDoesNotResetManualSelectionAgain() {
        var resetCalls = 0

        prepareDrawerSelection(
            current = DrawerItem.ProfessionalScore,
            target = DrawerItem.ProfessionalScore,
            onProfessionalScoreEntered = { resetCalls++ }
        )

        assertEquals(0, resetCalls)
    }
}
