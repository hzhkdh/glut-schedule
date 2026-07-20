package com.glut.schedule.widget

import android.content.Context
import com.glut.schedule.ScheduleApplication
import java.time.LocalDateTime
import kotlinx.coroutines.flow.first

class ScheduleWidgetDataSource(private val context: Context) {
    suspend fun load(now: LocalDateTime = LocalDateTime.now()): WidgetScheduleSnapshot {
        return runCatching {
            val container = (context.applicationContext as ScheduleApplication).appContainer
            ScheduleWidgetSnapshotBuilder.build(
                now = now,
                courses = container.scheduleRepository.courses.first(),
                classPeriods = container.scheduleRepository.classPeriods.first(),
                semesterStartMonday = container.settingsStore.semesterStartMonday.first(),
                semesterEndDate = container.settingsStore.semesterEndDate.first()
            )
        }.getOrElse {
            WidgetScheduleSnapshot(
                status = WidgetScheduleStatus.READ_ERROR,
                today = now.toLocalDate(),
                currentWeek = 1
            )
        }
    }
}
