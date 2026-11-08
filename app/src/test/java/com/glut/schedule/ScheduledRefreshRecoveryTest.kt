package com.glut.schedule

import com.glut.schedule.service.refresh.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** 恢复以原到期轮次为边界，禁止重刷成功依赖或借恢复绕过登录要求。 */
class ScheduledRefreshRecoveryTest {
    private val due = Instant.parse("2026-11-08T10:00:00Z").toEpochMilli()
    private val plan = ScheduledRefreshPlan("evening", 1080,
        setOf(RefreshTaskId.SCHEDULE, RefreshTaskId.SEMESTER_OVERVIEW, RefreshTaskId.CHAOXING))
    private val success = ScheduledRefreshTaskResult(RefreshTaskId.SCHEDULE, RefreshTaskStatus.SUCCESS, "已更新")
    private val run = ScheduledRefreshRun("run", plan.id, due + 1000, due + 2000, listOf(success),
        minuteOfDay = plan.minuteOfDay, taskIds = plan.taskIds, state = ScheduledRefreshRunState.INTERRUPTED,
        scheduledAt = due, configurationKey = "configuration", dependencyResults = listOf(success))

    @Test fun recoveryKeepsSameOccurrenceAndSkipsSuccessfulPages() {
        assertTrue(canResumeScheduledRun(run, plan, "configuration", due + 60_000))
        assertEquals(setOf(RefreshTaskId.SEMESTER_OVERVIEW, RefreshTaskId.CHAOXING), remainingScheduledTasks(run))
        assertEquals(due, decodeRuns(encodeRuns(listOf(run))).single().scheduledAt)
    }

    @Test fun successfulRawDependencyIsNotRequestedAgainByDerivedPage() = runTest {
        val calls = mutableListOf<RefreshTaskId>()
        val cache = RefreshDependencyCache { task -> calls += task
            ScheduledRefreshTaskResult(task, RefreshTaskStatus.SUCCESS, "已更新") }
        cache.seedSuccess(run.dependencyResults)
        assertEquals(RefreshTaskStatus.SUCCESS, cache.execute(RefreshTaskId.SEMESTER_OVERVIEW).status)
        assertEquals(listOf(RefreshTaskId.SEMESTER_OVERVIEW), calls)
    }

    @Test fun checkpointIsSavedBetweenDerivedDependencies() = runTest {
        val checkpoints = mutableListOf<List<RefreshTaskId>>()
        val cache = RefreshDependencyCache { task -> ScheduledRefreshTaskResult(task, RefreshTaskStatus.SUCCESS, "已更新") }
        cache.onSuccessfulDependency = { values -> checkpoints += values.map { it.taskId } }
        cache.execute(RefreshTaskId.PROFESSIONAL_SCORE)
        assertEquals(listOf(RefreshTaskId.SCORES), checkpoints.first())
        assertEquals(listOf(RefreshTaskId.SCORES, RefreshTaskId.STUDY_PLAN), checkpoints.last())
    }

    @Test fun editedDeletedOrChangedSourceCannotResume() {
        assertFalse(canResumeScheduledRun(run, plan.copy(id = "deleted-replaced"), "configuration", due + 1000))
        assertFalse(canResumeScheduledRun(run, plan.copy(minuteOfDay = 1095), "configuration", due + 1000))
        assertFalse(canResumeScheduledRun(run, plan.copy(taskIds = setOf(RefreshTaskId.CHAOXING)), "configuration", due + 1000))
        assertFalse(canResumeScheduledRun(run, plan, "changed-source-or-revision", due + 1000))
        assertFalse(canResumeScheduledRun(run.copy(configurationKey = ""), plan, "", due + 1000))
    }

    @Test fun atMostTwoRetriesWithinOriginalTwoHourWindow() {
        assertTrue(canResumeScheduledRun(run.copy(attempt = 2), plan, "configuration", due + SCHEDULED_REFRESH_RECOVERY_WINDOW))
        assertFalse(canResumeScheduledRun(run.copy(attempt = 3), plan, "configuration", due + 1000))
        assertFalse(canResumeScheduledRun(run, plan, "configuration", due + SCHEDULED_REFRESH_RECOVERY_WINDOW + 1))
        assertFalse(canResumeScheduledRun(run, plan, "configuration", due - 1))
    }

    @Test fun loginFailureBlocksAcademicRecoveryButKeepsIndependentLearningSource() {
        val login = ScheduledRefreshTaskResult(RefreshTaskId.SCHEDULE, RefreshTaskStatus.LOGIN_REQUIRED, "需登录")
        assertEquals(setOf(RefreshTaskId.CHAOXING), remainingScheduledTasks(run.copy(results = listOf(login))))
        val failure = ScheduledRefreshTaskResult(RefreshTaskId.CHAOXING, RefreshTaskStatus.FAILED, "网络失败", retryable = true)
        assertEquals(setOf(RefreshTaskId.CHAOXING), remainingScheduledTasks(run.copy(results = listOf(login, failure))))
        assertTrue(remainingScheduledTasks(run.copy(results = listOf(login,
            failure.copy(status = RefreshTaskStatus.LOGIN_REQUIRED, retryable = false)))).isEmpty())
    }

