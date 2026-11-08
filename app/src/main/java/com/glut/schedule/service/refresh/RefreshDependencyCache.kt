package com.glut.schedule.service.refresh

/** 一轮计划内复用同一来源结果；每个用户所选页面仍保留独立结果行。 */
internal class RefreshDependencyCache(private val run: suspend (RefreshTaskId) -> ScheduledRefreshTaskResult) {
    private val completed = mutableMapOf<RefreshTaskId, ScheduledRefreshTaskResult>()
    var onSuccessfulDependency: suspend (List<ScheduledRefreshTaskResult>) -> Unit = {}
    /** 只恢复真实成功的数据依赖，派生页面不能再次请求已完成的课表或成绩。 */
    fun seedSuccess(results: List<ScheduledRefreshTaskResult>) {
        results.filter { it.status == RefreshTaskStatus.SUCCESS }.forEach { completed[it.taskId] = it }
    }
    fun successfulResults(): List<ScheduledRefreshTaskResult> = completed.values.filter { it.status == RefreshTaskStatus.SUCCESS }
    suspend fun execute(task: RefreshTaskId): ScheduledRefreshTaskResult {
        val results = refreshTaskDependencies(task).map { dependency ->
            completed[dependency] ?: run(dependency).also {
                completed[dependency] = it
                // 派生页面可能包含多个慢请求，每个原始依赖成功后立即落盘检查点。
                if (it.status == RefreshTaskStatus.SUCCESS) onSuccessfulDependency(successfulResults())
            }
        }
        return results.firstOrNull { it.status != RefreshTaskStatus.SUCCESS }?.copy(taskId = task)
            ?: ScheduledRefreshTaskResult(task, RefreshTaskStatus.SUCCESS, "已更新")
    }
}
