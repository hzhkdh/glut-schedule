package com.glut.schedule

import com.glut.schedule.widget.WidgetScheduleStatus
import com.glut.schedule.widget.widgetHeaderWeekText
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleWidgetHeaderTest {
    @Test
    fun weekIsOnlyShownForInSemesterStates() {
        assertEquals("第 19 周 · 周日", widgetHeaderWeekText(WidgetScheduleStatus.READY, 19, "周日"))
        assertEquals("第 19 周 · 周日", widgetHeaderWeekText(WidgetScheduleStatus.NO_COURSES, 19, "周日"))
        assertEquals("周一", widgetHeaderWeekText(WidgetScheduleStatus.OUTSIDE_SEMESTER, 20, "周一"))
        assertEquals("周日", widgetHeaderWeekText(WidgetScheduleStatus.BEFORE_SEMESTER, 0, "周日"))
        assertEquals("周三", widgetHeaderWeekText(WidgetScheduleStatus.NO_DATA, 20, "周三"))
        assertEquals("周四", widgetHeaderWeekText(WidgetScheduleStatus.READ_ERROR, 20, "周四"))
    }
}
