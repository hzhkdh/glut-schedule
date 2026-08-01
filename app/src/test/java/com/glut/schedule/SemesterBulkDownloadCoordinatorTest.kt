package com.glut.schedule

import com.glut.schedule.data.model.AcademicSemester
import com.glut.schedule.data.model.SemesterCacheStatus
import com.glut.schedule.data.model.SemesterSeason
import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.service.academic.AcademicSemesterImportPayload
import com.glut.schedule.service.academic.SemesterBulkDownloadCoordinator
import com.glut.schedule.service.academic.SemesterDownloadSession
import com.glut.schedule.service.academic.SemesterDownloadStartResult
import com.glut.schedule.service.academic.SemesterDownloadItemStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SemesterBulkDownloadCoordinatorTest {
    @Test
    fun bulkSkipsCurrentAndCachedRunsSequentiallyAndContinuesAfterFailure() = runTest {
        val fetched = mutableListOf<String>()
        val committed = mutableListOf<String>()
        var active = 0
        var maxActive = 0
        val coordinator = coordinator(
            scope = backgroundScope,
            download = { semester, _ ->
                active += 1
                maxActive = maxOf(maxActive, active)
                fetched += semester.id
                active -= 1
                if (semester.id == "failed") Result.failure(IllegalStateException("模拟失败"))
                else Result.success(payload())
            },
            commit = { semester, _ -> committed += semester.id }
        )

        val started = coordinator.startAll() as SemesterDownloadStartResult.Started
        val summary = started.completion.await()!!

        assertEquals(listOf("missing", "failed", "missing-later"), fetched)
        assertEquals(listOf("missing", "missing-later"), committed)
        assertEquals(1, maxActive)
        assertEquals(
            listOf(
                SemesterDownloadItemStatus.SUCCEEDED,
                SemesterDownloadItemStatus.FAILED,
                SemesterDownloadItemStatus.SUCCEEDED
            ),
            summary.items.map { it.status }
        )
        assertEquals(summary, coordinator.state.value.lastBulkSummary)
    }

    @Test
    fun duplicateStartIsRejectedWhileQueueIsRunning() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val coordinator = coordinator(
            scope = backgroundScope,
            download = { _, _ ->
                calls += 1
                if (calls == 1) gate.await()
                Result.success(payload())
            }
        )

        val first = coordinator.startAll() as SemesterDownloadStartResult.Started
        testScheduler.runCurrent()
        assertTrue(coordinator.startAll() is SemesterDownloadStartResult.AlreadyRunning)
        assertEquals(1, calls)
        gate.complete(Unit)
        first.completion.await()
    }

    @Test
    fun ownerChangeDuringResponsePreventsOldAccountCommit() = runTest {
        val gate = CompletableDeferred<Unit>()
        var owner = "student-a"
        var commits = 0
        val coordinator = coordinator(
            scope = backgroundScope,
            owner = { owner },
            download = { _, _ ->
                gate.await()
                Result.success(payload())
            },
            commit = { _, _ -> commits += 1 }
        )

        val started = coordinator.startAll() as SemesterDownloadStartResult.Started
        testScheduler.runCurrent()
        owner = "student-b"
        gate.complete(Unit)
        val summary = started.completion.await()!!

        assertEquals(0, commits)
        assertTrue(summary.items.all { it.status == SemesterDownloadItemStatus.FAILED })
    }

    private fun coordinator(
        scope: kotlinx.coroutines.CoroutineScope,
        owner: suspend () -> String = { "student-a" },
        download: suspend (AcademicSemester, SemesterDownloadSession) -> Result<AcademicSemesterImportPayload>,
        commit: suspend (AcademicSemester, AcademicSemesterImportPayload) -> Unit = { _, _ -> }
    ) = SemesterBulkDownloadCoordinator(
        scope = scope,
        semestersProvider = {
            listOf(
                semester("current", true, SemesterCacheStatus.NOT_CACHED),
                semester("cached", false, SemesterCacheStatus.CACHED),
                semester("missing", false, SemesterCacheStatus.NOT_CACHED),
                semester("failed", false, SemesterCacheStatus.FAILED),
                semester("missing-later", false, SemesterCacheStatus.NOT_CACHED)
            )
        },
        sessionProvider = {
            SemesterDownloadSession("student-a", "cookie", "https://jw.example")
        },
        currentOwnerProvider = owner,
        download = { semester, session, _ -> download(semester, session) },
        commit = commit,
        updateCacheStatus = { _, _ -> },
        now = { 123L }
    )

    private fun semester(
        id: String,
        current: Boolean,
        status: SemesterCacheStatus
    ) = AcademicSemester(
        id = id,
        campus = CampusType.GUILIN,
        portalYear = 2026,
        portalYearId = "46",
        season = SemesterSeason.SPRING,
        portalTermId = "1",
        displayName = id,
        isCurrent = current,
        cacheStatus = status
    )

    private fun payload() = AcademicSemesterImportPayload(
        courses = emptyList(),
        adjustments = emptyList(),
        currcourseHtml = "",
        timetableHtml = "",
        responseKind = com.glut.schedule.service.academic.AcademicSemesterResponseKind.VALID_EMPTY_SCHEDULE,
        portalMaxWeek = 20
    )
}
