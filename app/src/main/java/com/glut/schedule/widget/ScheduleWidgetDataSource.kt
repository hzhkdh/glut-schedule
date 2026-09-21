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
            // 调休规则按学期 id 分组存储，这里只取**当前学期**那一份——与首页、与本文件
            // 既有的「小组件永远展示当前学期而非历史学期」口径一致。
            val currentSemesterId = container.scheduleRepository.currentSemester.first()?.id.orEmpty()
            val manualDayCopies = container.settingsStore.manualDayCopies.first()[currentSemesterId].orEmpty()
            ScheduleWidgetSnapshotBuilder.build(
                now = now,
                courses = container.scheduleRepository.currentCourses.first(),
                classPeriods = container.scheduleRepository.currentClassPeriods.first(),
                semesterStartMonday = container.settingsStore.semesterStartMonday.first(),
                semesterEndDate = container.settingsStore.semesterEndDate.first(),
                manualDayCopies = manualDayCopies
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
