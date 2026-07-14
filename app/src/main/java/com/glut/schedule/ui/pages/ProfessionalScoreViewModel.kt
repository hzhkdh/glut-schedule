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
import com.glut.schedule.data.repository.ScheduleRepository
import com.glut.schedule.service.academic.AcademicLoginResult
import com.glut.schedule.service.academic.AcademicLoginService
import com.glut.schedule.service.academic.AcademicSessionStore
import com.glut.schedule.service.parser.ScoreParser
import com.glut.schedule.service.parser.StudyPlanParser
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
    private val sessionStore: AcademicSessionStore,
    private val loginService: AcademicLoginService,
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
            sessionStore.academicCookie
        ) { groupsWithCourses, scores, cookie ->
            Triple(groupsWithCourses, scores, cookie)
        },
        _isRefreshing,
        _message,
        _scoreUnavailableReason,
        _selectedAcademicYear
    ) { (groupsWithCourses, scores, cookie), isRefreshing, message, scoreUnavailableReason, selectedAcademicYear ->
        val academicYears = ProfessionalScoreCalculator.availableAcademicYears(groupsWithCourses)
        val effectiveAcademicYear = selectedAcademicYear
            ?.takeIf { it in academicYears }
            ?: academicYears.lastOrNull()
        val result = effectiveAcademicYear?.let {
            ProfessionalScoreCalculator.calculate(
                academicYear = it,
                groupsWithCourses = groupsWithCourses,
                scores = scores
            )
        }

        ProfessionalScoreUiState(
            availableAcademicYears = academicYears,
            selectedAcademicYear = effectiveAcademicYear,
            result = result,
            isRefreshing = isRefreshing,
            message = message,
            scoreUnavailableReason = scoreUnavailableReason,
            hasCookie = cookie.isNotBlank(),
            hasStudyPlanData = groupsWithCourses.any { it.courses.isNotEmpty() },
            hasScoreData = scores.isNotEmpty()
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ProfessionalScoreUiState()
    )

    fun selectAcademicYear(academicYear: String) {
        _selectedAcademicYear.value = academicYear
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

                _message.value = when {
                    remoteData.scoreUnavailableReason.isNotBlank() -> remoteData.scoreUnavailableReason
                    remoteData.scores.isNotEmpty() || remoteData.groups.isNotEmpty() ->
                        "已更新：${remoteData.scores.size} 条成绩，${remoteData.courses.size} 门计划课程"
                    else -> "未获取到可用于计算的数据"
                }
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
        when (val loginResult = loginService.silentLogin()) {
            is AcademicLoginResult.Success -> {
                cookie = sessionStore.academicCookie.first()
                campusBaseUrl = loginResult.campusBaseUrl
                sessionStore.saveCampusBaseUrl(campusBaseUrl)
                data = fetchIfPossible()
            }
            AcademicLoginResult.MissingCredentials ->
                throw IllegalStateException("请先在导入课表页面登录教务系统并保存账号")
            AcademicLoginResult.InvalidCredentials ->
                throw IllegalStateException("教务账号或密码错误，请重新登录")
            AcademicLoginResult.CaptchaOrInteractiveLoginRequired -> {
                val isNanning = campusBaseUrl == AcademicLoginResult.NANNING_URL
                throw IllegalStateException(
                    if (isNanning) "南宁登录需验证码，请到导入课表页面重新登录"
                    else "教务需要手动验证，请到导入课表页面重新登录"
                )
            }
            is AcademicLoginResult.NetworkError ->
                throw IllegalStateException("自动登录失败：${loginResult.message}")
        }
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
                val rawBytes = response.body?.bytes() ?: ByteArray(0)
                val ct = response.header("Content-Type") ?: ""
                Pair(rawBytes, ct)
            }
        }

        val html = String(body, detectCharset(contentType))
        val blockedReason = scoreAccessBlockReason(html)
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
                response.body?.bytes() ?: ByteArray(0)
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
                val rawBytes = response.body?.bytes() ?: ByteArray(0)
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
                response.body?.bytes() ?: ByteArray(0)
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
                response.body?.bytes() ?: ByteArray(0)
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

    private fun scoreAccessBlockReason(html: String): String? {
        val compact = html.replace(Regex("""\s+"""), "")
        return when {
            compact.contains("没有参加评教") && compact.contains("不能查看成绩") ->
                "教务系统提示：有课程未参加评教，暂时不能查看成绩"
            else -> null
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
        val scoreUnavailableReason: String = ""
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
    private val sessionStore: AcademicSessionStore,
    private val loginService: AcademicLoginService,
    private val scoreParser: ScoreParser = ScoreParser(),
    private val studyPlanParser: StudyPlanParser = StudyPlanParser()
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ProfessionalScoreViewModel(
            repository = repository,
            sessionStore = sessionStore,
            loginService = loginService,
            scoreParser = scoreParser,
            studyPlanParser = studyPlanParser
        ) as T
    }
}
