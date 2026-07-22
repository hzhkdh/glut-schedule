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
import com.glut.schedule.service.academic.ApiProbeService
import com.glut.schedule.service.academic.CapturingCookieJar
import com.glut.schedule.service.academic.CredentialStore
import com.glut.schedule.service.academic.NanningPasswordHash
import com.glut.schedule.service.parser.AcademicScheduleParser
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
import java.net.URLEncoder
import java.time.LocalDate
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
    val importResult: ImportResult? = null,
    val semesters: List<AcademicSemester> = emptyList(),
    val viewedSemesterId: String = AcademicSemester.LEGACY_CURRENT_ID,
    val importingSemesterId: String? = null,
    val showEnrollmentDialog: Boolean = false,
    val enrollmentYearInput: String = "",
    val enrollmentSeason: SemesterSeason = SemesterSeason.AUTUMN
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

    private val _uiState = MutableStateFlow(DirectLoginUiState())
    val uiState: StateFlow<DirectLoginUiState> = _uiState

    private var loginHttpClient = AcademicLoginHttpClient()
    private val oaLoginClient = AcademicOALoginClient()
    private var credentialsCleared = false

    // Nanning login session state (lives outside uiState to avoid data class issues)
    private var nanningCaptchaBytes: ByteArray? = null
    // Each Nanning login attempt gets a fresh CookieJar → fresh session
    private var nanningCookieJar: CapturingCookieJar? = null
    private var pendingCatalogHtml: String = ""
    private var pendingCatalogCampus = com.glut.schedule.data.settings.CampusType.GUILIN

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
                    rememberPassword = savedUsername.isNotBlank()
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
    fun updateRememberPassword(remember: Boolean) { _uiState.value = _uiState.value.copy(rememberPassword = remember) }
    fun toggleNanning() { _uiState.value = _uiState.value.copy(isNanning = !_uiState.value.isNanning) }
    fun updateCaptchaInput(input: String) { _uiState.value = _uiState.value.copy(captchaInput = input) }
    fun updateEnrollmentYear(input: String) {
        _uiState.value = _uiState.value.copy(enrollmentYearInput = input.filter(Char::isDigit).take(4))
    }
    fun selectEnrollmentSeason(season: SemesterSeason) {
        _uiState.value = _uiState.value.copy(enrollmentSeason = season)
    }

    fun confirmEnrollmentStart() {
        val year = _uiState.value.enrollmentYearInput.toIntOrNull()
        if (year == null || year !in 2000..LocalDate.now().year) {
            _uiState.value = _uiState.value.copy(message = "请输入正确的入学年份")
            return
        }
        viewModelScope.launch {
            val season = _uiState.value.enrollmentSeason
            settingsStore.setConfirmedEnrollmentStart(year, season)
            rebuildPendingCatalog(year, season)
            _uiState.value = _uiState.value.copy(showEnrollmentDialog = false, message = "已保存入学学期")
        }
    }

    fun dismissEnrollmentDialog() {
        _uiState.value = _uiState.value.copy(showEnrollmentDialog = false)
    }

    fun selectOrImportSemester(semesterId: String) {
        val semester = _uiState.value.semesters.firstOrNull { it.id == semesterId } ?: return
        if (semester.cacheStatus == SemesterCacheStatus.CACHED) {
            scheduleRepository.selectSemester(semesterId)
            return
        }
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
            val result = if (cookie.isBlank()) {
                Result.failure(IllegalStateException("登录状态已过期，请重新登录"))
            } else {
                semesterImportService.importSemester(cookie, baseUrl, semester, _uiState.value.username)
            }
            result.onSuccess { payload ->
                scheduleRepository.replaceSemesterSchedule(semester, payload.courses, payload.adjustments)
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

    fun returnToCurrentSemester() {
        viewModelScope.launch { scheduleRepository.resetViewedSemesterToCurrent() }
    }

    fun clearLoginState() {
        credentialsCleared = true
        loginHttpClient = AcademicLoginHttpClient()
        nanningCaptchaBytes = null
        nanningCookieJar = null
        _uiState.value = DirectLoginUiState(rememberPassword = false)
    }

    fun loginAndImport() {
        val state = _uiState.value
        if (state.username.isBlank() || state.password.isBlank()) {
            _uiState.value = state.copy(message = "请输入学号和密码")
            return
        }

        // Create fresh login client to clear any cookies from previous sessions.
        // Without this, the CapturingCookieJar from a prior login can cause the
        // new login to fail with "CaptchaOrInteractiveLoginRequired".
        loginHttpClient = AcademicLoginHttpClient()

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
        } else {
            credentialStore.clearCredentials()
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
        var gradeExamCount = 0
        var studyPlanCount = 0

        try {
            val results = apiProbeService.probeAllEndpoints(cookie = cookie, baseUrl = campusBaseUrl)
            val campus = if (campusBaseUrl == AcademicLoginResult.NANNING_URL) {
                com.glut.schedule.data.settings.CampusType.NANNING
            } else {
                com.glut.schedule.data.settings.CampusType.GUILIN
            }
            val calendar = ApiProbeService.extractAcademicCalendar(results)
            if (calendar != null) {
                settingsStore.setSemesterStartMonday(calendar.semesterStartMonday)
                calendar.semesterEndDate?.let { settingsStore.setSemesterEndDate(it) }
                settingsStore.setCurrentWeekNumber(calendar.currentWeekNumber)
            }

            val catalogHtml = results.firstOrNull {
                it.url.contains("currcourse.jsdo") && it.httpCode in 200..299
            }?.body.orEmpty()
            pendingCatalogHtml = catalogHtml
            pendingCatalogCampus = campus
            val enrollmentHtml = apiProbeService.probeUrl(
                cookie,
                "$campusBaseUrl/academic/student/studentinfo/studentInfoModifyIndex.do?frombase=0&wantTag=0"
            )?.body.orEmpty()
            val authoritativeEnrollment = AcademicSemesterParser.parseEnrollment(enrollmentHtml)
                ?.takeIf { it.isConsistent }
            val confirmedEnrollment = settingsStore.confirmedEnrollmentStart.first()
            val enrollmentDate = authoritativeEnrollment?.entranceDate
                ?: authoritativeEnrollment?.enrollmentYear?.let { LocalDate.of(it, 9, 1) }
                ?: confirmedEnrollment?.let { (year, season) -> enrollmentDate(year, season) }

            var semesterCatalog = if (catalogHtml.isNotBlank()) {
                AcademicSemesterParser.parseCatalog(
                    html = catalogHtml,
                    campus = campus,
                    enrollmentDate = enrollmentDate ?: LocalDate.now(),
                    today = LocalDate.now()
                )
            } else emptyList()
            if (semesterCatalog.isEmpty()) {
                val portalYearId = ApiProbeService.extractYearIdFromCurrcourse(catalogHtml)
                    ?: (LocalDate.now().year - 1980).toString()
                val portalYear = portalYearId.toIntOrNull()?.plus(1980) ?: LocalDate.now().year
                val season = if (LocalDate.now().monthValue >= 8) SemesterSeason.AUTUMN else SemesterSeason.SPRING
                semesterCatalog = listOf(AcademicSemester.create(
                    campus = campus,
                    portalYear = portalYear,
                    portalYearId = portalYearId,
                    season = season,
                    portalTermId = ApiProbeService.extractTermIdFromCurrcourse(catalogHtml)
                        ?: if (season == SemesterSeason.SPRING) "1" else if (campus == com.glut.schedule.data.settings.CampusType.GUILIN) "2" else "3",
                    isCurrent = true
                ))
            }
            scheduleRepository.saveSemesterCatalog(semesterCatalog)
            val currentSemester = semesterCatalog.firstOrNull { it.isCurrent } ?: semesterCatalog.first()
            settingsStore.setCurrentSemesterId(currentSemester.id)
            if (enrollmentDate == null) {
                _uiState.value = _uiState.value.copy(
                    showEnrollmentDialog = true,
                    enrollmentYearInput = LocalDate.now().year.toString(),
                    enrollmentSeason = SemesterSeason.AUTUMN
                )
            }

            var htmlResult = apiProbeService.findTimetableHtmlResult(results)

            // Nanning: currcourse.jsdo is the primary schedule endpoint.
            // showTimetable.do has the adjustment table but needs yearid/termid from currcourse.
            var nanningShowResult: ApiProbeService.ProbeResult? = null
            if (campusBaseUrl == AcademicLoginResult.NANNING_URL) {
                // Always attempt to probe showTimetable.do for adjustments
                val currcourseBody = results
                    .find { it.url.contains("currcourse.jsdo") }?.body ?: ""
                val extractedId = ApiProbeService.extractInternalIdFromCurrcourse(currcourseBody)
                val extractedYearId = ApiProbeService.extractYearIdFromCurrcourse(currcourseBody)
                val extractedTermId = ApiProbeService.extractTermIdFromCurrcourse(currcourseBody)
                val id = extractedId ?: _uiState.value.username
                if (id.isNotBlank()) {
                    val showUrl = "$campusBaseUrl/academic/manager/coursearrange/showTimetable.do" +
                        "?id=$id" +
                        "&yearid=${extractedYearId ?: "46"}" +
                        "&termid=${extractedTermId ?: "1"}" +
                        "&timetableType=STUDENT&sectionType=BASE"
                    nanningShowResult = apiProbeService.probeUrl(cookie, showUrl)
                }

                // Priority 1: currcourse.jsdo (primary Nanning course source)
                val currcourse = results.find {
                    it.url.contains("currcourse.jsdo") && it.httpCode == 200
                }
                if (currcourse != null && currcourse.body.length > 1000) {
                    htmlResult = currcourse
                }

                // Priority 2: fall back to showTimetable.do for courses as well
                if (htmlResult == null) {
                    htmlResult = nanningShowResult
                }
            }

            if (htmlResult != null) {
                sessionStore.saveHtmlPreview(htmlResult.body.take(3000))
                var courses = runCatching {
                    scheduleParser.parsePersonalSchedule(htmlResult.body)
                }.getOrDefault(emptyList())
                // Save showTimetable.do URL for adjustments (Nanning) or the course source URL (Guilin)
                val timetableUrl = nanningShowResult?.url ?: htmlResult.url
                sessionStore.saveTimetableUrl(timetableUrl)
                val adjustmentHtml = nanningShowResult?.body ?: htmlResult.body
                val adjustments = scheduleParser.parseAdjustments(adjustmentHtml)
                Log.d(TAG, "Parsed ${adjustments.size} semester adjustments from timetable HTML")
                // Nanning: courses come from currcourse.jsdo which does NOT apply adjustments
                // internally—apply them now from the separately-fetched showTimetable.do
                if (campusBaseUrl == AcademicLoginResult.NANNING_URL && adjustmentHtml.isNotBlank()) {
                    courses = scheduleParser.applyAdjustmentsToCourses(courses, adjustmentHtml)
                    Log.d(TAG, "Applied adjustments to Nanning courses, result: ${courses.size} courses")
                }
                scheduleRepository.replaceSemesterSchedule(
                    semester = currentSemester,
                    courses = courses,
                    adjustments = adjustments,
                    semesterStartDate = calendar?.semesterStartMonday,
                    semesterEndDate = calendar?.semesterEndDate
                )
                courseCount = courses.size
            }

            val examJsonResult = apiProbeService.findExamJsonResult(results)
            if (examJsonResult != null) {
                val exams = runCatching { examParser.parseExamJson(examJsonResult.body) }.getOrDefault(emptyList())
                scheduleRepository.replaceExams(exams)
                examCount = exams.size
            }
            if (examCount == 0) {
                val examHtmlResult = apiProbeService.findExamHtmlResult(results)
                if (examHtmlResult != null) {
                    val exams = runCatching { examParser.parseExamHtml(examHtmlResult.body) }.getOrDefault(emptyList())
                    scheduleRepository.replaceExams(exams)
                    examCount = exams.size
                }
            }

            scoreCount = fetchAndSaveScores(cookie, campusBaseUrl)

            // Import grade exams from probe result
            val gradeExamResult = apiProbeService.findGradeExamResult(results)
            if (gradeExamResult != null) {
                val gradeExams = runCatching {
                    gradeExamParser.parse(gradeExamResult.body)
                }.getOrDefault(emptyList())
                scheduleRepository.replaceGradeExams(gradeExams)
                gradeExamCount = gradeExams.size
            }

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
                    }
                }
            } catch (_: Exception) { }

            _uiState.value = _uiState.value.copy(
                isLoggingIn = false,
                message = "导入完成",
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

    private suspend fun rebuildPendingCatalog(year: Int, season: SemesterSeason) {
        if (pendingCatalogHtml.isBlank()) return
        val catalog = AcademicSemesterParser.parseCatalog(
            html = pendingCatalogHtml,
            campus = pendingCatalogCampus,
            enrollmentDate = enrollmentDate(year, season),
            today = LocalDate.now()
        )
        if (catalog.isNotEmpty()) scheduleRepository.saveSemesterCatalog(catalog)
    }

    private fun enrollmentDate(year: Int, season: SemesterSeason): LocalDate =
        if (season == SemesterSeason.AUTUMN) LocalDate.of(year, 9, 1) else LocalDate.of(year, 2, 1)

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
            scheduleRepository.replaceScores(scores)
            return scores.size
        } catch (_: Exception) {
            return 0
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
