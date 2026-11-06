package com.glut.schedule

import com.glut.schedule.service.chaoxing.HomeworkSessionGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** 详情只占会话锁，只有另一次真正的同步才可以触发同步去重。 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeworkSessionGateTest {
    @Test fun refreshWaitsForDetailInsteadOfBeingDropped() = runTest {
        val gate = HomeworkSessionGate()
        val release = CompletableDeferred<Unit>()
        val detail = launch { gate.withSession { release.await() } }
        runCurrent()
        var executed = false
        val refresh = async { gate.sync { executed = true; "updated" } }
        runCurrent()
        assertFalse(executed)
        assertFalse(refresh.isCompleted)
        release.complete(Unit)
        detail.join()
        assertEquals("updated", refresh.await())
        assertTrue(executed)
    }
    @Test fun onlyConcurrentSyncIsDeduplicated() = runTest {
        val gate = HomeworkSessionGate()
        val release = CompletableDeferred<Unit>()
        val first = async { gate.sync { release.await(); "updated" } }
        runCurrent()
        assertNull(gate.sync { error("不应重复同步") })
        release.complete(Unit)
        assertEquals("updated", first.await())
        assertEquals("next", gate.sync { "next" })
    }
    @Test fun canceledWaiterReleasesSyncAdmission() = runTest {
        val gate = HomeworkSessionGate()
        val release = CompletableDeferred<Unit>()
        val detail = launch { gate.withSession { release.await() } }
        runCurrent()
        val canceled = launch { gate.sync { error("已取消的同步不应执行") } }
        runCurrent()
        canceled.cancelAndJoin()
        val next = async { gate.sync { "updated" } }
        runCurrent()
        release.complete(Unit)
        detail.join()
        assertEquals("updated", next.await())
    }
}
