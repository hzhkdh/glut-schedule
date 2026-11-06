package com.glut.schedule.ui

import com.glut.schedule.data.settings.ScheduleSettingsStore
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 自动刷新的入口闸门：判定与记录必须原子完成。
 *
 * 需要它是因为「App 回到前台」有两个事件源（冷启动的 LaunchedEffect 与 ON_RESUME），
 * 同一次进入时两者几乎同时触发。若各自先读时间戳再写回，会双双读到「还没到 8 小时」的
 * 旧值，于是连刷两遍——课表那边有 SingleFlightGuard 兜底，考试那边没有，会重复发探测请求。
 *
 * 用 [Mutex] 而不是 AtomicBoolean：读时间戳是挂起操作（DataStore），原子性靠锁保证。
 */
class AutoRefreshGate(private val settingsStore: ScheduleSettingsStore) {
    private val mutex = Mutex()

    /**
     * 到点则记录本次尝试并返回 true；未到点返回 false 且不做任何写入。
     */
    suspend fun tryAcquire(nowEpochMillis: Long = System.currentTimeMillis()): Boolean =
        mutex.withLock {
            val lastAttempt = settingsStore.autoRefreshAttemptAt.first().takeIf { it > 0L }
            if (!AutoRefreshPolicy.isDue(nowEpochMillis, lastAttempt)) return@withLock false
            // 先记录再放行：即使随后刷新失败，8 小时内也不会再自动重试。
            settingsStore.setAutoRefreshAttemptAt(nowEpochMillis)
            true
        }
}

/** 两条刷新链路按实际完成顺序执行，取消后不再启动考试。 */
internal suspend fun runAutoRefreshInOrder(
    refreshSchedule: suspend () -> Boolean,
    refreshExams: suspend () -> Unit
) {
    // 手动刷新抢先占锁时，不再追加可能同时续期的考试请求。
    if (!refreshSchedule()) return
    kotlinx.coroutines.currentCoroutineContext().ensureActive()
    refreshExams()
}
