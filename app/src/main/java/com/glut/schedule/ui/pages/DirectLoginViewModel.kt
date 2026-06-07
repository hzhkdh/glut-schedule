package com.glut.schedule.ui.pages

import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glut.schedule.data.model.ScoreInfo
import com.glut.schedule.data.repository.ScheduleRepository
import com.glut.schedule.data.settings.ScheduleSettingsStore
import com.glut.schedule.service.academic.AcademicLoginHttpClient
import com.glut.schedule.service.academic.AcademicLoginResult
import com.glut.schedule.service.academic.AcademicLoginService
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
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class DirectLoginUiState(
    val username: String = "",
    val password: String = "",
    val rememberPassword: Boolean = true,
    val captchaBitmap: android.graphics.Bitmap? = null,
    val captchaText: String = "",
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

    private val captchaClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val loginHttpClient = AcademicLoginHttpClient()

    init {
        viewModelScope.launch {
            val savedUsername = credentialStore.getUsername()
            val savedPassword = credentialStore.getPassword()
            _uiState.value = _uiState.value.copy(
                username = savedUsername,
                password = savedPassword,
                rememberPassword = savedUsername.isNotBlank()
            )
            loadCaptcha()
        }
    }

    fun loadCaptcha() {
        viewModelScope.launch {
            try {
                val pageRequest = Request.Builder()
                    .url("http://jw.glut.edu.cn/academic/affairLogin.do")
                    .header("User-Agent", MOBILE_USER_AGENT)
                    .get()
                    .build()

                val sessionCookies = withContext(Dispatchers.IO) {
                    captchaClient.newCall(pageRequest).execute().use { response ->
                        response.headers("Set-Cookie").joinToString("; ")
                    }
                }

                val captchaRequest = Request.Builder()
                    .url("http://jw.glut.edu.cn/academic/getCaptcha.do")
                    .header("Cookie", sessionCookies)
                    .header("User-Agent", MOBILE_USER_AGENT)
                    .get()
                    .build()

                withContext(Dispatchers.IO) {
                    captchaClient.newCall(captchaRequest).execute().use { response ->
                        val bytes = response.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            _uiState.value = _uiState.value.copy(captchaBitmap = bitmap)
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(message = "验证码加载失败: ${e.message}")
            }
        }
    }

    fun updateUsername(username: String) { _uiState.value = _uiState.value.copy(username = username) }
    fun updatePassword(password: String) { _uiState.value = _uiState.value.copy(password = password) }
    fun updateCaptchaText(captchaText: String) { _uiState.value = _uiState.value.copy(captchaText = captchaText) }
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
                        loadCaptcha()
                    }
                    AcademicLoginResult.CaptchaOrInteractiveLoginRequired -> {
                        _uiState.value = _uiState.value.copy(isLoggingIn = false, message = "需要验证码验证")
                        loadCaptcha()
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
        val allScores = mutableListOf<ScoreInfo>()
        val years = listOf("2025-2026", "2024-2025", "2023-2024", "2022-2023")
        for (year in years) {
            for (term in 1..2) {
                try {
                    val formBody = FormBody.Builder()
                        .add("year", year).add("term", term.toString()).add("para", "0")
                        .build()
                    val request = Request.Builder()
                        .url("http://jw.glut.edu.cn/academic/manager/score/studentOwnScore.do")
                        .header("Cookie", cookie)
                        .header("User-Agent", MOBILE_USER_AGENT)
                        .post(formBody).build()
                    withContext(Dispatchers.IO) {
                        scoreClient.newCall(request).execute().use { response ->
                            val body = response.body?.string().orEmpty()
                            val scores = scoreParser.parseScoreHtml(body, year, term)
                            allScores.addAll(scores)
                        }
                    }
                } catch (_: Exception) { }
            }
        }
        if (allScores.isNotEmpty()) scheduleRepository.replaceScores(allScores)
        return allScores.size
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
