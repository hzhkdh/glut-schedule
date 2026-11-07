package com.glut.schedule.service.refresh

/** 一轮计划内复用同一来源结果；每个用户所选页面仍保留独立结果行。 */
internal class RefreshDependencyCache(private val run: suspend (RefreshTaskId) -> ScheduledRefreshTaskResult) {
    private val completed = mutableMapOf<RefreshTaskId, ScheduledRefreshTaskResult>()
    suspend fun execute(task: RefreshTaskId): ScheduledRefreshTaskResult {
        val results = refreshTaskDependencies(task).map { dependency ->
            completed[dependency] ?: run(dependency).also { completed[dependency] = it }
        }
        return results.firstOrNull { it.status != RefreshTaskStatus.SUCCESS }?.copy(taskId = task)
            ?: ScheduledRefreshTaskResult(task, RefreshTaskStatus.SUCCESS, "已更新")
    }
}
