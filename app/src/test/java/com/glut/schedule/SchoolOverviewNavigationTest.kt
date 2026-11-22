package com.glut.schedule

import com.glut.schedule.ui.navigation.SchoolOverviewDestination
import com.glut.schedule.ui.navigation.SchoolOverviewNavigation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SchoolOverviewNavigationTest {
    @Test
    fun documentsReturnToTheLastTopicAfterEnteringCampusInfo() {
        val navigation = SchoolOverviewNavigation()
            .open(SchoolOverviewDestination.HISTORY)
            .open(SchoolOverviewDestination.CAMPUS_INFO)

        assertEquals("校园信息", navigation.destination.label)
        assertEquals("历史沿革", navigation.showDocuments().destination.label)
    }

    @Test
    fun documentsOpenAtLeadersAndTabsSelectTheirOwnDestination() {
        assertEquals(SchoolOverviewDestination.LEADERS, SchoolOverviewNavigation().destination)
        val navigation = SchoolOverviewNavigation()
            .open(SchoolOverviewDestination.HISTORY)
            .open(SchoolOverviewDestination.CAMPUS_INFO)
            .open(SchoolOverviewDestination.SPIRIT)

        assertEquals("桂工精神", navigation.destination.label)
        assertEquals("桂工精神", navigation.showDocuments().destination.label)
        assertNull(SchoolOverviewDestination.CAMPUS_INFO.topic)
    }
}
