package com.glut.schedule.service.academic

import com.glut.schedule.data.model.AcademicSemester
import com.glut.schedule.data.model.SemesterCacheStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SemesterDownloadMode { SINGLE, BULK, RETRY_FAILED }

enum class SemesterDownloadItemStatus { QUEUED, DOWNLOADING, SUCCEEDED, FAILED }

data class SemesterDownloadSession(
    val ownerStudentNumber: String,
    val cookie: String,
    val baseUrl: String
)

data class SemesterDownloadItemState(
    val semesterId: String,
    val displayName: String,
    val status: SemesterDownloadItemStatus,
    val skippedRowCount: Int = 0,
    val errorMessage: String? = null
)

data class SemesterBulkDownloadSummary(
    val mode: SemesterDownloadMode,
    val ownerStudentNumber: String,
    val items: List<SemesterDownloadItemState>,
    val finishedAtEpochMillis: Long
)

data class SemesterDownloadState(
    val isRunning: Boolean = false,
    val mode: SemesterDownloadMode? = null,
    val activeSemesterId: String? = null,
    val items: List<SemesterDownloadItemState> = emptyList(),
    val lastBulkSummary: SemesterBulkDownloadSummary? = null
)

sealed interface SemesterDownloadStartResult {
    data class Started(
        val completion: Deferred<SemesterBulkDownloadSummary?>
    ) : SemesterDownloadStartResult

    data object AlreadyRunning : SemesterDownloadStartResult
    data object NothingPending : SemesterDownloadStartResult
    data object LoginRequired : SemesterDownloadStartResult
    data object NotRetryable : SemesterDownloadStartResult
}

/**
 * 应用级学期下载协调器。任务归属 Application scope，因此离开导入页或 Activity 重建
 * 不会中断；系统杀死进程后的续传不属于本协调器的保证范围。
 */