    @Test fun onlyExplicitTemporaryFailureIsRetriedAndLearningGoesFirst() {
        val failed = ScheduledRefreshTaskResult(RefreshTaskId.SEMESTER_OVERVIEW, RefreshTaskStatus.FAILED, "暂不可查看")
        assertEquals(setOf(RefreshTaskId.CHAOXING), remainingScheduledTasks(run.copy(results = listOf(success, failed))))
        assertEquals(RefreshTaskId.CHAOXING, orderedScheduledTasks(plan.taskIds).first())
    }

    @Test fun legacyDueAtExactTimeStillBelongsToTodayAndSuccessorIsTomorrow() {
        val zone = ZoneId.of("Asia/Shanghai")
        assertEquals(due, latestScheduledPlan(listOf(plan), Instant.ofEpochMilli(due), zone).second.toEpochMilli())
        assertEquals(due + 24 * 60 * 60_000L,
            nextRefreshInstant(listOf(plan.minuteOfDay), Instant.ofEpochMilli(due), zone).toEpochMilli())
    }

    @Test fun diagnosticsAndRetryCheckpointRoundTripWithoutCredentials() {
        val snapshot = run.copy(attempt = 2, durationMillis = 1500, reason = "后台任务被系统停止或取消", stopReason = 6,
            results = run.results + ScheduledRefreshTaskResult(RefreshTaskId.CHAOXING, RefreshTaskStatus.FAILED, "请求超时", true))
        assertEquals(snapshot, decodeRuns(encodeRuns(listOf(snapshot))).single())
    }

    @Test fun parentTimeoutPropagatesInsteadOfInventingTaskFailure() = runTest {
        try {
            withTimeout(20) {
                try { delay(40) } catch (_: TimeoutCancellationException) {
                    scheduledRefreshTimeoutResult(RefreshTaskId.SCHEDULE)
                }
            }
            fail("整轮超时必须继续取消")
        } catch (_: TimeoutCancellationException) { /* 预期由父级停止整轮。 */ }
    }

    @Test fun localTimeoutRemainsRetryableWhileParentIsActive() = runTest {
        val result = withTimeout(100) {
            try {
                withTimeout(20) { delay(40) }
                error("未触发分项超时")
            } catch (_: TimeoutCancellationException) { scheduledRefreshTimeoutResult(RefreshTaskId.SCHEDULE) }
        }
        assertEquals(RefreshTaskStatus.FAILED, result.status)
        assertTrue(result.retryable)
    }

    @Test fun successorEnqueueIsIdempotentEvenWhenPreviousSuccessorFinished() {
        val request = "occurrence:configuration:$due:request:initial:0"
        assertTrue(shouldEnqueueScheduledOccurrence(false, false, emptyList(), request))
        for (finished in listOf(false, true)) {
            assertFalse(shouldEnqueueScheduledOccurrence(false, false,
                listOf(ScheduledOccurrenceWorkSnapshot(setOf(request), finished)), request))
        }
        // WorkManager 历史被清理后，持久运行记录仍阻止同轮重新登记。
        assertFalse(shouldEnqueueScheduledOccurrence(false, true, emptyList(), request))
    }

    @Test fun recoveryEnqueueIsIdempotentPerBoundedAttempt() {
        val original = ScheduledOccurrenceWorkSnapshot(setOf("initial"), true)
        assertTrue(shouldEnqueueScheduledOccurrence(true, true, listOf(original), "recovery-2"))
        assertFalse(shouldEnqueueScheduledOccurrence(true, true,
            listOf(original.copy(finished = false)), "recovery-2"))
        assertFalse(shouldEnqueueScheduledOccurrence(true, true,
            listOf(original, ScheduledOccurrenceWorkSnapshot(setOf("recovery-2"), true)), "recovery-2"))
    }

    @Test fun overnightOfflineWorkerCatchesOnlyLatestUnrecordedCurrentSlot() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = Instant.ofEpochMilli(due + 5 * 60_000L)
        assertEquals(due, nextScheduledOccurrenceAfterRun(plan, now, zone,
            due - 24 * 60 * 60_000L, emptySet()))
        assertEquals(due + 24 * 60 * 60_000L,
            nextScheduledOccurrenceAfterRun(plan, now, zone, due - 24 * 60 * 60_000L, setOf(due)))
        assertEquals(due + 24 * 60 * 60_000L,
            nextScheduledOccurrenceAfterRun(plan, Instant.ofEpochMilli(due + SCHEDULED_REFRESH_RECOVERY_WINDOW + 1), zone,
                due - 24 * 60 * 60_000L, emptySet()))
    }
}
