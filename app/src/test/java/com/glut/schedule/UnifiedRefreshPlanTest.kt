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
            assertTrue(refreshTaskSupported(RefreshTaskId.NOTICES, campus))
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
        val plan = ScheduledRefreshPlan("gap", 150, setOf(RefreshTaskId.NOTICES))
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
}
