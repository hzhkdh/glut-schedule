package com.glut.schedule.ui.pages

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glut.schedule.data.model.ProfessionalScoreCalculator
import com.glut.schedule.data.model.ProfessionalScoreResult
import com.glut.schedule.data.model.ScoreInfo
import com.glut.schedule.data.model.StudyPlanCourse
import com.glut.schedule.data.model.StudyPlanGroup
import com.glut.schedule.data.model.StudyPlanGroupWithCourses
import com.glut.schedule.data.repository.ScheduleRepository
import com.glut.schedule.data.settings.ScheduleSettingsStore
import com.glut.schedule.service.academic.AcademicLoginResult
import com.glut.schedule.service.academic.AcademicLoginService
import com.glut.schedule.service.academic.scorePageUnavailableReason
import com.glut.schedule.service.academic.AcademicSessionStore
import com.glut.schedule.service.academic.SessionRenewalCoordinator
import com.glut.schedule.service.parser.ScoreParser
import com.glut.schedule.service.parser.StudyPlanParser
import com.glut.schedule.service.network.MAX_HTML_RESPONSE_BYTES
import com.glut.schedule.service.network.readBytesLimited
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.Charset
import java.time.LocalDate
import java.util.concurrent.TimeUnit

data class ProfessionalScoreUiState(
    val availableAcademicYears: List<String> = emptyList(),
    val selectedAcademicYear: String? = null,
    val result: ProfessionalScoreResult? = null,
    val isRefreshing: Boolean = false,
    val message: String = "",
    val scoreUnavailableReason: String = "",
    val hasCookie: Boolean = false,
    val hasStudyPlanData: Boolean = false,
    val hasScoreData: Boolean = false
)

private data class ProfessionalScoreBase(
    val groupsWithCourses: List<StudyPlanGroupWithCourses>,
    val scores: List<ScoreInfo>,
    val cookie: String,
    val semesterStartDate: LocalDate,
    val semesterEndDate: LocalDate
)

/**
 * 刷新结束后状态条该显示什么；返回 null 表示**保持现状、不要覆盖**。
 *
 * 抽成纯函数是为了能单测：原先这段决策写死在 `refreshData()` 的 `when` 里，
 * 于是「用户在续期弹窗里点了取消」这件事刚写下的提示会被立刻覆盖——
 * 取消路径写「会话已过期，已取消登录」，紧接着那个 `when` 无条件改写成
 * 「未获取到可用于计算的数据」，既不准确，又把用户刚做的动作抹掉，
 * 用户会以为自己点错了。
 */
internal fun professionalScoreRefreshMessage(
    renewalCancelled: Boolean,
    scoreUnavailableReason: String,
    scoreCount: Int,
    courseCount: Int
): String? = when {
    renewalCancelled -> null
    // 被评教拦住这类原因必须说出来，否则用户只看到「未获取到可用于计算的数据」，
    // 会以为自己没有成绩。
    scoreUnavailableReason.isNotBlank() -> scoreUnavailableReason
    scoreCount > 0 || courseCount > 0 -> "已更新：$scoreCount 条成绩，$courseCount 门计划课程"
    else -> "未获取到可用于计算的数据"
}

internal fun canUseProfessionalScoreRemoteDataWithoutLoginRetry(
    hasCookie: Boolean,
    hasScores: Boolean,
    hasStudyPlan: Boolean,
    scoreUnavailableReason: String
): Boolean {
    return hasCookie && hasScores && hasStudyPlan && scoreUnavailableReason.isBlank()
}

