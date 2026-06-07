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
    val isNanning: Boolean = false,
    val isLoggingIn: Boolean = false,
    val showNanningWebView: Boolean = false,
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
    private val nanningLoginClient = AcademicLoginHttpClient(
        baseUrl = AcademicLoginResult.NANNING_URL,
        loginPagePath = "/academic/common/security/affairLogin.jsp",
        usePostLogin = true  // Nanning requires POST (not GET) to skip MD5 client-side hashing
    )
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

    fun updateUsername(username: String) {
        // Auto-detect campus: Nanning IDs are 10 digits, Guilin IDs are 13
        val digits = username.filter { it.isDigit() }
        val autoNanning = digits.length == 10
        _uiState.value = _uiState.value.copy(username = username, isNanning = autoNanning)
    }
    fun updatePassword(password: String) { _uiState.value = _uiState.value.copy(password = password) }
    fun updateRememberPassword(remember: Boolean) { _uiState.value = _uiState.value.copy(rememberPassword = remember) }
    fun toggleNanning() { _uiState.value = _uiState.value.copy(isNanning = !_uiState.value.isNanning) }

    fun loginAndImport() {
        val state = _uiState.value
        if (state.username.isBlank() || state.password.isBlank()) {
            _uiState.value = state.copy(message = "请输入学号和密码")
            return
        }

        // Nanning requires captcha → use WebView-based login instead of direct HTTP
        if (state.isNanning) {
            _uiState.value = _uiState.value.copy(showNanningWebView = true, message = "")
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
                        performImport(result.cookie, result.campusBaseUrl)
                    }
                    AcademicLoginResult.MissingCredentials ->
                        _uiState.value = _uiState.value.copy(isLoggingIn = false, message = "请输入学号和密码")
                    AcademicLoginResult.InvalidCredentials -> {
                        _uiState.value = _uiState.value.copy(isLoggingIn = false, message = "学号或密码错误，请重试")
                    }
                    AcademicLoginResult.CaptchaOrInteractiveLoginRequired -> {
                        _uiState.value = _uiState.value.copy(message = "正在尝试统一身份认证登录...")
                        val oaResult = oaLoginClient.login(state.username, state.password)
                        when (oaResult) {
                            is AcademicLoginResult.Success -> {
                                if (state.rememberPassword) {
                                    credentialStore.saveCredentials(state.username, state.password)
                                }
                                sessionStore.saveCookie(oaResult.cookie)
                                _uiState.value = _uiState.value.copy(message = "OA登录成功，正在导入数据...")
                                performImport(oaResult.cookie, oaResult.campusBaseUrl)
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

    /** Called when Nanning WebView login succeeds (user entered captcha manually). */
    fun onNanningWebViewLoginSuccess(cookie: String) {
        val state = _uiState.value
        _uiState.value = _uiState.value.copy(showNanningWebView = false)
        if (state.rememberPassword) {
            credentialStore.saveCredentials(state.username, state.password)
        }
        viewModelScope.launch {
            sessionStore.saveCookie(cookie)
            _uiState.value = _uiState.value.copy(isLoggingIn = true, message = "登录成功，正在导入数据...")
            performImport(cookie, AcademicLoginResult.NANNING_URL)
        }
    }

    /** Called when user goes back from Nanning WebView without importing. */
    fun onNanningWebViewBack() {
        _uiState.value = _uiState.value.copy(showNanningWebView = false)
    }

    private suspend fun performImport(cookie: String, campusBaseUrl: String = AcademicLoginResult.DEFAULT_GUILIN_URL) {
        var courseCount = 0
        var examCount = 0
        var scoreCount = 0

        try {
            val results = apiProbeService.probeAllEndpoints(cookie = cookie, baseUrl = campusBaseUrl)
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

            scoreCount = fetchAndSaveScores(cookie, campusBaseUrl)

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

    private suspend fun fetchAndSaveScores(cookie: String, campusBaseUrl: String = AcademicLoginResult.DEFAULT_GUILIN_URL): Int {
        val scoreClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        try {
            // Single GET with empty params returns ALL scores across all semesters
            // (optimized from 8 POST requests down to 1)
            val request = Request.Builder()
                .url("$campusBaseUrl/academic/manager/score/studentOwnScore.do?year=&term=&para=0")
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
            val scores = scoreParser.parseScoreHtml(body, isNanning = campusBaseUrl == AcademicLoginResult.NANNING_URL)
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
