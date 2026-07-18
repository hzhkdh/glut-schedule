package com.glut.schedule

import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.ui.navigation.DrawerItem
import com.glut.schedule.ui.navigation.campusDrawerItems
import com.glut.schedule.ui.navigation.otherDrawerItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CampusDrawerItemsTest {
    @Test
    fun guilinCampusItemsAreSeparateAndFinanceIsLast() {
        assertEquals(
            listOf(DrawerItem.AcademicCalendar, DrawerItem.ShuttleBus, DrawerItem.Finance),
            campusDrawerItems(CampusType.GUILIN)
        )
    }

    @Test
    fun nanningKeepsFinanceButHidesGuilinInformation() {
        assertEquals(listOf(DrawerItem.Finance), campusDrawerItems(CampusType.NANNING))
    }

    @Test
    fun financeIsNoLongerInOtherItems() {
        assertFalse(DrawerItem.Finance in otherDrawerItems)
    }
}
