package com.glut.schedule.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class ScheduleWidgetRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            if (inputData.getBoolean(KEY_EVENT_REFRESH, false)) {
                ScheduleWidgetUpdater.renderAll(applicationContext)
                ScheduleWidgetRefreshScheduler.scheduleNext(
                    applicationContext,
                    ExistingWorkPolicy.APPEND_OR_REPLACE
                )
            } else {
                ScheduleWidgetUpdater.updateAll(applicationContext)
            }
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // 重试耗尽即 Result.failure()，而自我续期正是发生在 doWork 里——失败就意味着
            // 刷新链条到此为止，当天只能指望系统那个 30 分钟的兜底广播。所以这里必须留痕。
            if (runAttemptCount + 1 < MAX_RETRY_ATTEMPTS) {
                Log.w(TAG, "小组件刷新失败，第 ${runAttemptCount + 1} 次重试", error)
                Result.retry()
            } else {
                Log.e(TAG, "小组件刷新重试耗尽，刷新链已断", error)
                Result.failure()
            }
        }
    }

    companion object {
        internal const val KEY_EVENT_REFRESH = "event_refresh"
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val TAG = "ScheduleWidget"
    }
}

object ScheduleWidgetRefreshScheduler {
    private const val EVENT_WORK_NAME = "schedule_widget_event_refresh"
    private const val IMMEDIATE_WORK_NAME = "schedule_widget_immediate_refresh"
    private const val TAG = "ScheduleWidget"

    fun requestImmediate(context: Context) {
        val request = OneTimeWorkRequestBuilder<ScheduleWidgetRefreshWorker>().build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    suspend fun scheduleNext(
        context: Context,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
        now: LocalDateTime = LocalDateTime.now()
    ) {
        val appContext = context.applicationContext
        val workManager = WorkManager.getInstance(appContext)
        // 只在「显式重排」（REPLACE，即 updateAll 之后）时才用这个判断决定要不要继续维持链条：
        // 用户把小组件都删了就无需再排。
        //
        // worker 自我续期（APPEND_OR_REPLACE）时**不能**这么判：那一轮刚刚渲染过，小组件显然
        // 存在，而 GlanceAppWidgetManager.getGlanceIds 在进程刚重启时可能瞬时返回空——
        // 一判就永久断链，当天再没有任何事件刷新，正是用户看到的「过了一小时也没刷」。
        if (policy == ExistingWorkPolicy.REPLACE && !hasInstalledWidgets(appContext)) {
            Log.i(TAG, "没有已添加的小组件，停止刷新链")
            workManager.cancelUniqueWork(EVENT_WORK_NAME)
            return
        }

        val snapshot = ScheduleWidgetDataSource(appContext).load(now)
        val nextRefresh = ScheduleWidgetRefreshPlanner.nextRefreshAt(now, snapshot)
        val delayMillis = Duration.between(now, nextRefresh).toMillis().coerceAtLeast(1_000L)
        val input = Data.Builder()
            .putBoolean(ScheduleWidgetRefreshWorker.KEY_EVENT_REFRESH, true)
            .build()
        val request = OneTimeWorkRequestBuilder<ScheduleWidgetRefreshWorker>()
            .setInputData(input)
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .build()

        workManager.enqueueUniqueWork(EVENT_WORK_NAME, policy, request)
    }

    private suspend fun hasInstalledWidgets(context: Context): Boolean {
        val manager = GlanceAppWidgetManager(context)
        return manager.getGlanceIds(CompactTodayWidget::class.java).isNotEmpty() ||
            manager.getGlanceIds(TodayTomorrowWidget::class.java).isNotEmpty() ||
            manager.getGlanceIds(ColorTimelineWidget::class.java).isNotEmpty()
    }
}
