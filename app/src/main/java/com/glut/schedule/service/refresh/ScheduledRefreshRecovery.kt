package com.glut.schedule.service.refresh

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.UUID

/** 启动时只回收已确认不在运行的任务；查询失败时保留原始记录，避免误判。 */
suspend fun recoverInterruptedScheduledRefreshRuns(context: Context) = withContext(Dispatchers.IO) {
    val store = ScheduledRefreshStore(context)
    val manager = WorkManager.getInstance(context)
    for (run in store.config.first().runs.filter { it.state == ScheduledRefreshRunState.RUNNING }) {
        val workId = run.workId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: continue
        val work = try { manager.getWorkInfoById(workId).get() } catch (_: Exception) { continue }
        // ENQUEUED/BLOCKED 也可能是系统停止后保留的同一个任务，交由它继续检查点。
        if (work != null && !work.state.isFinished) continue
        // 仅补齐生命周期信息，不推测未执行的项目结果或精确中断时间。
        store.markInterruptedIfUnchanged(run, work?.stopReason)
    }
}

internal const val SCHEDULED_REFRESH_RECOVERY_WINDOW = 2 * 60 * 60_000L
internal const val SCHEDULED_REFRESH_MAX_ATTEMPTS = 3

/** 已成功和需要用户登录的项目均为终态；仅缺失项目与明确暂时失败允许恢复。 */
internal fun remainingScheduledTasks(run: ScheduledRefreshRun): Set<RefreshTaskId> = run.taskIds.filter { task ->
    val result = run.results.lastOrNull { it.taskId == task }
    // 同一教务来源已明确要求登录时，恢复也不能借另一个页面再次尝试认证。
    val sourceNeedsLogin = task.academic && run.results.any { it.taskId.academic && it.status == RefreshTaskStatus.LOGIN_REQUIRED }
    !sourceNeedsLogin && (result == null || (result.status == RefreshTaskStatus.FAILED && result.retryable))
}.toSet()

/** 配置代次包含开关、编辑和来源身份；旧记录缺少轮次身份时不自动追补。 */
internal fun canResumeScheduledRun(run: ScheduledRefreshRun, plan: ScheduledRefreshPlan,
    configurationKey: String, now: Long): Boolean = scheduledRunRecoveryStopReason(run, plan, configurationKey, now) == null

internal fun scheduledRunRecoveryStopReason(run: ScheduledRefreshRun, plan: ScheduledRefreshPlan,
    configurationKey: String, now: Long): String? = when {
    run.configurationKey.isBlank() || run.scheduledAt <= 0 -> "旧记录缺少可校验的轮次身份，不自动恢复"
    run.planId != plan.id || run.minuteOfDay != plan.minuteOfDay || run.taskIds != plan.taskIds ||
        run.configurationKey != configurationKey -> "计划或账号来源已变化，本轮不再恢复"
    remainingScheduledTasks(run).isEmpty() -> "本轮无需恢复"
    run.attempt >= SCHEDULED_REFRESH_MAX_ATTEMPTS -> "已达到两次恢复上限"
    now < run.scheduledAt || now - run.scheduledAt > SCHEDULED_REFRESH_RECOVERY_WINDOW -> "已超过本轮两小时恢复时限"
    else -> null
}

/** 学习通与教务来源独立，先完成学习通，避免多项慢教务请求耗尽整轮后台时间。 */
internal fun orderedScheduledTasks(tasks: Set<RefreshTaskId>): List<RefreshTaskId> =
    tasks.sortedWith(compareBy<RefreshTaskId> { it != RefreshTaskId.CHAOXING }.thenBy { it.ordinal })
