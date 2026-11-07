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
        if (work?.state == WorkInfo.State.RUNNING) continue
        // 仅补齐生命周期信息，不推测未执行的项目结果或精确中断时间。
        store.markInterruptedIfUnchanged(run)
    }
}
