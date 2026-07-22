package com.glut.schedule.widget

import android.content.Context
import com.glut.schedule.ScheduleApplication
import java.time.LocalDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

class ScheduleWidgetDataSource(private val context: Context) {
    suspend fun load(now: LocalDateTime = LocalDateTime.now()): WidgetScheduleSnapshot {
        return try {
            val container = (context.applicationContext as ScheduleApplication).appContainer
            ScheduleWidgetSnapshotBuilder.build(
                now = now,
                courses = container.scheduleRepository.currentCourses.first(),
                classPeriods = container.scheduleRepository.currentClassPeriods.first(),
                semesterStartMonday = container.settingsStore.semesterStartMonday.first(),
                semesterEndDate = container.settingsStore.semesterEndDate.first()
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            WidgetScheduleSnapshot(
                status = WidgetScheduleStatus.READ_ERROR,
                today = now.toLocalDate(),
                currentWeek = 1
            )
        }
    }
}
