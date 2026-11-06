package com.glut.schedule

import com.glut.schedule.ui.runAutoRefreshInOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

/** 延迟课表结果，确保考试不会抢先启动或在取消后启动。 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AutoRefreshSequenceTest {
    @Test fun examsWaitForSchedule() = runTest {
        val ready = CompletableDeferred<Unit>()
        var examsStarted = false
        val task = launch { runAutoRefreshInOrder({ ready.await(); true }, { examsStarted = true }) }
        runCurrent()
        assertFalse(examsStarted)
        ready.complete(Unit)
        task.join()
        assertTrue(examsStarted)
    }
    @Test fun busyScheduleSkipsExams() = runTest {
        var examsStarted = false
        runAutoRefreshInOrder({ false }, { examsStarted = true })
        assertFalse(examsStarted)
    }
    @Test fun cancellationSkipsExams() = runTest {
        var examsStarted = false
        val task = launch { runAutoRefreshInOrder({ CompletableDeferred<Unit>().await(); true }, { examsStarted = true }) }
        runCurrent()
        task.cancel()
        task.join()
        assertFalse(examsStarted)
    }
}
