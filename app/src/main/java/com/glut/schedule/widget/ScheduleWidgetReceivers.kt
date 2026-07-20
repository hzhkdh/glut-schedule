package com.glut.schedule.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class CompactTodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CompactTodayWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        ScheduleWidgetRefreshScheduler.requestImmediate(context)
    }
}

class TodayTomorrowWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayTomorrowWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        ScheduleWidgetRefreshScheduler.requestImmediate(context)
    }
}

class ColorTimelineWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ColorTimelineWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        ScheduleWidgetRefreshScheduler.requestImmediate(context)
    }
}
