package com.glut.schedule

import com.glut.schedule.service.chaoxing.homeworkCountdown
import com.glut.schedule.ui.navigation.*
import com.glut.schedule.data.settings.CampusType
import org.junit.Assert.*
import org.junit.Test

/** 时间自然推进会跨越提示阈值，不依赖重新同步。 */
class HomeworkCountdownTest {
    @Test fun countdownTracksLocalTime() {
        val deadline = 73*3600000L
        assertEquals("normal", homeworkCountdown(deadline, "未交", 0)!!.tone)
        assertEquals("warning", homeworkCountdown(deadline, "未交", 3600000)!!.tone)
        assertEquals("urgent", homeworkCountdown(deadline, "未交", 49*3600000L)!!.tone)
        assertEquals("不足1分钟", homeworkCountdown(deadline, "未交", deadline-30000)!!.text)
        assertEquals("已截止", homeworkCountdown(deadline, "未交", deadline)!!.text)
        assertNull(homeworkCountdown(deadline, "已完成", 0))
    }
    @Test fun homeworkIsInCampusForEverySource() {
        CampusType.entries.forEach { campus ->
            assertEquals(1, campusDrawerItems(campus).count { it == DrawerItem.ChaoxingHomework })
            assertTrue(DrawerItem.ChaoxingHomework.isEnabledFor(campus))
        }
        assertFalse(otherDrawerItems.contains(DrawerItem.ChaoxingHomework))
    }
}