class SemesterBulkDownloadCoordinator(
    private val scope: CoroutineScope,
    private val semestersProvider: suspend () -> List<AcademicSemester>,
    private val sessionProvider: suspend () -> SemesterDownloadSession,
    private val currentOwnerProvider: suspend () -> String,
    private val download: suspend (
        semester: AcademicSemester,
        session: SemesterDownloadSession
    ) -> Result<AcademicSemesterImportPayload>,
    private val commit: suspend (AcademicSemester, AcademicSemesterImportPayload) -> Unit,
    private val updateCacheStatus: suspend (String, SemesterCacheStatus) -> Unit,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val startMutex = Mutex()
    private var activeCompletion: Deferred<SemesterBulkDownloadSummary?>? = null
    private var generation = 0L
    private val _state = MutableStateFlow(SemesterDownloadState())
    val state: StateFlow<SemesterDownloadState> = _state.asStateFlow()
    private val _completionEvents = MutableSharedFlow<SemesterBulkDownloadSummary>(extraBufferCapacity = 1)
    val completionEvents: SharedFlow<SemesterBulkDownloadSummary> = _completionEvents.asSharedFlow()

    /**
     * 「全部下载」：把**所有历史学期**重抓一遍并覆盖缓存。
     *
     * 这里刻意**不看 cacheStatus**。曾经只挑「未缓存 / 失败」的学期，结果是历史学期一旦
     * 全部缓存，这个入口就无事可做——界面只能把它置灰或换成一条状态说明，用户会读成
     * 「按钮没了」。现在的语义是「全部重新下载」：已缓存的学期也照样重抓，让用户能一键
     * 把旧缓存刷新一遍，不必先手动清缓存。
     *
     * 当前学期始终排除：它由首页刷新负责，且它正是「正在查看」的那个，不该被批量覆盖。
     *
     * 成本：统一走大节课表后每学期只有 2 次 GET（原先模式1 是逐周 POST 约 20 次），
     * 6 个学期也就十来次请求，但仍是串行执行，随时可以离开页面。
     */
    suspend fun startAll(): SemesterDownloadStartResult = startMutex.withLock {
        if (activeCompletion?.isActive == true) return@withLock SemesterDownloadStartResult.AlreadyRunning
        val candidates = semestersProvider().filter { semester -> !semester.isCurrent }
        if (candidates.isEmpty()) return@withLock SemesterDownloadStartResult.NothingPending
        startLocked(SemesterDownloadMode.BULK, candidates)
    }

    suspend fun startSingle(semesterId: String): SemesterDownloadStartResult = startMutex.withLock {
        if (activeCompletion?.isActive == true) return@withLock SemesterDownloadStartResult.AlreadyRunning
        val semester = semestersProvider().firstOrNull { it.id == semesterId }
            ?: return@withLock SemesterDownloadStartResult.NotRetryable
        if (semester.isCurrent) return@withLock SemesterDownloadStartResult.NotRetryable
        startLocked(SemesterDownloadMode.SINGLE, listOf(semester))
    }

    suspend fun retryFailed(semesterId: String): SemesterDownloadStartResult = startMutex.withLock {
        if (activeCompletion?.isActive == true) return@withLock SemesterDownloadStartResult.AlreadyRunning
        val failed = _state.value.lastBulkSummary?.items?.any {
            it.semesterId == semesterId && it.status == SemesterDownloadItemStatus.FAILED
        } == true
        val semester = semestersProvider().firstOrNull { it.id == semesterId }
        if (!failed || semester == null) return@withLock SemesterDownloadStartResult.NotRetryable
        startLocked(SemesterDownloadMode.RETRY_FAILED, listOf(semester))
    }

    suspend fun cancelForAccountChange() {
        val completion = startMutex.withLock {
            generation += 1
            activeCompletion.also { activeCompletion = null }
        }
        completion?.cancelAndJoin()
        _state.value = SemesterDownloadState()
    }

    private suspend fun startLocked(
        mode: SemesterDownloadMode,
        semesters: List<AcademicSemester>
    ): SemesterDownloadStartResult {
        val session = sessionProvider()
        if (session.ownerStudentNumber.isBlank() || session.cookie.isBlank()) {
            return SemesterDownloadStartResult.LoginRequired
        }
        val runGeneration = ++generation
        _state.value = _state.value.copy(
            isRunning = true,
            mode = mode,
            activeSemesterId = null,
            items = semesters.map { semester ->
                SemesterDownloadItemState(
                    semesterId = semester.id,
                    displayName = semester.displayName,
                    status = SemesterDownloadItemStatus.QUEUED
                )
            }
        )
        val completion = scope.async {
            executeRun(runGeneration, mode, session, semesters)
        }
        activeCompletion = completion
        completion.invokeOnCompletion {
            if (activeCompletion === completion) activeCompletion = null
        }
        return SemesterDownloadStartResult.Started(completion)
    }

    private suspend fun executeRun(
        runGeneration: Long,
        mode: SemesterDownloadMode,
        session: SemesterDownloadSession,
        semesters: List<AcademicSemester>
    ): SemesterBulkDownloadSummary? {
        val results = mutableListOf<SemesterDownloadItemState>()
        var activeSession = session
        semesters.forEach { semester ->
            updateItem(runGeneration, semester.id) {
                it.copy(status = SemesterDownloadItemStatus.DOWNLOADING)
            }
            val previousStatus = semester.cacheStatus
            runCatching {
                verifyOwner(runGeneration, session.ownerStudentNumber)
                updateCacheStatus(semester.id, SemesterCacheStatus.DOWNLOADING)
                val payload = download(semester, activeSession).getOrThrow()
                if (payload.updatedCookie.isNotBlank()) {
                    // 教务可能在下载学期时轮换会话，后续学期必须接续本次响应的最新 Cookie。
                    activeSession = activeSession.copy(cookie = payload.updatedCookie)
                }
                verifyOwner(runGeneration, session.ownerStudentNumber)
                commit(semester, payload)
                verifyOwner(runGeneration, session.ownerStudentNumber)
                // 提交完成后继续携带下载结果，供成功摘要展示异常行跳过数量。
                payload
            }.onSuccess { payload ->
                val item = currentItem(semester.id).copy(
                    status = SemesterDownloadItemStatus.SUCCEEDED,
                    skippedRowCount = payload.skippedRowCount,
                    errorMessage = null
                )
                updateItem(runGeneration, semester.id) { item }
                results += item
            }.onFailure { error ->
                // 账号切换主动取消时必须立即退出，不能在清库后又写回旧账号状态。
                if (error is CancellationException) throw error
                val fallback = if (previousStatus == SemesterCacheStatus.CACHED) {
                    SemesterCacheStatus.CACHED
                } else {
                    SemesterCacheStatus.FAILED
                }
                updateCacheStatus(semester.id, fallback)
                val item = currentItem(semester.id).copy(
                    status = SemesterDownloadItemStatus.FAILED,
                    errorMessage = error.message ?: "下载失败"
                )
                updateItem(runGeneration, semester.id) { item }
                results += item
            }
        }
        if (generation != runGeneration) return null

        val previousSummary = _state.value.lastBulkSummary
        val summary = when (mode) {
            SemesterDownloadMode.BULK -> SemesterBulkDownloadSummary(
                mode = mode,
                ownerStudentNumber = session.ownerStudentNumber,
                items = results,
                finishedAtEpochMillis = now()
            )
            SemesterDownloadMode.RETRY_FAILED -> previousSummary?.copy(
                mode = mode,
                items = previousSummary.items.map { old ->
                    results.firstOrNull { it.semesterId == old.semesterId } ?: old
                },
                finishedAtEpochMillis = now()
            )
            SemesterDownloadMode.SINGLE -> SemesterBulkDownloadSummary(
                mode = mode,
                ownerStudentNumber = session.ownerStudentNumber,
                items = results,
                finishedAtEpochMillis = now()
            )
        }
        _state.value = _state.value.copy(
            isRunning = false,
            mode = null,
            activeSemesterId = null,
            items = emptyList(),
            lastBulkSummary = if (mode == SemesterDownloadMode.SINGLE) previousSummary else summary
        )
        if (summary != null) _completionEvents.tryEmit(summary)
        return summary
    }

    private suspend fun verifyOwner(runGeneration: Long, expectedOwner: String) {
        check(generation == runGeneration && currentOwnerProvider().trim() == expectedOwner.trim()) {
            "账号已切换，旧下载结果已丢弃"
        }
    }

    private fun currentItem(semesterId: String): SemesterDownloadItemState =
        _state.value.items.first { it.semesterId == semesterId }

    private fun updateItem(
        runGeneration: Long,
        semesterId: String,
        transform: (SemesterDownloadItemState) -> SemesterDownloadItemState
    ) {
        if (generation != runGeneration) return
        val current = _state.value
        _state.value = current.copy(
            activeSemesterId = semesterId,
            items = current.items.map { item ->
                if (item.semesterId == semesterId) transform(item) else item
            }
        )
    }
}
