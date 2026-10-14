package com.glut.schedule.ui.pages

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glut.schedule.data.model.ScoreInfo
import com.glut.schedule.data.repository.ScheduleRepository
import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.service.academic.AcademicLoginResult
import com.glut.schedule.service.academic.AcademicLoginService
import com.glut.schedule.service.academic.scorePageUnavailableReason
import com.glut.schedule.service.academic.AcademicSessionStore
import com.glut.schedule.service.academic.SessionRenewalCoordinator
import com.glut.schedule.service.graduate.GraduateClient
import com.glut.schedule.service.graduate.GraduateParser
import com.glut.schedule.service.graduate.GraduateSessionExpiredException
import com.glut.schedule.service.parser.ScoreParser
import com.glut.schedule.service.parser.StudyPlanParser
import com.glut.schedule.service.network.MAX_HTML_RESPONSE_BYTES
import com.glut.schedule.service.network.readBytesLimited
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

data class ScoreUiState(
    val scores: List<ScoreInfo> = emptyList(),
    val isRefreshing: Boolean = false,
    val message: String = "",
    val hasCookie: Boolean = false
)

class ScoreViewModel(
    private val repository: ScheduleRepository,
    private val sessionStore: AcademicSessionStore,
    /** 会话失效时的续期门卫：南宁/研究生原地弹验证码，桂林静默换票。 */
    private val renewal: SessionRenewalCoordinator,
    private val scoreParser: ScoreParser = ScoreParser(),
    private val studyPlanParser: StudyPlanParser = StudyPlanParser(),
    /** 当前来源；刷新链路对研究生必须换网关（见 [refreshScores]）。刷新时读取，不做构造快照。 */
    private val campusTypeFlow: Flow<CampusType> = flowOf(CampusType.GUILIN)
) : ViewModel() {

    private val _isRefreshing = MutableStateFlow(false)
    private val _message = MutableStateFlow("")
    private val _selectedYear = MutableStateFlow<String?>(null)
    private var scoreBlockedByEvaluation = false
    private var scorePageErrorMessage: String? = null

    val uiState: StateFlow<ScoreUiState> = combine(
        repository.scores,
        sessionStore.academicCookie,
        _isRefreshing,
        _message
    ) { scores, cookie, isRefreshing, message ->
        ScoreUiState(
            scores = scores,
            isRefreshing = isRefreshing,
            message = message,
            hasCookie = cookie.isNotBlank()
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ScoreUiState()
    )

    val availableYears: StateFlow<List<String>> = repository.scores
        .map { scores -> scores.map { it.year }.distinct().sortedDescending() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedYear: StateFlow<String?> = _selectedYear

    fun selectYear(year: String?) {
        _selectedYear.value = year
    }

    fun refreshScores() {
        viewModelScope.launch {
            // 来源在**刷新时**读取，而不是构造时快照：本 ViewModel 是 Activity 级缓存实例，
            // 构造时还不知道用户会导入哪个来源，用构造参数会让导入研究生后仍走本科链路。
            if (campusTypeFlow.first() == CampusType.GRADUATE) {
                refreshGraduateScores()
            } else {
                refreshAcademicScores()
            }
        }
    }

    /**
     * 本科/南宁成绩刷新。
     *
     * 对研究生**绝不可达**：这里会拿会话 Cookie 打 `jw.glut.edu.cn`，并在 `silentLogin()`
     * 成功后把 `campusBaseUrl` 覆盖成本科地址，反过来破坏研究生会话（见 [refreshScores]）。
     */
    private suspend fun refreshAcademicScores() {
        _isRefreshing.value = true
        _message.value = "正在获取成绩..."
        try {
            // Try existing cookie first
            var cookie = sessionStore.academicCookie.first()
            var allScores = if (cookie.isNotBlank()) fetchAllScores(cookie) else emptyList()

            // 会话失效：原地续期——南宁/研究生弹验证码，桂林静默换票。
            // 用户取消或续期失败都**保留已有缓存**，只提示、不动屏幕上已有的成绩。
            // 续期成功后只重放这一次取数；仍为空就交给下面的评教判定与空状态处理，语义不变。
            if (allScores.isEmpty()) {
                if (!renewal.ensureFreshSession()) {
                    _message.value = "会话已过期，已取消登录"
                    delay(4000)
                    _message.value = ""
                    return
                }
                cookie = sessionStore.academicCookie.first()
                allScores = fetchAllScores(cookie)
            }

            if (scoreBlockedByEvaluation) {
                _message.value = "你有课程未完成评教，必须完成全部评教才能查看成绩，可以在微信小程序中搜索【教学质量管理平台】并完成全部的评教，等待1-2天即可查看成绩"
                scoreBlockedByEvaluation = false
            } else if (scorePageErrorMessage != null) {
                _message.value = scorePageErrorMessage.orEmpty()
                scorePageErrorMessage = null
            } else if (allScores.isNotEmpty()) {
                repository.replaceScores(allScores)
                _message.value = "已获取 ${allScores.size} 条成绩记录"
            } else {
                _message.value = "未获取到成绩数据"
            }
        } catch (e: Exception) {
            _message.value = "获取失败: ${e.message}"
            Log.e(TAG, "Score fetch failed", e)
        } finally {
            _isRefreshing.value = false
            delay(4000)
            _message.value = ""
        }
    }

    /**
     * 研究生成绩刷新：一个网关调用，**不做静默登录**。
     *
     * 门户需要图形验证码，无法在后台补登录——会话失效时走 [renewal] 原地弹验证码并重放这一次取数，
     * 而不是拿记住的密码去撞本科教务。用户取消或续期失败都保留已有缓存。
     */
    private suspend fun refreshGraduateScores() {
        _isRefreshing.value = true
        _message.value = "正在获取成绩..."
        try {
            var cookie = sessionStore.academicCookie.first()
            var studentId = sessionStore.authenticatedStudentNumber.first()

            // cookie 缺失、学号缺失，或门户报「登录已失效」，都属于同一种情况：会话没了。
            // 研究生门户要图形验证码、无法静默补登录——原地续期（弹验证码）后重试一次。
            // 只对**会话失效**续期：网络抖动之类的失败照旧直接提示，不该为它弹验证码框。
            var attempt = if (cookie.isBlank() || studentId.isBlank()) {
                Result.failure(GraduateSessionExpiredException("研究生登录已失效，请重新登录"))
            } else {
                fetchGraduateScores(cookie, studentId)
            }
            val sessionLooksDead = cookie.isBlank() || studentId.isBlank() ||
                attempt.exceptionOrNull() is GraduateSessionExpiredException
            if (attempt.isFailure && sessionLooksDead) {
                if (!renewal.ensureFreshSession()) {
                    // 用户取消或续期失败：保留屏幕上已有的成绩，只提示。
                    _message.value = "会话已过期，已取消登录"
                    return
                }
                cookie = sessionStore.academicCookie.first()
                studentId = sessionStore.authenticatedStudentNumber.first()
                attempt = fetchGraduateScores(cookie, studentId)
            }
            val scores = attempt.getOrElse { error ->
                _message.value = "获取失败: ${error.message}"
                return
            }
            repository.replaceScores(scores)
            _message.value = if (scores.isEmpty()) {
                "未获取到成绩数据"
            } else {
                "已获取 ${scores.size} 条成绩记录"
            }
        } catch (e: Exception) {
            _message.value = "获取失败: ${e.message}"
        } finally {
            _isRefreshing.value = false
            delay(4000)
            _message.value = ""
        }
    }

    /**
     * 研究生成绩的一次取数。
     *
     * 返回 `Result` 而不是直接抛：调用方要靠 `exceptionOrNull()` 判断是不是会话失效，
     * 再决定续期还是照旧提示。`CancellationException` 必须原样抛出，否则页面退出时
     * 会被当成「取数失败」而弹出一个验证码框。
     */
    private suspend fun fetchGraduateScores(cookie: String, studentId: String): Result<List<ScoreInfo>> =
        try {
            Result.success(
                withContext(Dispatchers.IO) {
                    GraduateParser.scores(GraduateClient(cookie).scores(studentId))
                }
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }

    private suspend fun fetchAllScores(cookie: String): List<ScoreInfo> {
        val campusBaseUrl = sessionStore.campusBaseUrl.first()
            .ifBlank { AcademicLoginResult.DEFAULT_GUILIN_URL }

        val scoreClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        // POST with form body — matches the HTML form method and all reference projects
        // (GlutAssistant, GlutAssistantN, glut Android)
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


        val (body, responseCode, contentType) = withContext(Dispatchers.IO) {
            scoreClient.newCall(request).execute().use { response ->
                val rawBytes = response.body?.readBytesLimited(MAX_HTML_RESPONSE_BYTES)
                    ?: ByteArray(0)
                val ct = response.header("Content-Type") ?: ""
                val code = response.code
                Triple(rawBytes, code, ct)
            }
        }

        Log.d(TAG, "Score response: code=$responseCode, contentType=$contentType, bodyLen=${body.size}")

        // GLUT academic system returns GBK/GB2312 encoded HTML.
        // Try to detect charset from Content-Type, fall back to GBK.
        val charset = detectCharset(contentType)
        val html = String(body, charset)

        val unavailableReason = scorePageUnavailableReason(html)
        scoreBlockedByEvaluation = isEvaluationBlocked(html)
        scorePageErrorMessage = unavailableReason?.takeUnless { scoreBlockedByEvaluation }
        if (scoreBlockedByEvaluation) {
            Log.w(TAG, "Scores blocked by teaching evaluation")
            return emptyList()
        }
        if (scorePageErrorMessage != null) {
            Log.w(TAG, "Academic score endpoint returned an error page")
            return emptyList()
        }

        val isNanning = campusBaseUrl == AcademicLoginResult.NANNING_URL
        Log.d(TAG, "Parsing scores: isNanning=$isNanning")

        // For Nanning, fetch teaching plan to build 课程类别 → 选课属性 mapping
        val attributeMap = if (isNanning) fetchNanningAttributeMap(cookie, campusBaseUrl, scoreClient) else emptyMap()
        Log.d(TAG, "Attribute map entries: ${attributeMap.size}")

        val scores = scoreParser.parseScoreHtml(html, isNanning = isNanning, attributeMap = attributeMap)
        Log.d(TAG, "Parsed ${scores.size} scores")

        return scores
    }

    /**
     * Fetch Nanning teaching plan page and extract 课程类别 → 选课属性 mapping.
     * Two-step: (1) get studentId+classId from studentSelfSchedule.jsdo, (2) parse mapping from studentScheduleLineShow.do.
     */
    private suspend fun fetchNanningAttributeMap(
        cookie: String,
        campusBaseUrl: String,
        client: OkHttpClient
    ): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            // Step 1: Fetch studentSelfSchedule.jsdo to get studentId + classId
            val selfUrl = "$campusBaseUrl/academic/manager/studyschedule/studentSelfSchedule.jsdo"
            val selfBody = client.newCall(Request.Builder().url(selfUrl)
                .header("Cookie", cookie).header("User-Agent", UA).get().build()
            ).execute().use { response ->
                response.body?.readBytesLimited(MAX_HTML_RESPONSE_BYTES) ?: ByteArray(0)
            }
            val selfHtml = String(selfBody, Charset.forName("GBK"))
            val ids = studyPlanParser.parseStudentIds(selfHtml) ?: return@withContext emptyMap()
            val (studentId, classId) = ids

            // Step 2: Fetch studentScheduleLineShow.do
            val lineUrl = "$campusBaseUrl/academic/manager/studyschedule/studentScheduleLineShow.do?z=z&studentId=$studentId&classId=$classId"
            val lineBody = client.newCall(Request.Builder().url(lineUrl)
                .header("Cookie", cookie).header("User-Agent", UA).get().build()
            ).execute().use { response ->
                response.body?.readBytesLimited(MAX_HTML_RESPONSE_BYTES) ?: ByteArray(0)
            }
            val lineHtml = String(lineBody, Charsets.UTF_8)

            studyPlanParser.parseCourseAttributeMap(lineHtml)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch Nanning attribute map", e)
            emptyMap()
        }
    }

    /**
     * Detect charset from Content-Type header. The GLUT academic system returns
     * GBK/GB2312 encoded pages. If no charset is specified, default to GBK
     * (the server's native encoding for Chinese).
     */
    private fun detectCharset(contentType: String): Charset {
        val charsetName = if (contentType.contains("charset=", ignoreCase = true)) {
            contentType.substringAfter("charset=").trim().removePrefix("\"").removeSuffix("\"")
        } else {
            "GBK"
        }
        return try {
            Charset.forName(charsetName)
        } catch (_: Exception) {
            Charsets.UTF_8
        }
    }

    fun clearMessage() { _message.value = "" }

    companion object {
        private const val TAG = "ScoreViewModel"
        private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"

        fun isEvaluationBlocked(html: String): Boolean {
            val text = html.ifBlank { "" }
            return text.contains("评教") && text.contains("不能查看成绩")
        }
    }
}

class ScoreViewModelFactory(
    private val repository: ScheduleRepository,
    private val sessionStore: AcademicSessionStore,
    /** 会话失效时的续期门卫：南宁/研究生原地弹验证码，桂林静默换票。 */
    private val renewal: SessionRenewalCoordinator,
    private val scoreParser: ScoreParser = ScoreParser(),
    private val studyPlanParser: StudyPlanParser = StudyPlanParser(),
    private val campusTypeFlow: Flow<CampusType> = flowOf(CampusType.GUILIN)
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ScoreViewModel(
            repository, sessionStore, renewal,
            scoreParser, studyPlanParser, campusTypeFlow
        ) as T
    }
}
