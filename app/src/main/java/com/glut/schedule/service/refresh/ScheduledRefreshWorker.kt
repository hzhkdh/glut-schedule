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
import java.time.Instant
import java.time.ZoneId
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
            ?: return@withContext Result.success()
        val started = System.currentTimeMillis()
        val elapsedStart = android.os.SystemClock.elapsedRealtime()
        val source = scheduledRefreshSourceKey(applicationContext)
        val key = scheduledRefreshConfigurationKey(config, plan, source)
        // 配置或账号发生变化时旧任务失效；时钟、时区校准由排程层保留已到期任务。
        val inputKey = inputData.getString("configurationKey")
        if (inputKey != null && inputKey != key) return@withContext Result.success()
        val scheduledAt = inputData.getLong("scheduledAt", 0).takeIf { it > 0 }
            ?: latestScheduledPlan(listOf(plan), Instant.ofEpochMilli(started), ZoneId.systemDefault()).second.toEpochMilli()
        // 重复投递也复用同一到期轮次记录，不能重新执行已经完成的成功项目。
        val previous = config.runs.firstOrNull { it.id == inputData.getString("resumeRunId") || it.workId == id.toString() ||
            (it.configurationKey == key && it.planId == plan.id && it.scheduledAt == scheduledAt) }
        if (previous != null && !canResumeScheduledRun(previous, plan, key, started)) {
            if (remainingScheduledTasks(previous).isNotEmpty()) store.recordRun(previous.copy(
                state = ScheduledRefreshRunState.INTERRUPTED, activeTask = null,
                reason = scheduledRunRecoveryStopReason(previous, plan, key, started).orEmpty()))
            enqueueNextIfCurrent(store, plan, key, scheduledAt)
            return@withContext Result.success()
        }
        val results = previous?.results.orEmpty().toMutableList()
        // 开始前固化计划快照；进程被系统杀死后仍能知道实际执行到了哪一步。
        var run = previous?.copy(state = ScheduledRefreshRunState.RUNNING, finishedAt = 0,
            workId = id.toString(), attempt = previous.attempt + 1, reason = "正在恢复同一轮未完成项目", stopReason = null)
            ?: ScheduledRefreshRun(UUID.randomUUID().toString(), plan.id, started, 0, emptyList(),
            minuteOfDay = plan.minuteOfDay, taskIds = plan.taskIds.toSet(),
            state = ScheduledRefreshRunState.RUNNING, workId = id.toString(), scheduledAt = scheduledAt,
            configurationKey = key, reason = "开始执行")
        val previousDuration = run.durationMillis
        val executor = UnifiedRefreshExecutor(applicationContext,
            (applicationContext as ScheduleApplication).appContainer) {
            val current = store.config.first()
            current.enabled && plan in current.plans &&
                scheduledRefreshConfigurationKey(current, plan, scheduledRefreshSourceKey(applicationContext)) == key
        }
        executor.seedSuccess(run.dependencyResults)
        executor.checkpointWith { dependencies ->
            run = run.copy(dependencyResults = dependencies,
                durationMillis = previousDuration + android.os.SystemClock.elapsedRealtime() - elapsedStart)
            store.recordRun(run)
        }
        var retry = false
        try {
            store.recordRun(run)
            if (started - scheduledAt > SCHEDULED_REFRESH_RECOVERY_WINDOW) {
                run = run.copy(reason = "已超过本轮两小时恢复时限")
            } else withTimeout(minOf(8 * 60_000L,
                scheduledAt + SCHEDULED_REFRESH_RECOVERY_WINDOW - started).coerceAtLeast(1)) {
                for (task in orderedScheduledTasks(plan.taskIds)) {
                    if (task !in remainingScheduledTasks(run)) {
                        // 同来源已确认需要登录，后续页面只记录登录前置条件，不再请求或续期。
                        if (task.academic && results.none { it.taskId == task } &&
                            results.any { it.taskId.academic && it.status == RefreshTaskStatus.LOGIN_REQUIRED }) {
                            results += ScheduledRefreshTaskResult(task, RefreshTaskStatus.LOGIN_REQUIRED, "教务来源需要重新登录，本项未请求")
                            run = run.copy(results = results.toList())
                            store.recordRun(run)
                        }
                        continue
                    }
                    run = run.copy(activeTask = task)
                    store.recordRun(run)
                    val result = executor.execute(task)
                    results.removeAll { it.taskId == task }
                    results += result
                    run = run.copy(results = results.toList(), activeTask = null,
                        dependencyResults = executor.successfulDependencies(),
                        durationMillis = previousDuration + android.os.SystemClock.elapsedRealtime() - elapsedStart)
                    store.recordRun(run)
                }
                run = run.copy(state = ScheduledRefreshRunState.FINISHED, reason = "本轮执行结束")
            }
        } catch (_: TimeoutCancellationException) {
            // 超时属于本轮中断；未执行项目保持空缺，不伪造分项结果。
            run = run.copy(reason = if (System.currentTimeMillis() >= scheduledAt + SCHEDULED_REFRESH_RECOVERY_WINDOW)
                "已超过本轮两小时恢复时限" else "整轮后台执行超过八分钟")
        } catch (cancelled: CancellationException) {
            run = run.copy(reason = "后台任务被系统停止或取消", stopReason = stopReason)
            throw cancelled
        } catch (_: Exception) {
            run = run.copy(reason = "后台执行异常，保留已完成项目")
        } finally {
            // 取消仍保留检查点；系统约束停止由同一 WorkRequest 继续，配置撤销不再排队。
            withContext(NonCancellable) {
                // 整轮中断时也接续本轮已换发的 Cookie，避免恢复再次使用旧会话。
                runCatching { executor.finish() }
                run = run.copy(finishedAt = System.currentTimeMillis(), results = results.toList(),
                    durationMillis = previousDuration + android.os.SystemClock.elapsedRealtime() - elapsedStart,
                    dependencyResults = executor.successfulDependencies(),
                    activeTask = null, state = if (run.state == ScheduledRefreshRunState.FINISHED)
                        ScheduledRefreshRunState.FINISHED else ScheduledRefreshRunState.INTERRUPTED)
                val current = store.config.first()
                val currentKey = scheduledRefreshConfigurationKey(current, plan, scheduledRefreshSourceKey(applicationContext))
                val valid = current.enabled && plan in current.plans && currentKey == key
                retry = valid && canResumeScheduledRun(run, plan, key, System.currentTimeMillis())
                if (!valid) run = run.copy(reason = "计划或账号来源已变化，本轮不再恢复")
                else if (retry) run = run.copy(reason = "${run.reason}；等待恢复未完成项目")
                else if (remainingScheduledTasks(run).isNotEmpty() && run.attempt >= SCHEDULED_REFRESH_MAX_ATTEMPTS)
                    run = run.copy(reason = "${run.reason}；已达到两次恢复上限")
                store.recordRun(run)
                if (!isStopped && !retry && valid) enqueueNextIfCurrent(store, plan, key, scheduledAt)
            }
        }
        if (retry) Result.retry() else Result.success()
    }

    private suspend fun enqueueNextIfCurrent(store: ScheduledRefreshStore, plan: ScheduledRefreshPlan, key: String,
        previousScheduledAt: Long) = withScheduledRefreshSchedulingLock {
        val current = store.config.first()
        if (isStopped || !current.enabled || plan !in current.plans ||
            scheduledRefreshConfigurationKey(current, plan, scheduledRefreshSourceKey(applicationContext)) != key) return@withScheduledRefreshSchedulingLock
        val works = androidx.work.WorkManager.getInstance(applicationContext).getWorkInfosByTag("configuration:$key").get()
        val known = current.runs.filter { it.configurationKey == key }.map { it.scheduledAt }.toSet() +
            // 校准取消的未来请求并未执行，不能阻挡同一时段重新登记。
            works.filter { it.state != androidx.work.WorkInfo.State.CANCELLED }.flatMap { work -> work.tags.mapNotNull { tag ->
                tag.removePrefix("occurrence:$key:").takeIf { tag.startsWith("occurrence:$key:") }?.toLongOrNull() } }
        val next = nextScheduledOccurrenceAfterRun(plan, Instant.now(), ZoneId.systemDefault(), previousScheduledAt, known)
        enqueueScheduledOccurrence(applicationContext, plan, key, next, append = true)
    }
}
