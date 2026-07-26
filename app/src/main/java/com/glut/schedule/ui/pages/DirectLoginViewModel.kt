package com.glut.schedule.ui.pages

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glut.schedule.data.repository.ScheduleRepository
import com.glut.schedule.data.model.AcademicSemester
import com.glut.schedule.data.model.SemesterCacheStatus
import com.glut.schedule.data.model.SemesterSeason
import com.glut.schedule.data.settings.ScheduleSettingsStore
import com.glut.schedule.service.academic.AcademicLoginHttpClient
import com.glut.schedule.service.academic.AcademicLoginResult
import com.glut.schedule.service.academic.AcademicLoginService
import com.glut.schedule.service.academic.AcademicOALoginClient
import com.glut.schedule.service.academic.AcademicSessionStore
import com.glut.schedule.service.academic.AcademicSemesterImportService
import com.glut.schedule.service.academic.AcademicSemesterCalendarEstimator
import com.glut.schedule.service.academic.AcademicSemesterCurrentImportPlanner
import com.glut.schedule.service.academic.AcademicSemesterProbePlanner
import com.glut.schedule.service.academic.AcademicSemesterViewPlanner
import com.glut.schedule.service.academic.ApiProbeService
import com.glut.schedule.service.academic.CapturingCookieJar
import com.glut.schedule.service.academic.CredentialStore
import com.glut.schedule.service.academic.NanningPasswordHash
import com.glut.schedule.service.parser.AcademicScheduleParser
import com.glut.schedule.service.network.MAX_BOOLEAN_RESPONSE_BYTES
import com.glut.schedule.service.network.MAX_HTML_RESPONSE_BYTES
import com.glut.schedule.service.network.MAX_IMAGE_RESPONSE_BYTES
import com.glut.schedule.service.network.readBytesLimited
import com.glut.schedule.service.network.readStringLimited
import com.glut.schedule.ui.SingleFlightGuard
import com.glut.schedule.service.parser.AcademicSemesterCatalogPlan
import com.glut.schedule.service.parser.AcademicSemesterParser
import com.glut.schedule.service.parser.GlutExamParser
import com.glut.schedule.service.parser.GradeExamParser
import com.glut.schedule.service.parser.ScoreParser
import com.glut.schedule.service.parser.StudyPlanParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.time.LocalDate
import java.util.concurrent.TimeUnit

data class DirectLoginUiState(
    val username: String = "",
    val password: String = "",
    val rememberPassword: Boolean = true,
    val secureCredentialStorageAvailable: Boolean = true,
    val isNanning: Boolean = false,
    val isLoggingIn: Boolean = false,
    // Nanning captcha flow
    val showCaptchaDialog: Boolean = false,
    val captchaBitmap: Bitmap? = null,
    val captchaInput: String = "",
    //
    val message: String = "",
    val importResult: ImportResult? = null,
    val semesters: List<AcademicSemester> = emptyList(),
    val viewedSemesterId: String = AcademicSemester.LEGACY_CURRENT_ID,
    val importingSemesterId: String? = null
)

data class ImportResult(
    val courseCount: Int,
    val examCount: Int,
    val scoreCount: Int,
    val gradeExamCount: Int = 0,
    val studyPlanCount: Int = 0
)

