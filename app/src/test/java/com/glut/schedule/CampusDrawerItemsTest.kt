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
    fun guilinCampusItemsCombinePublicImagesAndKeepFinanceSeparate() {
        assertEquals(
            listOf(
                DrawerItem.CourseTimeStats,
                DrawerItem.CampusInfo,
                DrawerItem.PartnerSchedule,
                DrawerItem.Finance
            ),
            campusDrawerItems(CampusType.GUILIN)
        )
    }

    @Test
    fun nanningKeepsFinanceButHidesGuilinInformation() {
        assertEquals(
            listOf(
                DrawerItem.CourseTimeStats,
                DrawerItem.PartnerSchedule,
                DrawerItem.Finance
            ),
            campusDrawerItems(CampusType.NANNING)
        )
    }

    /**
     * 研究生与南宁不同：门户确实没有这些模块，但入口**保留在原位**并置灰，
     * 而不是像南宁那样整项消失——用户能一眼看出「菜单存在，只是研究生没有」。
     */
    @Test
    fun graduateKeepsUnsupportedModulesInPlaceForGreyingOut() {
        assertEquals(
            listOf(
                DrawerItem.CourseTimeStats,
                DrawerItem.CampusInfo,
                DrawerItem.PartnerSchedule,
                DrawerItem.Finance
            ),
            campusDrawerItems(CampusType.GRADUATE)
        )
    }

    @Test
    fun financeIsNoLongerInOtherItems() {
        assertFalse(DrawerItem.Finance in otherDrawerItems)
    }
}
