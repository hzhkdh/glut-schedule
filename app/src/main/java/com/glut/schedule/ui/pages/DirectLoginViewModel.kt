package com.glut.schedule.ui.pages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glut.schedule.data.model.ScoreInfo
import com.glut.schedule.data.repository.ScheduleRepository
import com.glut.schedule.data.settings.ScheduleSettingsStore
import com.glut.schedule.service.academic.AcademicLoginHttpClient
import com.glut.schedule.service.academic.AcademicLoginResult
import com.glut.schedule.service.academic.AcademicLoginService
import com.glut.schedule.service.academic.AcademicOALoginClient
import com.glut.schedule.service.academic.AcademicSessionStore
import com.glut.schedule.service.academic.ApiProbeService
import com.glut.schedule.service.academic.CredentialStore
import com.glut.schedule.service.parser.AcademicScheduleParser
import com.glut.schedule.service.parser.GlutExamParser
import com.glut.schedule.service.parser.ScoreParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class DirectLoginUiState(
    val username: String = "",
    val password: String = "",
    val rememberPassword: Boolean = true,
    val isLoggingIn: Boolean = false,
    val message: String = "",
    val importResult: ImportResult? = null
)

data class ImportResult(
    val courseCount: Int,
    val examCount: Int,
    val scoreCount: Int
)

