package com.glut.schedule.service.chaoxing

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 同步去重和会话串行化分开：详情占用会话时，同步应等待，不能被当作重复任务。 */
internal class HomeworkSessionGate {
    private val syncAdmission = Mutex()
    private val sessionMutex = Mutex()

    /** 只有确实已有同步正在执行或等待时才返回 null，详情请求不占同步名额。 */
    suspend fun <T : Any> sync(block: suspend () -> T): T? {
        if (!syncAdmission.tryLock()) return null
        return try {
            withSession(block)
        } finally {
            // 包括等待详情时被取消的情况，均释放同步名额。
            syncAdmission.unlock()
        }
    }

    suspend fun <T> withSession(block: suspend () -> T): T = sessionMutex.withLock { block() }
}