class DirectLoginViewModel(
    private val loginService: AcademicLoginService,
    private val sessionStore: AcademicSessionStore,
    private val credentialStore: CredentialStore,
    private val scheduleRepository: ScheduleRepository,
    private val settingsStore: ScheduleSettingsStore,
    private val apiProbeService: ApiProbeService,
    private val semesterImportService: AcademicSemesterImportService,
    private val scheduleParser: AcademicScheduleParser,
    private val examParser: GlutExamParser,
    private val scoreParser: ScoreParser,
    private val gradeExamParser: GradeExamParser = GradeExamParser(),
    private val studyPlanParser: StudyPlanParser = StudyPlanParser()
) : ViewModel() {
    private val loginGuard = SingleFlightGuard()

    private val _uiState = MutableStateFlow(
        DirectLoginUiState(
            rememberPassword = credentialStore.canPersistCredentials(),
            secureCredentialStorageAvailable = credentialStore.canPersistCredentials()
        )
    )
    val uiState: StateFlow<DirectLoginUiState> = _uiState

    private var loginHttpClient = AcademicLoginHttpClient()
    private val oaLoginClient = AcademicOALoginClient()
    private var credentialsCleared = false

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
            if (!credentialsCleared) {
                _uiState.value = _uiState.value.copy(
                    username = savedUsername,
                    password = savedPassword,
                    rememberPassword = effectiveRememberPassword(
                        requested = savedUsername.isNotBlank(),
                        secureStorageAvailable = credentialStore.canPersistCredentials()
                    )
                )
            }
        }
        viewModelScope.launch {
            combine(scheduleRepository.semesters, scheduleRepository.viewedSemesterId) { semesters, viewedId ->
                semesters to viewedId
            }.collect { (semesters, viewedId) ->
                _uiState.value = _uiState.value.copy(semesters = semesters, viewedSemesterId = viewedId)
            }
        }
    }

    fun updateUsername(username: String) {
        val digits = username.filter { it.isDigit() }
        val autoNanning = digits.length == 10
        _uiState.value = _uiState.value.copy(username = username, isNanning = autoNanning)
    }
    fun updatePassword(password: String) { _uiState.value = _uiState.value.copy(password = password) }
    fun updateRememberPassword(remember: Boolean) {
        _uiState.value = _uiState.value.copy(
            rememberPassword = effectiveRememberPassword(
                requested = remember,
                secureStorageAvailable = credentialStore.canPersistCredentials()
            )
        )
    }
    fun toggleNanning() { _uiState.value = _uiState.value.copy(isNanning = !_uiState.value.isNanning) }
    fun updateCaptchaInput(input: String) { _uiState.value = _uiState.value.copy(captchaInput = input) }

    fun downloadSemester(semesterId: String) {
        val semester = _uiState.value.semesters.firstOrNull { it.id == semesterId } ?: return
        if (semester.cacheStatus == SemesterCacheStatus.CACHED ||
            semester.cacheStatus == SemesterCacheStatus.DOWNLOADING) return
        if (_uiState.value.importingSemesterId != null) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(importingSemesterId = semesterId, message = "正在下载${semester.displayName}...")
            scheduleRepository.updateSemesterCacheStatus(semesterId, SemesterCacheStatus.DOWNLOADING)
            val cookie = sessionStore.academicCookie.first()
            val baseUrl = sessionStore.campusBaseUrl.first().ifBlank {
                if (semester.campus == com.glut.schedule.data.settings.CampusType.NANNING) {
                    AcademicLoginResult.NANNING_URL
                } else AcademicLoginResult.DEFAULT_GUILIN_URL
            }
            val authenticatedStudentNumber = sessionStore.authenticatedStudentNumber.first()
                .ifBlank { credentialStore.getUsername() }
            val result = if (cookie.isBlank()) {
                Result.failure(IllegalStateException("登录状态已过期，请重新登录"))
            } else {
                semesterImportService.importSemester(
                    cookie = cookie,
                    baseUrl = baseUrl,
                    semester = semester,
                    studentIdFallback = authenticatedStudentNumber,
                    useWeeklyTimetable = true,
                    onProgress = { completed, total ->
                        _uiState.value = _uiState.value.copy(
                            message = "正在下载${semester.displayName}（第${completed}/${total}周）..."
                        )
                    }
                )
            }
            result.onSuccess { payload ->
                scheduleRepository.replaceSemesterSchedule(
                    semester = semester,
                    courses = payload.courses,
                    adjustments = payload.adjustments,
                    portalMaxWeek = payload.portalMaxWeek
                )
                _uiState.value = _uiState.value.copy(
                    importingSemesterId = null,
                    message = "已缓存${semester.displayName}，历史学期为只读模式"
                )
            }.onFailure { error ->
                scheduleRepository.updateSemesterCacheStatus(semesterId, SemesterCacheStatus.FAILED)
                _uiState.value = _uiState.value.copy(
                    importingSemesterId = null,
                    message = "下载失败：${error.message ?: "请稍后重试"}"
                )
            }
        }
    }

    fun viewSemester(semesterId: String) {
        val semester = _uiState.value.semesters.firstOrNull { it.id == semesterId } ?: return
        if (!semester.isCurrent && semester.cacheStatus != SemesterCacheStatus.CACHED) return
        viewModelScope.launch {
            val week = AcademicSemesterViewPlanner.weekFor(
                semester = semester,
                today = LocalDate.now(),
                fallbackStart = settingsStore.semesterStartMonday.first(),
                fallbackEnd = settingsStore.semesterEndDate.first()
            )
            scheduleRepository.selectSemester(semesterId)
            settingsStore.setCurrentWeekNumber(week)
        }
    }

    fun returnToCurrentSemester() {
        viewModelScope.launch { scheduleRepository.resetViewedSemesterToCurrent() }
    }

    fun clearLoginState() {
        credentialsCleared = true
        loginHttpClient = AcademicLoginHttpClient()
        nanningCaptchaBytes = null
        nanningCookieJar = null
        _uiState.value = DirectLoginUiState(
            rememberPassword = false,
            secureCredentialStorageAvailable = credentialStore.canPersistCredentials()
        )
    }

    fun loginAndImport() {
        val state = _uiState.value
        if (state.username.isBlank() || state.password.isBlank()) {
            _uiState.value = state.copy(message = "请输入学号和密码")
            return
        }
        if (!loginGuard.tryStart()) return
        _uiState.value = state.copy(isLoggingIn = true, message = "正在登录...")

        // Create fresh login client to clear any cookies from previous sessions.
        // Without this, the CapturingCookieJar from a prior login can cause the
        // new login to fail with "CaptchaOrInteractiveLoginRequired".
        loginHttpClient = AcademicLoginHttpClient()

        if (state.isNanning) {
            startNanningLoginFlow(guardAlreadyStarted = true)
            return
        }

        // Guilin: direct HTTP login
        viewModelScope.launch {
            try {
                val result = loginHttpClient.login(state.username, state.password)
                when (result) {
                    is AcademicLoginResult.Success -> onLoginSuccess(result.cookie, result.campusBaseUrl, state.rememberPassword, state.username)
                    AcademicLoginResult.MissingCredentials ->
                        _uiState.value = _uiState.value.copy(isLoggingIn = false, message = "请输入学号和密码")
                    AcademicLoginResult.InvalidCredentials ->
                        _uiState.value = _uiState.value.copy(isLoggingIn = false, message = "学号或密码错误，请重试")
                    AcademicLoginResult.CaptchaOrInteractiveLoginRequired -> {
                        _uiState.value = _uiState.value.copy(message = "正在尝试统一身份认证登录...")
                        val oaResult = oaLoginClient.login(state.username, state.password)
                        when (oaResult) {
                            is AcademicLoginResult.Success -> onLoginSuccess(oaResult.cookie, oaResult.campusBaseUrl, state.rememberPassword, state.username)
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
            } finally {
                loginGuard.finish()
            }
        }
    }

    // ---- Nanning native captcha flow ----

    private fun startNanningLoginFlow(guardAlreadyStarted: Boolean = false) {
        if (!guardAlreadyStarted && !loginGuard.tryStart()) return
        _uiState.value = _uiState.value.copy(isLoggingIn = true, message = "正在获取验证码...")
        viewModelScope.launch {
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
                        ).execute().use { it.body?.readBytesLimited(MAX_IMAGE_RESPONSE_BYTES) }
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
            } finally {
                loginGuard.finish()
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
        if (!loginGuard.tryStart()) return
        _uiState.value = _uiState.value.copy(isLoggingIn = true, showCaptchaDialog = false, message = "正在登录...")

        viewModelScope.launch {
            try {
                val loginCookie = performNanningLogin(cj, state.username, state.password, captchaCode)
                if (loginCookie != null) {
                    onLoginSuccess(loginCookie, AcademicLoginResult.NANNING_URL, state.rememberPassword, state.username)
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
            } finally {
                loginGuard.finish()
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
                        ).execute().use { it.body?.readBytesLimited(MAX_IMAGE_RESPONSE_BYTES) }
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
            .method("POST", ByteArray(0).toRequestBody(null))
            .build()
        ).execute().use { response ->
            response.body?.readStringLimited(MAX_BOOLEAN_RESPONSE_BYTES).orEmpty().trim() == "true"
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
            val body = response.body?.readStringLimited(MAX_HTML_RESPONSE_BYTES).orEmpty()
            isAuthenticatedNanningResponse(response.code, location, body)
        }
    }

    // ---- Shared helpers ----

    private suspend fun onLoginSuccess(cookie: String, campusBaseUrl: String, remember: Boolean, studentNumber: String) {
        val username = _uiState.value.username
        val password = _uiState.value.password
        val shouldRemember = effectiveRememberPassword(remember, credentialStore.canPersistCredentials())
        val previousStudent = sessionStore.authenticatedStudentNumber.first()
        if (shouldClearAcademicData(previousStudent, studentNumber)) {
            // 账号变化时先清空旧教务缓存，避免新账号短暂看到上一位学生的数据。
            scheduleRepository.clearAllData()
            sessionStore.clearAll()
        }
        if (shouldRemember) {
            credentialStore.saveCredentials(username, password)
        } else {
            credentialStore.clearCredentials()
        }
        sessionStore.saveCookie(cookie)
        sessionStore.saveCampusBaseUrl(campusBaseUrl)
        sessionStore.saveAuthenticatedStudentNumber(studentNumber)
        val campusType = if (campusBaseUrl == AcademicLoginResult.NANNING_URL) {
            com.glut.schedule.data.settings.CampusType.NANNING
        } else {
            com.glut.schedule.data.settings.CampusType.GUILIN
        }
        settingsStore.setCampusType(campusType)
        _uiState.value = _uiState.value.copy(message = "登录成功，正在导入数据...")
        performImport(cookie, campusBaseUrl, studentNumber)
    }

    private suspend fun performImport(cookie: String, campusBaseUrl: String, studentNumber: String) {
        var courseCount = 0
        var examCount = 0
        var scoreCount = 0
        var gradeExamCount = 0
        var studyPlanCount = 0
        val failedModules = linkedSetOf<String>()

        try {
            val results = apiProbeService.probeAllEndpoints(cookie = cookie, baseUrl = campusBaseUrl)
            val campus = if (campusBaseUrl == AcademicLoginResult.NANNING_URL) {
                com.glut.schedule.data.settings.CampusType.NANNING
            } else {
                com.glut.schedule.data.settings.CampusType.GUILIN
            }
            val calendar = ApiProbeService.extractAcademicCalendar(results)

            val catalogHtml = results.firstOrNull {
                it.url.contains("currcourse.jsdo") && it.httpCode in 200..299
            }?.body.orEmpty()
            val enrollmentHtml = apiProbeService.probeUrl(
                cookie,
                "$campusBaseUrl/academic/student/studentinfo/studentInfoModifyIndex.do?frombase=0&wantTag=0"
            )?.body.orEmpty()
            val studentName = AcademicSemesterParser.parseStudentName(enrollmentHtml)
            sessionStore.saveAuthenticatedStudent(studentNumber, studentName)
            val enrollmentDate = AcademicSemesterParser.parseEnrollment(
                html = enrollmentHtml,
                studentNumber = studentNumber
            )?.catalogStartDate

            var catalogPlan = if (catalogHtml.isNotBlank() && enrollmentDate != null) {
                AcademicSemesterParser.parseCatalogPlan(
                    html = catalogHtml,
                    campus = campus,
                    enrollmentDate = enrollmentDate,
                    today = LocalDate.now()
                )
            } else AcademicSemesterCatalogPlan(emptyList(), null)
            if (catalogPlan.semesters.isEmpty()) {
                val portalYearId = ApiProbeService.extractYearIdFromCurrcourse(catalogHtml)
                    ?: (LocalDate.now().year - 1980).toString()
                val portalYear = portalYearId.toIntOrNull()?.plus(1980) ?: LocalDate.now().year
                val season = if (LocalDate.now().monthValue >= 8) SemesterSeason.AUTUMN else SemesterSeason.SPRING
                catalogPlan = AcademicSemesterCatalogPlan(
                    semesters = listOf(AcademicSemester.create(
                        campus = campus,
                        portalYear = portalYear,
                        portalYearId = portalYearId,
                        season = season,
                        portalTermId = ApiProbeService.extractTermIdFromCurrcourse(catalogHtml)
                            ?: if (season == SemesterSeason.SPRING) "1" else if (campus == com.glut.schedule.data.settings.CampusType.GUILIN) "2" else "3",
                        isCurrent = true
                    )),
                    nextSemester = null
                )
            }
            val nextProbeResult = catalogPlan.nextSemester?.let { nextSemester ->
                semesterImportService.importSemester(
                    cookie = cookie,
                    baseUrl = campusBaseUrl,
                    semester = nextSemester,
                    studentIdFallback = studentNumber,
                    useWeeklyTimetable = false
                )
            }
            val decision = AcademicSemesterProbePlanner.decide(catalogPlan, nextProbeResult)
            val semesterCatalog = decision.catalog
            val currentSemester = decision.currentSemester
            val promotedPayload = decision.promotedPayload
            val estimatedCalendar = if (promotedPayload != null) {
                AcademicSemesterCalendarEstimator.estimate(
                    currentSemester,
                    LocalDate.now()
                )
            } else null

            _uiState.value = _uiState.value.copy(message = "正在下载${currentSemester.displayName}周次课表...")
            val currentPayload = semesterImportService.importSemester(
                cookie = cookie,
                baseUrl = campusBaseUrl,
                semester = currentSemester,
                studentIdFallback = studentNumber,
                useWeeklyTimetable = true,
                onProgress = { completed, total ->
                    _uiState.value = _uiState.value.copy(
                        message = "正在下载${currentSemester.displayName}（第${completed}/${total}周）..."
                    )
                }
            ).getOrElse { error ->
                throw IllegalStateException(
                    "${currentSemester.displayName}周次课表导入失败：${error.message.orEmpty()}",
                    error
                )
            }
            sessionStore.saveHtmlPreview(currentPayload.currcourseHtml.take(3000))
            scheduleRepository.saveSemesterCatalog(semesterCatalog)
            settingsStore.setCurrentSemesterId(currentSemester.id)
            scheduleRepository.selectSemester(currentSemester.id)
            if (estimatedCalendar != null) {
                settingsStore.setSemesterStartMonday(estimatedCalendar.startMonday)
                settingsStore.setSemesterEndDate(estimatedCalendar.endDate)
                settingsStore.setCurrentWeekNumber(estimatedCalendar.currentWeekNumber)
            } else if (calendar != null) {
                settingsStore.setSemesterStartMonday(calendar.semesterStartMonday)
                calendar.semesterEndDate?.let { settingsStore.setSemesterEndDate(it) }
                settingsStore.setCurrentWeekNumber(calendar.currentWeekNumber)
            }
            scheduleRepository.replaceSemesterSchedule(
                semester = currentSemester,
                courses = currentPayload.courses,
                adjustments = currentPayload.adjustments,
                semesterStartDate = if (promotedPayload == null) calendar?.semesterStartMonday else null,
                semesterEndDate = if (promotedPayload == null) calendar?.semesterEndDate else null,
                portalMaxWeek = currentPayload.portalMaxWeek
            )
            courseCount = currentPayload.courses.size

            var examImported = false
            val examJsonResult = apiProbeService.findExamJsonResult(results)
            if (examJsonResult != null) {
                val exams = runCatching { examParser.parseExamJson(examJsonResult.body) }.getOrNull()
                if (exams != null) {
                    scheduleRepository.replaceExams(exams)
                    examCount = exams.size
                    examImported = true
                }
            }
            if (!examImported) {
                val examHtmlResult = apiProbeService.findExamHtmlResult(results)
                if (examHtmlResult != null) {
                    val exams = runCatching { examParser.parseExamHtml(examHtmlResult.body) }.getOrNull()
                    if (exams != null) {
                        scheduleRepository.replaceExams(exams)
                        examCount = exams.size
                        examImported = true
                    }
                }
            }
            if (!examImported) failedModules += "考试"

            fetchAndSaveScores(cookie, campusBaseUrl)
                .onSuccess { scoreCount = it }
                .onFailure { failedModules += "成绩" }

            // 等级考试只有在响应可识别且解析成功时才替换缓存。
            var gradeExamImported = false
            val gradeExamResult = apiProbeService.findGradeExamResult(results)
            if (gradeExamResult != null) {
                val gradeExams = runCatching {
                    gradeExamParser.parse(gradeExamResult.body)
                }.getOrNull()
                if (gradeExams != null) {
                    scheduleRepository.replaceGradeExams(gradeExams)
                    gradeExamCount = gradeExams.size
                    gradeExamImported = true
                }
            }
            if (!gradeExamImported) failedModules += "等级考试"

            var studyPlanImported = false
            try {
                // Step 1: Use probed studentSelfSchedule.jsdo result (like exams/grade exams use probe results)
                val selfResult = results.find {
                    it.url.contains("studentSelfSchedule.jsdo") && it.httpCode == 200 && it.body.length > 500
                }
                val parsedIds = if (selfResult != null) {
                    studyPlanParser.parseStudentIds(selfResult.body)
                } else {
                    null
                }
                if (parsedIds != null) {
                    val (studentId, classId) = parsedIds
                    // Step 2: Fetch study plan via probeUrl (uses same reliable client as probing)
                    val planUrl = "$campusBaseUrl/academic/manager/studyschedule/studentScheduleLineShow.do?z=z&studentId=$studentId&classId=$classId"
                    val planResult = apiProbeService.probeUrl(cookie, planUrl)
                    if (planResult != null && planResult.httpCode == 200 && planResult.body.length > 500) {
                        var (groups, courses) = studyPlanParser.parseData(planResult.body)
                        // Step 3: 框架模式 — 任选课组详情
                        val selfBody = selfResult?.body ?: ""
                        val frameStudentId = if (selfBody.isNotEmpty()) studyPlanParser.parseFrameStudentId(selfBody) else null
                        if (frameStudentId != null) {
                            val frameUrl = "$campusBaseUrl/academic/manager/studyschedule/studentScheduleShowFrame.do?z=z&studentId=$frameStudentId&classId=$classId"
                            val frameResult = apiProbeService.probeUrl(cookie, frameUrl)
                            if (frameResult != null && frameResult.httpCode == 200 && frameResult.body.length > 500) {
                                val freeGroupIds = studyPlanParser.extractFreeGroupIds(frameResult.body)
                                if (freeGroupIds.isNotEmpty()) {
                                    val mg = groups.toMutableList()
                                    val mc = courses.toMutableList()
                                    for ((gid, gname) in freeGroupIds) {
                                        val gUrl = "$campusBaseUrl/academic/manager/studyschedule/scheduleFreeGroupCourseList.do?pojoTypeId=2&id=$gid"
                                        val gResult = apiProbeService.probeUrl(cookie, gUrl)
                                        if (gResult != null && gResult.httpCode == 200) {
                                            val (fg, fcs) = studyPlanParser.parseFreeGroupDetail(gResult.body)
                                            if (fg != null) {
                                                val idx = mg.indexOfFirst { it.groupName == fg.groupName }
                                                if (idx >= 0) mg[idx] = fg else mg.add(fg)
                                                mc.addAll(fcs)
                                            }
                                        }
                                    }
                                    groups = mg.distinctBy { it.id }
                                    courses = mc.distinctBy { it.id }
                                }
                            }
                        }
                        scheduleRepository.replaceStudyPlanData(groups, courses)
                        studyPlanCount = groups.size
                        studyPlanImported = true
                    }
                }
            } catch (_: Exception) {
                // 单个模块失败不应阻断课表导入，但必须反馈且保留原缓存。
            }
            if (!studyPlanImported) failedModules += "教学计划"

            _uiState.value = _uiState.value.copy(
                isLoggingIn = false,
                message = importCompletionMessage(failedModules),
                importResult = ImportResult(courseCount, examCount, scoreCount, gradeExamCount, studyPlanCount)
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                isLoggingIn = false,
                message = "部分导入失败: ${e.message}",
                importResult = ImportResult(courseCount, examCount, scoreCount, gradeExamCount, studyPlanCount)
            )
        }
    }

    private suspend fun fetchAndSaveScores(
        cookie: String,
        campusBaseUrl: String = AcademicLoginResult.DEFAULT_GUILIN_URL
    ): Result<Int> {
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
                    val rawBytes = response.body?.readBytesLimited(MAX_HTML_RESPONSE_BYTES)
                        ?: ByteArray(0)
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
            scheduleRepository.replaceScores(scores)
            return Result.success(scores.size)
        } catch (error: Exception) {
            return Result.failure(error)
        }
    }

    private companion object {
        private const val TAG = "DirectLoginVM"
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

/**
 * 只有系统加密存储可用时才允许记住教务密码，避免任何明文降级路径。
 */
internal fun effectiveRememberPassword(
    requested: Boolean,
    secureStorageAvailable: Boolean
): Boolean = requested && secureStorageAvailable

/**
 * 首次登录没有旧数据可清；只有切换到不同的已知学号时才隔离并清理教务缓存。
 */
internal fun shouldClearAcademicData(previousStudent: String, newStudent: String): Boolean {
    val previous = previousStudent.trim()
    val incoming = newStudent.trim()
    return previous.isNotEmpty() && incoming.isNotEmpty() && previous != incoming
}

/**
 * 南宁受保护页面必须包含明确的认证标记，普通 2xx 或返回登录表单都不能算成功。
 */
internal fun isAuthenticatedNanningResponse(
    httpCode: Int,
    location: String,
    body: String
): Boolean {
    val redirectAuthenticated = listOf(
        "framePage",
        "index_new",
        "showTimetable",
        "/personal/",
        "/manager/"
    ).any { location.contains(it, ignoreCase = true) }
    if (redirectAuthenticated) return true
    if (httpCode !in 200..299) return false

    val loginMarkers = listOf("affairLogin", "j_username", "j_password", "验证码", "loginForm")
    if (loginMarkers.any { body.contains(it, ignoreCase = true) }) return false

    return listOf(
        "schoolCalendarStartDate",
        "currentTodayPlan.do",
        "moduleMenu.do",
        "preGotoAffairFrame"
    ).any { body.contains(it, ignoreCase = true) }
}

internal fun importCompletionMessage(failedModules: Collection<String>): String {
    val uniqueModules = failedModules.distinct()
    return if (uniqueModules.isEmpty()) {
        "导入完成"
    } else {
        "部分导入失败：${uniqueModules.joinToString("、")}；已保留原缓存"
    }
}

class DirectLoginViewModelFactory(
    private val loginService: AcademicLoginService,
    private val sessionStore: AcademicSessionStore,
    private val credentialStore: CredentialStore,
    private val scheduleRepository: ScheduleRepository,
    private val settingsStore: ScheduleSettingsStore,
    private val apiProbeService: ApiProbeService,
    private val semesterImportService: AcademicSemesterImportService,
    private val scheduleParser: AcademicScheduleParser,
    private val examParser: GlutExamParser,
    private val scoreParser: ScoreParser,
    private val gradeExamParser: GradeExamParser = GradeExamParser(),
    private val studyPlanParser: StudyPlanParser = StudyPlanParser()
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return DirectLoginViewModel(
            loginService, sessionStore, credentialStore,
            scheduleRepository, settingsStore, apiProbeService,
            semesterImportService, scheduleParser, examParser, scoreParser, gradeExamParser, studyPlanParser
        ) as T
    }
}
