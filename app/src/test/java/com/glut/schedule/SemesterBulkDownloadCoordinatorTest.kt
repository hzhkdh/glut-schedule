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
    fun bulkRedownloadsEveryHistoricalSemesterAndSkipsCurrent() = runTest {
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

        // 「全部下载」的语义是**全部重新下载**：已缓存的 cached 也要重抓一遍，否则按钮
        // 在历史学期全缓存后就变成了空按钮（这正是本次要修的那个观感问题）。
        // 只有当前学期例外——它由首页刷新负责，不走这条批量线路。
        assertEquals(listOf("cached", "missing", "failed", "missing-later"), fetched)
        assertEquals(listOf("cached", "missing", "missing-later"), committed)
        assertEquals(1, maxActive)
        assertEquals(
            listOf(
                SemesterDownloadItemStatus.SUCCEEDED,
                SemesterDownloadItemStatus.SUCCEEDED,
                SemesterDownloadItemStatus.FAILED,
                SemesterDownloadItemStatus.SUCCEEDED
            ),
            summary.items.map { it.status }
        )
        assertEquals(summary, coordinator.state.value.lastBulkSummary)
    }

    @Test
    fun bulkStartsEvenWhenEveryHistoricalSemesterIsAlreadyCached() = runTest {
        val fetched = mutableListOf<String>()
        val coordinator = coordinator(
            scope = backgroundScope,
            semesters = listOf(
                semester("current", true, SemesterCacheStatus.NOT_CACHED),
                semester("cached-a", false, SemesterCacheStatus.CACHED),
                semester("cached-b", false, SemesterCacheStatus.CACHED)
            ),
            download = { semester, _ ->
                fetched += semester.id
                Result.success(payload())
            }
        )

        // 全部已缓存时绝不能再返回 NothingPending：导入页的「全部下载」必须保持可点，
        // 点下去就是按当前模式把这几个学期重抓一遍。
        val started = coordinator.startAll() as SemesterDownloadStartResult.Started
        started.completion.await()

        assertEquals(listOf("cached-a", "cached-b"), fetched)
    }

    @Test
    fun bulkReportsNothingPendingOnlyWhenThereIsNoHistoricalSemester() = runTest {
        val coordinator = coordinator(
            scope = backgroundScope,
            semesters = listOf(semester("current", true, SemesterCacheStatus.NOT_CACHED)),
            download = { _, _ -> Result.success(payload()) }
        )

        // 没有任何历史学期时才是真的无事可做——此时按钮该置灰。
        assertTrue(coordinator.startAll() is SemesterDownloadStartResult.NothingPending)
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

    @Test
    fun successfulItemCarriesSkippedRowCountFromPayload() = runTest {
        val coordinator = coordinator(
            scope = backgroundScope,
            download = { _, _ -> Result.success(payload(skippedRowCount = 3)) }
        )

        val summary = (coordinator.startAll() as SemesterDownloadStartResult.Started)
            .completion.await()!!

        assertTrue(summary.items.filter { it.status == SemesterDownloadItemStatus.SUCCEEDED }
            .all { it.skippedRowCount == 3 })
    }

    @Test
    fun bulkCarriesRotatedCookieIntoLaterSemesterDownloads() = runTest {
        val seenCookies = mutableListOf<String>()
        val coordinator = coordinator(
            scope = backgroundScope,
            download = { semester, session ->
                seenCookies += session.cookie
                if (semester.id == "failed") {
                    Result.failure(IllegalStateException("模拟失败"))
                } else {
                    Result.success(payload(updatedCookie = "cookie-${semester.id}"))
                }
            }
        )

        (coordinator.startAll() as SemesterDownloadStartResult.Started).completion.await()

        // 顺序即目录顺序（current 被跳过）：cached → missing → failed → missing-later。
        // 失败的那次不更新会话，所以后面的学期仍带着上一个成功的 Cookie。
        assertEquals(
            listOf("cookie", "cookie-cached", "cookie-missing", "cookie-missing"),
            seenCookies
        )
    }

    private fun coordinator(
        scope: kotlinx.coroutines.CoroutineScope,
        owner: suspend () -> String = { "student-a" },
        semesters: List<AcademicSemester> = listOf(
            semester("current", true, SemesterCacheStatus.NOT_CACHED),
            semester("cached", false, SemesterCacheStatus.CACHED),
            semester("missing", false, SemesterCacheStatus.NOT_CACHED),
            semester("failed", false, SemesterCacheStatus.FAILED),
            semester("missing-later", false, SemesterCacheStatus.NOT_CACHED)
        ),
        download: suspend (AcademicSemester, SemesterDownloadSession) -> Result<AcademicSemesterImportPayload>,
        commit: suspend (AcademicSemester, AcademicSemesterImportPayload) -> Unit = { _, _ -> }
    ) = SemesterBulkDownloadCoordinator(
        scope = scope,
        semestersProvider = { semesters },
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

    private fun payload(
        skippedRowCount: Int = 0,
        updatedCookie: String = ""
    ) = AcademicSemesterImportPayload(
        courses = emptyList(),
        adjustments = emptyList(),
        currcourseHtml = "",
        timetableHtml = "",
        responseKind = com.glut.schedule.service.academic.AcademicSemesterResponseKind.VALID_EMPTY_SCHEDULE,
        portalMaxWeek = 20,
        skippedRowCount = skippedRowCount,
        updatedCookie = updatedCookie
    )
}
