package com.glut.schedule

import com.glut.schedule.ui.pages.noticeLevelLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class NoticeLevelTest {
    @Test
    fun noticeLevelLabelUsesUserFacingChineseText() {
        assertEquals("重要", noticeLevelLabel("important"))
        assertEquals("提醒", noticeLevelLabel("warning"))
        assertEquals("更新", noticeLevelLabel("update"))
        assertEquals("通知", noticeLevelLabel("info"))
        assertEquals("通知", noticeLevelLabel(""))
        assertEquals("通知", noticeLevelLabel("unknown"))
    }
}
