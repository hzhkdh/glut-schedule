package com.glut.schedule.widget

import android.content.Context
import android.util.Log
import com.glut.schedule.ScheduleApplication
import com.glut.schedule.data.model.ManualDayCopyRule
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
                semesterEndDate = container.settingsStore.semesterEndDate.first(),
                manualDayCopies = loadManualDayCopies()
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // 这条日志很关键：返回错误快照意味着 todayCourses 为空，刷新规划器只能把下一次
            // 刷新排到「次日 0 点」——**当天再不会有任何事件刷新**。小组件「过了一小时还没
            // 更新」的现场，往往就是这里被某次数据读取抖动触发了。
            Log.w(TAG, "小组件快照读取失败，本次退化为错误态且当天不再有事件刷新", error)
            WidgetScheduleSnapshot(
                status = WidgetScheduleStatus.READ_ERROR,
                today = now.toLocalDate(),
                currentWeek = 1
            )
        }
    }

    /**
     * 手动调休规则只影响「今天多出来的副本」，拿不到时按「没有规则」处理。
     *
     * 单独容错而不是并进上面那个大 try：一次 DataStore 抖动不该让整个快照退化成错误态，
     * 那会顺带把当天的刷新链条一起掐断。
     */
    private suspend fun loadManualDayCopies(): List<ManualDayCopyRule> {
        return try {
            val container = (context.applicationContext as ScheduleApplication).appContainer
            // 调休规则按学期 id 分组存储，这里只取**当前学期**那一份——与首页、与本文件
            // 既有的「小组件永远展示当前学期而非历史学期」口径一致。
            val currentSemesterId = container.scheduleRepository.currentSemester.first()?.id.orEmpty()
            container.settingsStore.manualDayCopies.first()[currentSemesterId].orEmpty()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "读取调休规则失败，按「没有调休」继续渲染", error)
            emptyList()
        }
    }

    private companion object {
        const val TAG = "ScheduleWidget"
    }
}