class DirectLoginViewModel(
    private val loginService: AcademicLoginService,
    private val sessionStore: AcademicSessionStore,
    private val credentialStore: CredentialStore,
    private val scheduleRepository: ScheduleRepository,
    private val settingsStore: ScheduleSettingsStore,
    private val apiProbeService: ApiProbeService,
    private val scheduleParser: AcademicScheduleParser,
    private val examParser: GlutExamParser,
    private val scoreParser: ScoreParser
) : ViewModel() {

    private val _uiState = MutableStateFlow(DirectLoginUiState())
    val uiState: StateFlow<DirectLoginUiState> = _uiState

    private val loginHttpClient = AcademicLoginHttpClient()
    private val oaLoginClient = AcademicOALoginClient()

    init {
        viewModelScope.launch {
            val savedUsername = credentialStore.getUsername()
            val savedPassword = credentialStore.getPassword()
            _uiState.value = _uiState.value.copy(
                username = savedUsername,
                password = savedPassword,
                rememberPassword = savedUsername.isNotBlank()
            )
        }
    }

    fun updateUsername(username: String) { _uiState.value = _uiState.value.copy(username = username) }
    fun updatePassword(password: String) { _uiState.value = _uiState.value.copy(password = password) }
    fun updateRememberPassword(remember: Boolean) { _uiState.value = _uiState.value.copy(rememberPassword = remember) }

    fun loginAndImport() {
        val state = _uiState.value
        if (state.username.isBlank() || state.password.isBlank()) {
            _uiState.value = state.copy(message = "请输入学号和密码")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoggingIn = true, message = "正在登录...")

            try {
                val result = loginHttpClient.login(state.username, state.password)
                when (result) {
                    is AcademicLoginResult.Success -> {
                        if (state.rememberPassword) {
                            credentialStore.saveCredentials(state.username, state.password)
                        }
                        sessionStore.saveCookie(result.cookie)
                        _uiState.value = _uiState.value.copy(message = "登录成功，正在导入数据...")
                        performImport(result.cookie)
                    }
                    AcademicLoginResult.MissingCredentials ->
                        _uiState.value = _uiState.value.copy(isLoggingIn = false, message = "请输入学号和密码")
                    AcademicLoginResult.InvalidCredentials -> {
                        _uiState.value = _uiState.value.copy(isLoggingIn = false, message = "学号或密码错误，请重试")
                    }
                    AcademicLoginResult.CaptchaOrInteractiveLoginRequired -> {
                        // JW direct login requires captcha — try OA unified auth as fallback
                        _uiState.value = _uiState.value.copy(message = "正在尝试统一身份认证登录...")
                        val oaResult = oaLoginClient.login(state.username, state.password)
                        when (oaResult) {
                            is AcademicLoginResult.Success -> {
                                if (state.rememberPassword) {
                                    credentialStore.saveCredentials(state.username, state.password)
                                }
                                sessionStore.saveCookie(oaResult.cookie)
                                _uiState.value = _uiState.value.copy(message = "OA登录成功，正在导入数据...")
                                performImport(oaResult.cookie)
                            }
                            AcademicLoginResult.InvalidCredentials ->
                                _uiState.value = _uiState.value.copy(isLoggingIn = false, message = "学号或密码错误，请重试")
                            else ->
                                _uiState.value = _uiState.value.copy(isLoggingIn = false, message = "教务登录需要验证码，OA登录也失败，请稍后重试")
                        }
                    }
                    is AcademicLoginResult.NetworkError ->
                        _uiState.value = _uiState.value.copy(isLoggingIn = false, message = "网络错误: ${result.message}")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoggingIn = false, message = "登录失败: ${e.message}")
            }
        }
    }

    private suspend fun performImport(cookie: String) {
        var courseCount = 0
        var examCount = 0
        var scoreCount = 0

        try {
            val results = apiProbeService.probeAllEndpoints(cookie = cookie)
            val calendar = ApiProbeService.extractAcademicCalendar(results)
            if (calendar != null) {
                settingsStore.setSemesterStartMonday(calendar.semesterStartMonday)
                calendar.semesterEndDate?.let { settingsStore.setSemesterEndDate(it) }
                settingsStore.setCurrentWeekNumber(calendar.currentWeekNumber)
            }

            val htmlResult = apiProbeService.findTimetableHtmlResult(results)
            if (htmlResult != null) {
                sessionStore.saveHtmlPreview(htmlResult.body.take(3000))
                val courses = runCatching {
                    scheduleParser.parsePersonalSchedule(htmlResult.body)
                }.getOrDefault(emptyList())
                if (courses.isNotEmpty()) {
                    scheduleRepository.replaceImportedCourses(courses)
                    courseCount = courses.size
                }
            }

            val examJsonResult = apiProbeService.findExamJsonResult(results)
            if (examJsonResult != null) {
                val exams = runCatching { examParser.parseExamJson(examJsonResult.body) }.getOrDefault(emptyList())
                if (exams.isNotEmpty()) {
                    scheduleRepository.replaceExams(exams)
                    examCount = exams.size
                }
            }
            if (examCount == 0) {
                val examHtmlResult = apiProbeService.findExamHtmlResult(results)
                if (examHtmlResult != null) {
                    examCount = runCatching { examParser.parseExamHtml(examHtmlResult.body) }.getOrDefault(emptyList()).also {
                        if (it.isNotEmpty()) scheduleRepository.replaceExams(it)
                    }.size
                }
            }

            scoreCount = fetchAndSaveScores(cookie)

            _uiState.value = _uiState.value.copy(
                isLoggingIn = false,
                message = "导入完成",
                importResult = ImportResult(courseCount, examCount, scoreCount)
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                isLoggingIn = false,
                message = "部分导入失败: ${e.message}",
                importResult = ImportResult(courseCount, examCount, scoreCount)
            )
        }
    }

    private suspend fun fetchAndSaveScores(cookie: String): Int {
        val scoreClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        try {
            // Single GET with empty params returns ALL scores across all semesters
            // (optimized from 8 POST requests down to 1)
            val request = Request.Builder()
                .url("http://jw.glut.edu.cn/academic/manager/score/studentOwnScore.do?year=&term=&para=0")
                .header("Cookie", cookie)
                .header("User-Agent", MOBILE_USER_AGENT)
                .get()
                .build()
            val body = withContext(Dispatchers.IO) {
                scoreClient.newCall(request).execute().use { response ->
                    response.body?.string().orEmpty()
                }
            }
            // year/term extracted from HTML cells automatically by ScoreParser
            val scores = scoreParser.parseScoreHtml(body)
            if (scores.isNotEmpty()) scheduleRepository.replaceScores(scores)
            return scores.size
        } catch (_: Exception) {
            return 0
        }
    }

    private companion object {
        const val MOBILE_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
    }
}

class DirectLoginViewModelFactory(
    private val loginService: AcademicLoginService,
    private val sessionStore: AcademicSessionStore,
    private val credentialStore: CredentialStore,
    private val scheduleRepository: ScheduleRepository,
    private val settingsStore: ScheduleSettingsStore,
    private val apiProbeService: ApiProbeService,
    private val scheduleParser: AcademicScheduleParser,
    private val examParser: GlutExamParser,
    private val scoreParser: ScoreParser
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return DirectLoginViewModel(
            loginService, sessionStore, credentialStore,
            scheduleRepository, settingsStore, apiProbeService,
            scheduleParser, examParser, scoreParser
        ) as T
    }
}
