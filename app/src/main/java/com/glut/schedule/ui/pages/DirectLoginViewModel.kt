package com.glut.schedule.ui.pages

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glut.schedule.data.repository.ScheduleRepository
import com.glut.schedule.data.settings.ScheduleSettingsStore
import com.glut.schedule.service.academic.AcademicLoginHttpClient
import com.glut.schedule.service.academic.AcademicLoginResult
import com.glut.schedule.service.academic.AcademicLoginService
import com.glut.schedule.service.academic.AcademicOALoginClient
import com.glut.schedule.service.academic.AcademicSessionStore
import com.glut.schedule.service.academic.ApiProbeService
import com.glut.schedule.service.academic.CredentialStore
import com.glut.schedule.service.academic.NanningPasswordHash
import com.glut.schedule.service.parser.AcademicScheduleParser
import com.glut.schedule.service.parser.GlutExamParser
import com.glut.schedule.service.parser.ScoreParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class DirectLoginUiState(
    val username: String = "",
    val password: String = "",
    val rememberPassword: Boolean = true,
    val isNanning: Boolean = false,
    val isLoggingIn: Boolean = false,
    // Nanning captcha flow
    val showCaptchaDialog: Boolean = false,
    val captchaBitmap: Bitmap? = null,
    val captchaInput: String = "",
    //
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

    // Nanning login session state (lives outside uiState to avoid data class issues)
    private var nanningSessionCookie: String = ""
    private var nanningCaptchaBytes: ByteArray? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

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
        val digits = username.filter { it.isDigit() }
        val autoNanning = digits.length == 10
        _uiState.value = _uiState.value.copy(username = username, isNanning = autoNanning)
    }
    fun updatePassword(password: String) { _uiState.value = _uiState.value.copy(password = password) }
    fun updateRememberPassword(remember: Boolean) { _uiState.value = _uiState.value.copy(rememberPassword = remember) }
    fun toggleNanning() { _uiState.value = _uiState.value.copy(isNanning = !_uiState.value.isNanning) }
    fun updateCaptchaInput(input: String) { _uiState.value = _uiState.value.copy(captchaInput = input) }

    fun loginAndImport() {
        val state = _uiState.value
        if (state.username.isBlank() || state.password.isBlank()) {
            _uiState.value = state.copy(message = "请输入学号和密码")
            return
        }

        if (state.isNanning) {
            startNanningLoginFlow()
            return
        }

        // Guilin: direct HTTP login
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoggingIn = true, message = "正在登录...")
            try {
                val result = loginHttpClient.login(state.username, state.password)
                when (result) {
                    is AcademicLoginResult.Success -> onLoginSuccess(result.cookie, result.campusBaseUrl, state.rememberPassword)
                    AcademicLoginResult.MissingCredentials ->
                        _uiState.value = _uiState.value.copy(isLoggingIn = false, message = "请输入学号和密码")
                    AcademicLoginResult.InvalidCredentials ->
                        _uiState.value = _uiState.value.copy(isLoggingIn = false, message = "学号或密码错误，请重试")
                    AcademicLoginResult.CaptchaOrInteractiveLoginRequired -> {
                        _uiState.value = _uiState.value.copy(message = "正在尝试统一身份认证登录...")
                        val oaResult = oaLoginClient.login(state.username, state.password)
                        when (oaResult) {
                            is AcademicLoginResult.Success -> onLoginSuccess(oaResult.cookie, oaResult.campusBaseUrl, state.rememberPassword)
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

    // ---- Nanning native captcha flow ----

    private fun startNanningLoginFlow() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoggingIn = true, message = "正在获取验证码...")
            try {
                // Step 1: Fetch login page to get JSESSIONID
                val sessionCookie = fetchNanningLoginPage()
                if (sessionCookie.isBlank()) {
                    _uiState.value = _uiState.value.copy(isLoggingIn = false, message = "无法连接南宁分校教务系统")
                    return@launch
                }
                nanningSessionCookie = sessionCookie

                // Step 2: Download captcha image
                val captchaBytes = downloadNanningCaptcha(sessionCookie)
                if (captchaBytes == null) {
                    _uiState.value = _uiState.value.copy(isLoggingIn = false, message = "无法获取验证码图片")
                    return@launch
                }
                nanningCaptchaBytes = captchaBytes

                val bitmap = BitmapFactory.decodeByteArray(captchaBytes, 0, captchaBytes.size)
                _uiState.value = _uiState.value.copy(
                    isLoggingIn = false,
                    showCaptchaDialog = true,
                    captchaBitmap = bitmap,
                    captchaInput = "",
                    message = ""
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoggingIn = false, message = "获取验证码失败: ${e.message}")
            }
        }
    }

    /** User confirmed captcha input — attempt login. */
    fun submitNanningCaptcha() {
        val state = _uiState.value
        val captchaCode = state.captchaInput.trim()
        if (captchaCode.isBlank()) {
            _uiState.value = state.copy(message = "请输入验证码")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoggingIn = true, showCaptchaDialog = false, message = "正在登录...")
            try {
                val loginCookie = performNanningLogin(
                    username = state.username,
                    password = state.password,
                    captcha = captchaCode,
                    sessionCookie = nanningSessionCookie
                )
                if (loginCookie != null) {
                    onLoginSuccess(loginCookie, AcademicLoginResult.NANNING_URL, state.rememberPassword)
                } else {
                    // Captcha wrong or credentials wrong — refresh captcha
                    refreshNanningCaptcha()
                    _uiState.value = _uiState.value.copy(
                        isLoggingIn = false,
                        showCaptchaDialog = true,
                        captchaInput = "",
                        message = "验证码或密码错误，请重试"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoggingIn = false, message = "登录失败: ${e.message}")
            }
        }
    }

    fun cancelNanningCaptcha() {
        _uiState.value = _uiState.value.copy(showCaptchaDialog = false, captchaInput = "", isLoggingIn = false)
        nanningCaptchaBytes = null
        nanningSessionCookie = ""
    }

    /** Refresh captcha image (user clicked refresh button). */
    fun refreshNanningCaptcha() {
        viewModelScope.launch {
            try {
                val bytes = downloadNanningCaptcha(nanningSessionCookie)
                if (bytes != null) {
                    nanningCaptchaBytes = bytes
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    _uiState.value = _uiState.value.copy(captchaBitmap = bitmap, captchaInput = "")
                }
            } catch (_: Exception) { }
        }
    }

    // ---- Nanning HTTP helpers ----

    private suspend fun fetchNanningLoginPage(): String = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("${AcademicLoginResult.NANNING_URL}/academic/common/security/affairLogin.jsp")
                .header("User-Agent", UA)
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                extractCookie(response.headers("Set-Cookie"))
            }
        }.getOrDefault("")
    }

    private suspend fun downloadNanningCaptcha(sessionCookie: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("${AcademicLoginResult.NANNING_URL}/academic/getCaptcha.do?captchaCheckCode=0&random=${System.nanoTime()}")
                .header("Cookie", sessionCookie)
                .header("User-Agent", UA)
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                response.body?.bytes()
            }
        }.getOrNull()
    }

    /**
     * Execute Nanning login following the same flow as the page's JavaScript:
     * 1. Double-MD5 hash password (submit_hex_md5)
     * 2. GET j_acegi_security_check with hashed password + captcha
     * 3. Follow redirect to verify login
     * Returns the final JSESSIONID cookie on success, null on failure.
     */
    private suspend fun performNanningLogin(
        username: String,
        password: String,
        captcha: String,
        sessionCookie: String
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val hashedPassword = NanningPasswordHash.hash(password)
            val encodedUser = URLEncoder.encode(username, "UTF-8")
            val encodedPass = URLEncoder.encode(hashedPassword, "UTF-8")
            val encodedCaptcha = URLEncoder.encode(captcha, "UTF-8")

            // Build the same URL the page JS uses in check():
            // /academic/j_acegi_security_check?j_username=...&j_password=<MD5>&j_captcha=...
            val loginUrl = "${AcademicLoginResult.NANNING_URL}/academic/j_acegi_security_check" +
                "?j_username=$encodedUser&j_password=$encodedPass&j_captcha=$encodedCaptcha"

            val request = Request.Builder()
                .url(loginUrl)
                .header("Cookie", sessionCookie)
                .header("User-Agent", UA)
                .header("Referer", "${AcademicLoginResult.NANNING_URL}/academic/common/security/affairLogin.jsp")
                .get()
                .build()

            var currentCookie = sessionCookie
            httpClient.newCall(request).execute().use { response ->
                // Merge any new cookies
                val newCookies = extractCookie(response.headers("Set-Cookie"))
                if (newCookies.isNotBlank()) currentCookie = mergeCookies(currentCookie, newCookies)

                // Check for login failure indicators
                val location = response.header("Location") ?: ""

                when {
                    // Redirect to login page = credentials/captcha wrong
                    location.contains("affairLogin", ignoreCase = true) ||
                        location.contains("error", ignoreCase = true) -> return@runCatching null

                    // Got a redirect → follow it (the server redirects on success)
                    location.isNotBlank() -> {
                        currentCookie = followRedirects(location, currentCookie)
                    }
                }
            }

            // Verify by fetching a protected page
            currentCookie.takeIf { verifyNanningLogin(currentCookie) }
        }.getOrNull()
    }

    /** Follow the post-login redirect chain to get the final session cookie. */
    private fun followRedirects(startUrl: String, cookie: String): String {
        var currentUrl = startUrl
        var currentCookie = cookie
        for (hop in 1..5) {
            val resolvedUrl = if (currentUrl.startsWith("http")) currentUrl
            else "${AcademicLoginResult.NANNING_URL}$currentUrl"

            val request = Request.Builder()
                .url(resolvedUrl)
                .header("Cookie", currentCookie)
                .header("User-Agent", UA)
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                val newCookies = extractCookie(response.headers("Set-Cookie"))
                if (newCookies.isNotBlank()) currentCookie = mergeCookies(currentCookie, newCookies)

                val location = response.header("Location") ?: ""
                if (location.isBlank()) return currentCookie
                currentUrl = location
            }
        }
        return currentCookie
    }

    /** Verify login by fetching a page that requires authentication. */
    private fun verifyNanningLogin(cookie: String): Boolean {
        val successPatterns = listOf("framePage", "index_new", "showTimetable", "personal", "manager")
        val request = Request.Builder()
            .url("${AcademicLoginResult.NANNING_URL}/academic/personal/framePage.do")
            .header("Cookie", cookie)
            .header("User-Agent", UA)
            .post(FormBody.Builder().build())
            .build()

        return httpClient.newCall(request).execute().use { response ->
            val location = response.header("Location") ?: ""
            successPatterns.any { location.contains(it, ignoreCase = true) } || response.isSuccessful
        }
    }

    // ---- Shared helpers ----

    private fun onLoginSuccess(cookie: String, campusBaseUrl: String, remember: Boolean) {
        if (remember) {
            credentialStore.saveCredentials(_uiState.value.username, _uiState.value.password)
        }
        viewModelScope.launch {
            sessionStore.saveCookie(cookie)
            _uiState.value = _uiState.value.copy(message = "登录成功，正在导入数据...")
            performImport(cookie, campusBaseUrl)
        }
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
            val request = Request.Builder()
                .url("$campusBaseUrl/academic/manager/score/studentOwnScore.do?year=&term=&para=0")
                .header("Cookie", cookie)
                .header("User-Agent", UA)
                .get()
                .build()
            val body = withContext(Dispatchers.IO) {
                scoreClient.newCall(request).execute().use { response ->
                    response.body?.string().orEmpty()
                }
            }
            val scores = scoreParser.parseScoreHtml(body, isNanning = campusBaseUrl == AcademicLoginResult.NANNING_URL)
            if (scores.isNotEmpty()) scheduleRepository.replaceScores(scores)
            return scores.size
        } catch (_: Exception) {
            return 0
        }
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"

        fun extractCookie(setCookieHeaders: List<String>): String {
            return setCookieHeaders
                .mapNotNull { header ->
                    val nameValue = header.substringBefore(";").trim()
                    nameValue.takeIf {
                        it.startsWith("JSESSIONID=", ignoreCase = true) ||
                            it.startsWith("CASTGC=", ignoreCase = true) ||
                            it.startsWith("TGC=", ignoreCase = true)
                    }
                }
                .distinct()
                .joinToString("; ")
        }

        fun mergeCookies(existing: String, incoming: String): String {
            val map = linkedMapOf<String, String>()
            existing.split(";").map { it.trim() }.filter { it.contains("=") }.forEach {
                val parts = it.split("=", limit = 2)
                map[parts[0].trim()] = parts.getOrElse(1) { "" }.trim()
            }
            incoming.split(";").map { it.trim() }.filter { it.contains("=") }.forEach {
                val parts = it.split("=", limit = 2)
                map[parts[0].trim()] = parts.getOrElse(1) { "" }.trim()
            }
            return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
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
