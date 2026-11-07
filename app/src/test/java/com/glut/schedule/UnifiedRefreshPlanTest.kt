package com.glut.schedule

import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.service.refresh.*
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class UnifiedRefreshPlanTest {
    @Test fun adjustmentAndOverviewShareTheSameRefreshSources() {
        assertEquals(refreshTaskDependencies(RefreshTaskId.SEMESTER_OVERVIEW),
            refreshTaskDependencies(RefreshTaskId.HOLIDAY_ADJUSTMENTS))
        assertEquals(emptyList<ScheduledRefreshPlan>(), decodePlans("not a json document"))
    }
    @Test fun selectedPagesShareFetchedDependencies() = runTest {
        val calls = mutableListOf<RefreshTaskId>()
        val cache = RefreshDependencyCache { calls += it; ScheduledRefreshTaskResult(it, RefreshTaskStatus.SUCCESS, "完成") }
        cache.execute(RefreshTaskId.PROFESSIONAL_SCORE)
        cache.execute(RefreshTaskId.SCORES)
        cache.execute(RefreshTaskId.STUDY_PLAN)
        assertEquals(listOf(RefreshTaskId.SCORES, RefreshTaskId.STUDY_PLAN), calls)
    }
    @Test fun academicFailureDoesNotStopIndependentSource() = runTest {
        val cache = RefreshDependencyCache { ScheduledRefreshTaskResult(it,
            if (it.academic) RefreshTaskStatus.LOGIN_REQUIRED else RefreshTaskStatus.SUCCESS, "结果") }
        assertEquals(RefreshTaskStatus.LOGIN_REQUIRED, cache.execute(RefreshTaskId.PROFESSIONAL_SCORE).status)
        assertEquals(RefreshTaskStatus.SUCCESS, cache.execute(RefreshTaskId.CHAOXING).status)
    }
    @Test fun sourceRestrictionsArePerTask() {
        for (campus in listOf(CampusType.NANNING, CampusType.GRADUATE)) {
            assertFalse(refreshTaskSupported(RefreshTaskId.SCORES, campus))
            assertTrue(refreshTaskSupported(RefreshTaskId.CHAOXING, campus))
            assertFalse(refreshTaskSupported(RefreshTaskId.NOTICES, campus))
        }
    }
    @Test fun legacyTimesKeepOnlyTheirOriginalTaskSelection() {
        val plans = migrateLegacyRefreshPlans(listOf(420, 1080))
        assertEquals(listOf(420, 1080), plans.map { it.minuteOfDay })
        assertTrue(plans.all { it.taskIds == setOf(RefreshTaskId.SCHEDULE, RefreshTaskId.EXAMS) })
    }
    @Test fun explicitPlanIdSelectsOnlyThatPlan() {
        val plans = listOf(ScheduledRefreshPlan("morning", 420, setOf(RefreshTaskId.SCHEDULE)),
            ScheduledRefreshPlan("evening", 1080, setOf(RefreshTaskId.CHAOXING)))
        assertEquals(plans[1], selectScheduledPlan(plans, "evening", 600))
        assertNull(selectScheduledPlan(plans, "removed", 600))
        assertEquals(plans, decodePlans(encodePlans(plans)))
    }
    @Test fun dstGapKeepsOriginalPlanIdentity() {
        val plan = ScheduledRefreshPlan("gap", 150, setOf(RefreshTaskId.CHAOXING))
        val next = nextScheduledPlan(listOf(plan), Instant.parse("2026-03-08T06:00:00Z"), ZoneId.of("America/New_York"))
        assertEquals(plan, next.first)
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), next.second)
    }
    @Test fun persistedResultsPreservePartialAndSkippedStatuses() {
        val run = ScheduledRefreshRun("r", "p", 1, 2, listOf(
            ScheduledRefreshTaskResult(RefreshTaskId.CHAOXING, RefreshTaskStatus.SUCCESS, "已更新"),
            ScheduledRefreshTaskResult(RefreshTaskId.SCORES, RefreshTaskStatus.LOGIN_REQUIRED, "需登录")))
        assertEquals(listOf(run), decodeRuns(encodeRuns(listOf(run))))
    }

    @Test fun plansRejectFourthSlotAndRemovedSources() {
        val plans = (0..3).map { ScheduledRefreshPlan("p$it", it * 60, setOf(RefreshTaskId.CHAOXING)) }
        assertTrue(validateRefreshPlans(plans.take(3)))
        assertFalse(validateRefreshPlans(plans))
        assertFalse(validateRefreshPlans(listOf(plans.first().copy(taskIds = setOf(RefreshTaskId.FINANCE_OVERVIEW)))))
        assertEquals(10, RefreshTaskId.entries.count { it.schedulable })
    }

    @Test fun runningSnapshotAndInterruptionKeepOnlyActualResults() {
        val run = ScheduledRefreshRun("r", "p", 1, 0,
            listOf(ScheduledRefreshTaskResult(RefreshTaskId.SCORES, RefreshTaskStatus.SUCCESS, "已更新")),
            minuteOfDay = 420, taskIds = setOf(RefreshTaskId.SCORES, RefreshTaskId.CHAOXING),
            state = ScheduledRefreshRunState.RUNNING, workId = "work", activeTask = RefreshTaskId.CHAOXING)
        assertEquals(listOf(run), decodeRuns(encodeRuns(listOf(run))))
        assertEquals(ScheduledRefreshRunStatus.RUNNING, scheduledRefreshRunSummary(run).status)
        val interrupted = run.copy(state = ScheduledRefreshRunState.INTERRUPTED, activeTask = null)
        assertEquals(ScheduledRefreshRunStatus.INTERRUPTED, scheduledRefreshRunSummary(interrupted).status)
        assertEquals(1, interrupted.results.size)
        assertEquals(2, scheduledRefreshRunSummary(interrupted).totalCount)
    }

    @Test fun legacyRecordsRemainReadableWithoutInventedSnapshot() {
        val raw = """[{"id":"r","plan":"p","start":1,"end":2,"results":[{"task":"NOTICES","status":"SUCCESS","message":"已更新"}]}]"""
        val run = decodeRuns(raw).single()
        assertNull(run.minuteOfDay)
        assertNull(run.workId)
        assertEquals(setOf(RefreshTaskId.NOTICES), run.taskIds)
        assertEquals(ScheduledRefreshRunStatus.FULL_SUCCESS, scheduledRefreshRunSummary(run).status)
    }

    @Test fun sharedSummarySeparatesPartialLoginFailureAndFullSuccess() {
        fun result(task: RefreshTaskId, status: RefreshTaskStatus) = ScheduledRefreshTaskResult(task, status, "结果")
        val base = ScheduledRefreshRun("r", "p", 1, 2,
            listOf(result(RefreshTaskId.SCORES, RefreshTaskStatus.LOGIN_REQUIRED)))
        assertEquals(ScheduledRefreshRunStatus.LOGIN_REQUIRED, scheduledRefreshRunSummary(base).status)
        assertEquals(ScheduledRefreshRunStatus.FAILED, scheduledRefreshRunSummary(base.copy(
            results = listOf(result(RefreshTaskId.SCORES, RefreshTaskStatus.FAILED)))).status)
        val both = base.copy(taskIds = setOf(RefreshTaskId.SCORES, RefreshTaskId.CHAOXING),
            results = base.results + result(RefreshTaskId.CHAOXING, RefreshTaskStatus.SUCCESS))
        assertEquals(ScheduledRefreshRunStatus.PARTIAL_SUCCESS, scheduledRefreshRunSummary(both).status)
        assertEquals(ScheduledRefreshRunStatus.FULL_SUCCESS, scheduledRefreshRunSummary(base.copy(
            results = listOf(result(RefreshTaskId.SCORES, RefreshTaskStatus.SUCCESS)))).status)
    }
}
