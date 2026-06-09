package com.glut.schedule.ui.pages

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glut.schedule.data.model.ScoreInfo
import com.glut.schedule.data.repository.ScheduleRepository
import com.glut.schedule.service.academic.AcademicLoginResult
import com.glut.schedule.service.academic.AcademicLoginService
import com.glut.schedule.service.academic.AcademicSessionStore
import com.glut.schedule.service.parser.ScoreParser
import com.glut.schedule.service.parser.StudyPlanParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    private val loginService: AcademicLoginService,
    private val scoreParser: ScoreParser = ScoreParser(),
    private val studyPlanParser: StudyPlanParser = StudyPlanParser()
) : ViewModel() {

    private val _isRefreshing = MutableStateFlow(false)
    private val _message = MutableStateFlow("")
    private val _selectedYear = MutableStateFlow<String?>(null)

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
            _isRefreshing.value = true
            _message.value = "正在获取成绩..."
            try {
                var cookie = sessionStore.academicCookie.first()
                if (cookie.isBlank()) {
                    val loginResult = loginService.silentLogin()
                    if (loginResult is AcademicLoginResult.Success) {
                        cookie = sessionStore.academicCookie.first()
                    } else {
                        _message.value = "请先在导入课表页面登录教务系统"
                        delay(4000)
                        _message.value = ""
                        return@launch
                    }
                }

                val allScores = fetchAllScores(cookie)
                if (allScores.isNotEmpty()) {
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

        Log.d(TAG, "Fetching scores from: $campusBaseUrl")

        val (body, responseCode, contentType) = withContext(Dispatchers.IO) {
            scoreClient.newCall(request).execute().use { response ->
                val rawBytes = response.body?.bytes() ?: ByteArray(0)
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
        Log.d(TAG, "Score HTML preview (first 300 chars): ${html.take(300)}")

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
                response.body?.bytes() ?: ByteArray(0)
            }
            val selfHtml = String(selfBody, Charset.forName("GBK"))
            val ids = studyPlanParser.parseStudentIds(selfHtml) ?: return@withContext emptyMap()
            val (studentId, classId) = ids

            // Step 2: Fetch studentScheduleLineShow.do
            val lineUrl = "$campusBaseUrl/academic/manager/studyschedule/studentScheduleLineShow.do?z=z&studentId=$studentId&classId=$classId"
            val lineBody = client.newCall(Request.Builder().url(lineUrl)
                .header("Cookie", cookie).header("User-Agent", UA).get().build()
            ).execute().use { response ->
                response.body?.bytes() ?: ByteArray(0)
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
    }
}

class ScoreViewModelFactory(
    private val repository: ScheduleRepository,
    private val sessionStore: AcademicSessionStore,
    private val loginService: AcademicLoginService,
    private val scoreParser: ScoreParser = ScoreParser(),
    private val studyPlanParser: StudyPlanParser = StudyPlanParser()
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ScoreViewModel(repository, sessionStore, loginService, scoreParser, studyPlanParser) as T
    }
}
