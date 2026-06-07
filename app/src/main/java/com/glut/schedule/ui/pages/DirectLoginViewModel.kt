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
import com.glut.schedule.service.academic.CapturingCookieJar
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
    private var nanningCaptchaBytes: ByteArray? = null
    // Each Nanning login attempt gets a fresh CookieJar → fresh session
    private var nanningCookieJar: CapturingCookieJar? = null

    // CookieJar-based client: cookies auto-persist across requests like a browser
    private fun nanningHttpClient(cookieJar: CapturingCookieJar): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(cookieJar)
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
                // Create fresh CookieJar for this login session (like a new browser tab)
                val cj = CapturingCookieJar()
                nanningCookieJar = cj
                val client = nanningHttpClient(cj)

                // Step 1: Fetch login page → CookieJar auto-saves JSESSIONID
                val pageOk = withContext(Dispatchers.IO) {
                    runCatching {
                        client.newCall(Request.Builder()
                            .url("${AcademicLoginResult.NANNING_URL}/academic/common/security/affairLogin.jsp")
                            .header("User-Agent", UA).get().build()
                        ).execute().use { it.isSuccessful }
                    }.getOrDefault(false)
                }
                if (!pageOk) {
                    _uiState.value = _uiState.value.copy(isLoggingIn = false, message = "无法连接南宁分校教务系统")
                    return@launch
                }

                // Step 2: Download captcha → CookieJar auto-sends JSESSIONID
                val captchaBytes = withContext(Dispatchers.IO) {
                    runCatching {
                        client.newCall(Request.Builder()
                            .url("${AcademicLoginResult.NANNING_URL}/academic/getCaptcha.do?captchaCheckCode=0&random=${System.nanoTime()}")
                            .header("User-Agent", UA)
                            .header("Referer", "${AcademicLoginResult.NANNING_URL}/academic/common/security/affairLogin.jsp")
                            .get().build()
                        ).execute().use { it.body?.bytes() }
                    }.getOrNull()
                }
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
        val cj = nanningCookieJar ?: run {
            _uiState.value = state.copy(message = "会话已过期，请重新开始")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoggingIn = true, showCaptchaDialog = false, message = "正在登录...")
            try {
                val loginCookie = performNanningLogin(cj, state.username, state.password, captchaCode)
                if (loginCookie != null) {
                    onLoginSuccess(loginCookie, AcademicLoginResult.NANNING_URL, state.rememberPassword)
                } else {
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
        nanningCookieJar = null
    }

    /** Refresh captcha image (user clicked refresh button). */
    fun refreshNanningCaptcha() {
        val cj = nanningCookieJar ?: return
        viewModelScope.launch {
            try {
                val client = nanningHttpClient(cj)
                val bytes = withContext(Dispatchers.IO) {
                    runCatching {
                        client.newCall(Request.Builder()
                            .url("${AcademicLoginResult.NANNING_URL}/academic/getCaptcha.do?captchaCheckCode=0&random=${System.nanoTime()}")
                            .header("User-Agent", UA)
                            .header("Referer", "${AcademicLoginResult.NANNING_URL}/academic/common/security/affairLogin.jsp")
                            .get().build()
                        ).execute().use { it.body?.bytes() }
                    }.getOrNull()
                }
                if (bytes != null) {
                    nanningCaptchaBytes = bytes
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    _uiState.value = _uiState.value.copy(captchaBitmap = bitmap, captchaInput = "")
                }
            } catch (_: Exception) { }
        }
    }

    // ---- Nanning HTTP helpers (CookieJar-based, no manual cookie passing) ----

    private suspend fun performNanningLogin(
        cj: CapturingCookieJar,
        username: String,
        password: String,
        captcha: String
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val client = nanningHttpClient(cj)
            val base = AcademicLoginResult.NANNING_URL
            val referer = "$base/academic/common/security/affairLogin.jsp"

            // Step 1: Validate captcha (matches validCaptcha() → AJAX)
            val captchaOk = validateNanningCaptcha(client, captcha, base, referer)
            if (!captchaOk) return@runCatching null

            // Step 2: Hash password + build login URL
            val hashedPassword = NanningPasswordHash.hash(password)
            val loginUrl = "$base/academic/j_acegi_security_check" +
                "?j_username=${URLEncoder.encode(username, "UTF-8")}" +
                "&j_password=${URLEncoder.encode(hashedPassword, "UTF-8")}" +
                "&j_captcha=${URLEncoder.encode(captcha, "UTF-8")}"

            // Step 3: Execute login → CookieJar auto-captures new JSESSIONID from Set-Cookie
            client.newCall(Request.Builder()
                .url(loginUrl)
                .header("User-Agent", UA)
                .header("Referer", referer)
                .get().build()
            ).execute().use { response ->
                // CookieJar automatically saves any Set-Cookie from this response!
                val location = response.header("Location") ?: ""
                when {
                    location.contains("affairLogin", ignoreCase = true) ||
                        location.contains("error", ignoreCase = true) -> return@runCatching null
                    location.isNotBlank() -> followNanningRedirects(client, location, base)
                }
            }

            // Step 4: Verify by fetching a protected page
            verifyNanningLogin(client, base)?.let {
                cj.cookieHeader()
            }
        }.getOrNull()
    }

    private fun validateNanningCaptcha(
        client: OkHttpClient, captcha: String, base: String, referer: String
    ): Boolean {
        val encoded = URLEncoder.encode(captcha, "UTF-8")
        return client.newCall(Request.Builder()
            .url("$base/academic/checkCaptcha.do?captchaCode=$encoded")
            .header("User-Agent", UA)
            .header("Referer", referer)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Origin", base)
            .header("Accept", "text/plain, */*; q=0.01")
            .method("POST", okhttp3.RequestBody.create(null, ByteArray(0)))
            .build()
        ).execute().use { response ->
            response.body?.string().orEmpty().trim() == "true"
        }
    }

    /** Follow redirect chain; CookieJar auto-collects cookies from each hop. */
    private fun followNanningRedirects(client: OkHttpClient, startUrl: String, base: String) {
        var url = startUrl
        for (hop in 1..5) {
            val resolved = if (url.startsWith("http")) url else "$base$url"
            client.newCall(Request.Builder()
                .url(resolved)
                .header("User-Agent", UA)
                .get().build()
            ).execute().use { response ->
                val location = response.header("Location") ?: ""
                if (location.isBlank()) return
                url = location
            }
        }
    }

    private fun verifyNanningLogin(client: OkHttpClient, base: String): Boolean {
        return client.newCall(Request.Builder()
            .url("$base/academic/personal/framePage.do")
            .header("User-Agent", UA)
            .post(okhttp3.FormBody.Builder().build())
            .build()
        ).execute().use { response ->
            val location = response.header("Location") ?: ""
            listOf("framePage", "index_new", "showTimetable", "personal", "manager").any {
                location.contains(it, ignoreCase = true)
            } || response.isSuccessful
        }
    }

    // ---- Shared helpers ----

    private fun onLoginSuccess(cookie: String, campusBaseUrl: String, remember: Boolean) {
        if (remember) {
            credentialStore.saveCredentials(_uiState.value.username, _uiState.value.password)
        }
        viewModelScope.launch {
            sessionStore.saveCookie(cookie)
            sessionStore.saveCampusBaseUrl(campusBaseUrl)
            val campusType = if (campusBaseUrl == AcademicLoginResult.NANNING_URL) {
                com.glut.schedule.data.settings.CampusType.NANNING
            } else {
                com.glut.schedule.data.settings.CampusType.GUILIN
            }
            settingsStore.setCampusType(campusType)
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

            var htmlResult = apiProbeService.findTimetableHtmlResult(results)

            // Nanning: currcourse.jsdo is the primary schedule endpoint.
            // showTimetable.do requires yearid/termid extracted from currcourse.jsdo.
            if (campusBaseUrl == AcademicLoginResult.NANNING_URL) {
                // Priority 1: currcourse.jsdo (primary Nanning endpoint)
                val currcourse = results.find {
                    it.url.contains("currcourse.jsdo") && it.httpCode == 200
                }
                if (currcourse != null && currcourse.body.length > 1000) {
                    htmlResult = currcourse
                }

                // Priority 2: showTimetable.do with params from currcourse.jsdo
                if (htmlResult == null) {
                    val currcourseBody = results
                        .find { it.url.contains("currcourse.jsdo") }?.body ?: ""
                    val extractedId = ApiProbeService.extractInternalIdFromCurrcourse(currcourseBody)
                    val extractedYearId = ApiProbeService.extractYearIdFromCurrcourse(currcourseBody)
                    val extractedTermId = ApiProbeService.extractTermIdFromCurrcourse(currcourseBody)
                    if (extractedId != null) {
                        val showUrl = "$campusBaseUrl/academic/manager/coursearrange/showTimetable.do" +
                            "?id=$extractedId" +
                            "&yearid=${extractedYearId ?: "46"}" +
                            "&termid=${extractedTermId ?: "1"}" +
                            "&timetableType=STUDENT&sectionType=BASE"
                        htmlResult = apiProbeService.probeUrl(cookie, showUrl)
                    }
                }

                // Priority 3: Last resort with student ID + default yearid/termid
                if (htmlResult == null) {
                    val username = _uiState.value.username
                    if (username.isNotBlank()) {
                        val fallbackUrl = "$campusBaseUrl/academic/manager/coursearrange/showTimetable.do" +
                            "?id=$username&yearid=46&termid=1&timetableType=STUDENT&sectionType=BASE"
                        htmlResult = apiProbeService.probeUrl(cookie, fallbackUrl)
                    }
                }
            }

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
            // POST with form body — matches the HTML form method and all reference projects
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
                scoreClient.newCall(request).execute().use { response ->
                    val rawBytes = response.body?.bytes() ?: ByteArray(0)
                    val ct = response.header("Content-Type") ?: ""
                    Pair(rawBytes, ct)
                }
            }

            // GLUT academic system returns GBK/GB2312 encoded HTML
            val charset = try {
                if (contentType.contains("charset=", ignoreCase = true)) {
                    java.nio.charset.Charset.forName(
                        contentType.substringAfter("charset=").trim().removePrefix("\"").removeSuffix("\"")
                    )
                } else {
                    java.nio.charset.Charset.forName("GBK")
                }
            } catch (_: Exception) {
                java.nio.charset.Charset.forName("UTF-8")
            }
            val html = String(body, charset)

            val scores = scoreParser.parseScoreHtml(html, isNanning = campusBaseUrl == AcademicLoginResult.NANNING_URL)
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
