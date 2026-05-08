package com.glut.schedule

import com.glut.schedule.ui.pages.aboutScheduleLines
import org.junit.Assert.assertEquals
import org.junit.Test

class AboutScheduleDialogTest {
    @Test
    fun aboutDialogShowsCompactDeveloperInfo() {
        assertEquals(
            listOf(
                "桂工课表 v0.2.2",
                "简洁 纯粹 高效",
                "开发者：hezh",
                "反馈邮箱：hezh0425@gmail.com",
                "项目地址：https://github.com/hzhkdh/glut-schedule"
            ),
            aboutScheduleLines()
        )
    }
}
