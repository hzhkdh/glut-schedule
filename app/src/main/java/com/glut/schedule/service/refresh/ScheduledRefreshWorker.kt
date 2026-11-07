package com.glut.schedule.service.refresh

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.glut.schedule.ScheduleApplication
import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.service.academic.AcademicProfileSession
import com.glut.schedule.service.academic.AcademicLoginResult
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.time.LocalTime
import java.util.UUID

/** 保留为教务任务的第二道来源检查；统一入口允许使用其他来源任务。 */
internal fun supportsScheduledRefresh(campus: CampusType, session: AcademicProfileSession): Boolean =
    campus == CampusType.GUILIN && session.studentNo.isNotBlank() &&
        session.base.trimEnd('/') == AcademicLoginResult.DEFAULT_GUILIN_URL

/** 老任务没有计划标识时只恢复最近一个时刻，不执行全天所有计划。 */
fun selectScheduledPlan(plans: List<ScheduledRefreshPlan>, planId: String?, minute: Int): ScheduledRefreshPlan? =
    if (!planId.isNullOrBlank()) plans.firstOrNull { it.id == planId }
    else plans.filter { it.minuteOfDay <= minute }.maxByOrNull { it.minuteOfDay } ?: plans.maxByOrNull { it.minuteOfDay }

class ScheduledRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = ScheduledRefreshStore(applicationContext)
        val config = store.config.first()
        if (!config.enabled) return@withContext Result.success()
        val local = LocalTime.now()
        val plan = selectScheduledPlan(config.plans, inputData.getString("planId"), local.hour * 60 + local.minute)
            ?: return@withContext Result.success().also { reconcileScheduledRefresh(applicationContext, config, true) }
        val started = System.currentTimeMillis()
        val results = mutableListOf<ScheduledRefreshTaskResult>()
        val executor = UnifiedRefreshExecutor(applicationContext,
            (applicationContext as ScheduleApplication).appContainer) {
            store.config.first().let { it.enabled && plan in it.plans }
        }
        try {
            store.recordAttempt(started)
            withTimeout(8 * 60_000L) {
                for (task in plan.taskIds.sortedBy { it.ordinal }) results += executor.execute(task)
                executor.finish()
            }
            Result.success()
        } catch (_: TimeoutCancellationException) {
            // 单轮留出系统十分钟限制前的收尾时间，未执行项目在 finally 标记取消。
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            // 取消也保留分项结果，未执行项目标记取消；按照最新配置继续排程。
            withContext(NonCancellable) {
                plan.taskIds.filter { task -> results.none { it.taskId == task } }.forEach {
                    results += ScheduledRefreshTaskResult(it, RefreshTaskStatus.CANCELLED, "任务已取消")
                }
                store.recordRun(ScheduledRefreshRun(UUID.randomUUID().toString(), plan.id, started,
                    System.currentTimeMillis(), results))
                reconcileScheduledRefresh(applicationContext, store.config.first(), true)
            }
        }
    }
}