class ProfessionalScoreViewModel(
    private val repository: ScheduleRepository,
    private val settingsStore: ScheduleSettingsStore,
    private val sessionStore: AcademicSessionStore,
    /** 会话失效时的续期门卫：南宁/研究生原地弹验证码，桂林静默换票。 */
    private val renewal: SessionRenewalCoordinator,
    private val scoreParser: ScoreParser = ScoreParser(),
    private val studyPlanParser: StudyPlanParser = StudyPlanParser()
) : ViewModel() {
    private val _isRefreshing = MutableStateFlow(false)
    private val _message = MutableStateFlow("")
    private val _scoreUnavailableReason = MutableStateFlow("")
    private val _selectedAcademicYear = MutableStateFlow<String?>(null)
    private var messageJob: Job? = null

    val uiState: StateFlow<ProfessionalScoreUiState> = combine(
        combine(
            repository.studyPlanGroupsWithCourses,
            repository.scores,
            sessionStore.academicCookie,
            settingsStore.semesterStartMonday,
            settingsStore.semesterEndDate
        ) { groupsWithCourses, scores, cookie, semesterStartDate, semesterEndDate ->
            ProfessionalScoreBase(
                groupsWithCourses = groupsWithCourses,
                scores = scores,
                cookie = cookie,
                semesterStartDate = semesterStartDate,
                semesterEndDate = semesterEndDate
            )
        },
        _isRefreshing,
        _message,
        _scoreUnavailableReason,
        _selectedAcademicYear
    ) { base, isRefreshing, message, scoreUnavailableReason, selectedAcademicYear ->
        val academicYears = ProfessionalScoreCalculator.availableAcademicYears(base.groupsWithCourses)
        val effectiveAcademicYear = selectedAcademicYear
            ?.takeIf { it in academicYears }
            ?: ProfessionalScoreCalculator.resolveDefaultAcademicYear(
                availableAcademicYears = academicYears,
                semesterStartDate = base.semesterStartDate,
                semesterEndDate = base.semesterEndDate,
                today = LocalDate.now()
            )
        val result = effectiveAcademicYear?.let {
            ProfessionalScoreCalculator.calculate(
                academicYear = it,
                groupsWithCourses = base.groupsWithCourses,
                scores = base.scores
            )
        }

        ProfessionalScoreUiState(
            availableAcademicYears = academicYears,
            selectedAcademicYear = effectiveAcademicYear,
            result = result,
            isRefreshing = isRefreshing,
            message = message,
            scoreUnavailableReason = scoreUnavailableReason,
            hasCookie = base.cookie.isNotBlank(),
            hasStudyPlanData = base.groupsWithCourses.any { it.courses.isNotEmpty() },
            hasScoreData = base.scores.isNotEmpty()
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ProfessionalScoreUiState()
    )

    fun selectAcademicYear(academicYear: String) {
        _selectedAcademicYear.value = academicYear
    }

    fun resetAcademicYearSelection() {
        _selectedAcademicYear.value = null
    }

    fun refreshData() {
        viewModelScope.launch {
            if (_isRefreshing.value) return@launch
            messageJob?.cancel()
            _isRefreshing.value = true
            _message.value = "正在获取成绩和培养计划..."
            try {
                val remoteData = fetchRemoteDataWithLoginRetry()

                if (remoteData.groups.isNotEmpty() && remoteData.courses.isNotEmpty()) {
                    repository.replaceStudyPlanData(remoteData.groups, remoteData.courses)
                }
                if (remoteData.scores.isNotEmpty()) {
                    repository.replaceScores(remoteData.scores)
                }
                _scoreUnavailableReason.value = remoteData.scoreUnavailableReason

                // 返回 null 表示保持现状：用户刚在续期弹窗里取消，那句「会话已过期，已取消登录」
                // 不该被这里盖成「未获取到可用于计算的数据」——那会抹掉他刚做的动作。
                professionalScoreRefreshMessage(
                    renewalCancelled = remoteData.renewalCancelled,
                    scoreUnavailableReason = remoteData.scoreUnavailableReason,
                    scoreCount = remoteData.scores.size,
                    courseCount = remoteData.courses.size
                )?.let { _message.value = it }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Professional score data refresh failed", error)
                _message.value = "获取失败：${error.message}"
            } finally {
                _isRefreshing.value = false
            }
            messageJob = viewModelScope.launch {
                delay(4000)
                _message.value = ""
            }
        }
    }

    private suspend fun fetchRemoteDataWithLoginRetry(): ProfessionalScoreRemoteData {
        var campusBaseUrl = sessionStore.campusBaseUrl.first()
            .ifBlank { AcademicLoginResult.DEFAULT_GUILIN_URL }
        var cookie = sessionStore.academicCookie.first()

        suspend fun fetchIfPossible(): ProfessionalScoreRemoteData {
            if (cookie.isBlank()) return ProfessionalScoreRemoteData()
            val (groups, courses) = fetchStudyPlanData(cookie, campusBaseUrl)
            val scoreFetch = fetchScores(cookie, campusBaseUrl)
            return ProfessionalScoreRemoteData(
                scores = scoreFetch.scores,
                groups = groups,
                courses = courses,
                scoreUnavailableReason = scoreFetch.unavailableReason
            )
        }

        var data = fetchIfPossible()
        if (
            canUseProfessionalScoreRemoteDataWithoutLoginRetry(
                hasCookie = cookie.isNotBlank(),
                hasScores = data.scores.isNotEmpty(),
                hasStudyPlan = data.groups.isNotEmpty() && data.courses.isNotEmpty(),
                scoreUnavailableReason = data.scoreUnavailableReason
            )
        ) {
            return data
        }

        if (cookie.isNotBlank() && data.scoreUnavailableReason.isNotBlank()) {
            _message.value = "成绩页受限，正在重新登录后重试..."
        } else {
            _message.value = "会话已过期，正在尝试自动登录..."
        }
        // 会话失效：原地续期——南宁/研究生弹验证码，桂林静默换票。
        // 用户取消或续期失败都**保留已有缓存**：直接返回手上这份数据（可能为空），
        // 由界面照常提示，不再像以前那样 throw 出去把整页打成错误态。
        if (!renewal.ensureFreshSession()) {
            _message.value = "会话已过期，已取消登录"
            // 打上标记：让 refreshData 别再改这条提示——用户刚做的动作不该被抹掉。
            return data.copy(renewalCancelled = true)
        }
        // 续期成功后重读会话：cookie 与主机都由协调器写好了，这里不再自己猜。
        cookie = sessionStore.academicCookie.first()
        campusBaseUrl = sessionStore.campusBaseUrl.first()
            .ifBlank { AcademicLoginResult.DEFAULT_GUILIN_URL }
        data = fetchIfPossible()
        return data
    }

    private suspend fun fetchScores(cookie: String, campusBaseUrl: String): ScoreFetchResult {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        val formBody = FormBody.Builder()
            .add("year", "")
            .add("term", "")
            .add("prop", "")
            .add("groupName", "")
            .add("para", "0")
            .add("sortColumn", "")
            .add("Submit", "查询")
            .build()

        val request = Request.Builder()
            .url("$campusBaseUrl/academic/manager/score/studentOwnScore.do")
            .header("Cookie", cookie)
            .header("User-Agent", UA)
            .post(formBody)
            .build()

        val (body, contentType) = withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val rawBytes = response.body?.readBytesLimited(MAX_HTML_RESPONSE_BYTES)
                    ?: ByteArray(0)
                val ct = response.header("Content-Type") ?: ""
                Pair(rawBytes, ct)
            }
        }

        val html = String(body, detectCharset(contentType))
        val blockedReason = scorePageUnavailableReason(html)
        if (blockedReason != null) {
            return ScoreFetchResult(scores = emptyList(), unavailableReason = blockedReason)
        }
        val isNanning = campusBaseUrl == AcademicLoginResult.NANNING_URL
        val attributeMap = if (isNanning) {
            fetchNanningAttributeMap(cookie, campusBaseUrl, client)
        } else {
            emptyMap()
        }

        return ScoreFetchResult(
            scores = scoreParser.parseScoreHtml(
                html = html,
                isNanning = isNanning,
                attributeMap = attributeMap
            )
        )
    }

    private suspend fun fetchStudyPlanData(
        cookie: String,
        campusBaseUrl: String
    ): Pair<List<StudyPlanGroup>, List<StudyPlanCourse>> {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        val selfScheduleUrl = "$campusBaseUrl/academic/manager/studyschedule/studentSelfSchedule.jsdo"
        val selfBytes = withContext(Dispatchers.IO) {
            client.newCall(
                Request.Builder()
                    .url(selfScheduleUrl)
                    .header("Cookie", cookie)
                    .header("User-Agent", UA)
                    .get()
                    .build()
            ).execute().use { response ->
                response.body?.readBytesLimited(MAX_HTML_RESPONSE_BYTES) ?: ByteArray(0)
            }
        }
        val selfHtml = String(selfBytes, Charset.forName("GBK"))
        val ids = studyPlanParser.parseStudentIds(selfHtml) ?: return Pair(emptyList(), emptyList())
        val (studentId, classId) = ids

        val lineShowUrl = "$campusBaseUrl/academic/manager/studyschedule/studentScheduleLineShow.do?z=z&studentId=$studentId&classId=$classId"
        val (lineBytes, lineContentType) = withContext(Dispatchers.IO) {
            client.newCall(
                Request.Builder()
                    .url(lineShowUrl)
                    .header("Cookie", cookie)
                    .header("User-Agent", UA)
                    .get()
                    .build()
            ).execute().use { response ->
                val rawBytes = response.body?.readBytesLimited(MAX_HTML_RESPONSE_BYTES)
                    ?: ByteArray(0)
                val ct = response.header("Content-Type") ?: ""
                Pair(rawBytes, ct)
            }
        }

        val lineHtml = String(lineBytes, detectCharset(lineContentType, defaultCharsetName = "UTF-8"))
        return studyPlanParser.parseData(lineHtml)
    }

    private suspend fun fetchNanningAttributeMap(
        cookie: String,
        campusBaseUrl: String,
        client: OkHttpClient
    ): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            val selfUrl = "$campusBaseUrl/academic/manager/studyschedule/studentSelfSchedule.jsdo"
            val selfBody = client.newCall(
                Request.Builder()
                    .url(selfUrl)
                    .header("Cookie", cookie)
                    .header("User-Agent", UA)
                    .get()
                    .build()
            ).execute().use { response ->
                response.body?.readBytesLimited(MAX_HTML_RESPONSE_BYTES) ?: ByteArray(0)
            }
            val selfHtml = String(selfBody, Charset.forName("GBK"))
            val ids = studyPlanParser.parseStudentIds(selfHtml) ?: return@withContext emptyMap()
            val (studentId, classId) = ids

            val lineUrl = "$campusBaseUrl/academic/manager/studyschedule/studentScheduleLineShow.do?z=z&studentId=$studentId&classId=$classId"
            val lineBody = client.newCall(
                Request.Builder()
                    .url(lineUrl)
                    .header("Cookie", cookie)
                    .header("User-Agent", UA)
                    .get()
                    .build()
            ).execute().use { response ->
                response.body?.readBytesLimited(MAX_HTML_RESPONSE_BYTES) ?: ByteArray(0)
            }
            val lineHtml = String(lineBody, Charsets.UTF_8)
            studyPlanParser.parseCourseAttributeMap(lineHtml)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "Failed to fetch Nanning attribute map", error)
            emptyMap()
        }
    }

    private fun detectCharset(
        contentType: String,
        defaultCharsetName: String = "GBK"
    ): Charset {
        val charsetName = if (contentType.contains("charset=", ignoreCase = true)) {
            contentType.substringAfter("charset=").trim().removePrefix("\"").removeSuffix("\"")
        } else {
            defaultCharsetName
        }
        return try {
            Charset.forName(charsetName)
        } catch (_: Exception) {
            Charset.forName(defaultCharsetName)
        }
    }

    companion object {
        private const val TAG = "ProfessionalScoreVM"
        private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
    }

    private data class ProfessionalScoreRemoteData(
        val scores: List<ScoreInfo> = emptyList(),
        val groups: List<StudyPlanGroup> = emptyList(),
        val courses: List<StudyPlanCourse> = emptyList(),
        val scoreUnavailableReason: String = "",
        /** 用户在续期弹窗里点了取消；此时状态条上那句提示已经写好，不要再覆盖。 */
        val renewalCancelled: Boolean = false
    ) {
        val hasAnyRemoteData: Boolean = scores.isNotEmpty() || groups.isNotEmpty() || courses.isNotEmpty()
    }

    private data class ScoreFetchResult(
        val scores: List<ScoreInfo>,
        val unavailableReason: String = ""
    )
}

class ProfessionalScoreViewModelFactory(
    private val repository: ScheduleRepository,
    private val settingsStore: ScheduleSettingsStore,
    private val sessionStore: AcademicSessionStore,
    /** 会话失效时的续期门卫：南宁/研究生原地弹验证码，桂林静默换票。 */
    private val renewal: SessionRenewalCoordinator,
    private val scoreParser: ScoreParser = ScoreParser(),
    private val studyPlanParser: StudyPlanParser = StudyPlanParser()
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ProfessionalScoreViewModel(
            repository = repository,
            settingsStore = settingsStore,
            sessionStore = sessionStore,
            renewal = renewal,
            scoreParser = scoreParser,
            studyPlanParser = studyPlanParser
        ) as T
    }
}
