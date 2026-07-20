package com.glut.schedule.widget

import android.content.Context
import androidx.glance.appwidget.updateAll

object ScheduleWidgetUpdater {
    suspend fun updateAll(context: Context) {
        CompactTodayWidget().updateAll(context)
        TodayTomorrowWidget().updateAll(context)
        ColorTimelineWidget().updateAll(context)
    }
}
