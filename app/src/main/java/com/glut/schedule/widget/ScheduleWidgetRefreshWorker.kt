package com.glut.schedule.widget

import android.content.Context
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
            if (runAttemptCount + 1 < MAX_RETRY_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        internal const val KEY_EVENT_REFRESH = "event_refresh"
        private const val MAX_RETRY_ATTEMPTS = 3
    }
}

object ScheduleWidgetRefreshScheduler {
    private const val EVENT_WORK_NAME = "schedule_widget_event_refresh"
    private const val IMMEDIATE_WORK_NAME = "schedule_widget_immediate_refresh"

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
        if (!hasInstalledWidgets(appContext)) {
            if (policy == ExistingWorkPolicy.REPLACE) {
                workManager.cancelUniqueWork(EVENT_WORK_NAME)
            }
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
