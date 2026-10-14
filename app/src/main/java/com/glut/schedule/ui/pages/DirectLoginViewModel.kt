package com.glut.schedule.ui.pages

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glut.schedule.data.repository.ScheduleRepository
import com.glut.schedule.data.model.AcademicSemester
import com.glut.schedule.data.model.ExamInfo
import com.glut.schedule.data.model.SemesterCacheStatus
import com.glut.schedule.data.model.SemesterSeason
import com.glut.schedule.data.model.academicMaxWeekForCalendar
import com.glut.schedule.data.model.academicWeekForDate
import com.glut.schedule.data.model.countDistinctCourseTitles
import com.glut.schedule.data.model.defaultSemesterEndDate
import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.data.settings.ClassPeriodProfile
import com.glut.schedule.data.settings.ImportSource
import com.glut.schedule.data.settings.ScheduleSettingsStore
import com.glut.schedule.data.settings.classPeriodProfile
import com.glut.schedule.service.graduate.GraduateImportCoordinator
import com.glut.schedule.service.graduate.GraduateImportEffects
import com.glut.schedule.service.graduate.GraduateImportService
import com.glut.schedule.service.graduate.GraduateLoginSession
import com.glut.schedule.service.graduate.GraduateClient
import com.glut.schedule.service.academic.AcademicExamService
import com.glut.schedule.service.academic.AcademicLoginHttpClient
import com.glut.schedule.service.academic.AcademicLoginResult
import com.glut.schedule.service.academic.AcademicLoginService
import com.glut.schedule.service.academic.scorePageUnavailableReason
import com.glut.schedule.service.academic.AcademicOALoginClient
import com.glut.schedule.service.academic.AcademicSessionStore
import com.glut.schedule.service.academic.AcademicSemesterImportService
import com.glut.schedule.service.academic.SemesterBulkDownloadCoordinator
import com.glut.schedule.service.academic.SemesterDownloadStartResult
import com.glut.schedule.service.academic.AcademicSemesterCalendarResolver
import com.glut.schedule.service.academic.AcademicSemesterProbePlanner
import com.glut.schedule.service.academic.AcademicSemesterViewPlanner
import com.glut.schedule.service.academic.ApiProbeService
import com.glut.schedule.service.academic.CaptchaLoginSession
import com.glut.schedule.service.academic.CredentialStore
import com.glut.schedule.service.academic.hasLocalAcademicSession
import com.glut.schedule.service.academic.hasUsableAcademicCookie
import com.glut.schedule.service.academic.shouldClearStoredCredentialsForWechatLogin
import com.glut.schedule.service.parser.AcademicScheduleParser
import com.glut.schedule.service.network.MAX_HTML_RESPONSE_BYTES
import com.glut.schedule.service.network.readBytesLimited
import com.glut.schedule.service.holiday.AutoDayCopyCoordinator
import com.glut.schedule.service.holiday.MakeupTableClient
import com.glut.schedule.service.holiday.TimorHolidayClient
import com.glut.schedule.service.holiday.refreshMakeupTable
import com.glut.schedule.service.holiday.refreshMissingHolidayYears
import com.glut.schedule.service.network.readStringLimited
import com.glut.schedule.ui.SingleFlightGuard
import com.glut.schedule.service.parser.AcademicSemesterCatalogPlan
import com.glut.schedule.service.parser.AcademicSemesterParser
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
import java.time.LocalDate
import java.util.concurrent.TimeUnit

data class DirectLoginUiState(
    val username: String = "",
    val password: String = "",
    val rememberPassword: Boolean = true,
    val secureCredentialStorageAvailable: Boolean = true,
    /**
     * 导入来源下拉框当前选中的项。
     *
     * 与已生效的 [CampusType] 分开：这里只是「用户打算导入哪一个」，
     * 真正的数据源切换发生在导入成功之后，失败不回写。
     */
    val importSource: ImportSource = ImportSource.GUILIN,
    val isLoggingIn: Boolean = false,
    // 验证码流程（南宁与研究生共用同一个弹窗）
    val showCaptchaDialog: Boolean = false,
    val captchaBitmap: Bitmap? = null,
    val captchaInput: String = "",
    //
    val message: String = "",
    val importResult: ImportResult? = null,
    /**
     * 本次会话是否已经导入完成。
     *
     * 导入页的主按钮据此变身「查看课表」：此前成功后按钮只是复位成「登 录 并 导 入」，
     * 用户看不出下一步该干什么，就留在导入页反复点导入。
     *
     * 必须与「当前填写的账号」同生命周期：改学号/密码/来源时立刻复位，
     * 否则换个账号回来按钮仍写着「查看课表」，而屏幕上那份课表还是上一个账号的。
     */
    val importCompleted: Boolean = false,
    val semesters: List<AcademicSemester> = emptyList(),
    val viewedSemesterId: String = AcademicSemester.LEGACY_CURRENT_ID,
    val importingSemesterId: String? = null,
    /** 无法识别周次的课次数量，用于向用户如实反馈而不是让课程静默消失。 */
    val unparsedWeekTextCount: Int = 0,
    /** 研究生调课提示：只展示，绝不据此改动主课表。 */
    val adjustmentWarning: String = ""
) {
    /** 兼容旧判断：南宁来源。 */
    val isNanning: Boolean get() = importSource == ImportSource.NANNING

    val isGraduate: Boolean get() = importSource == ImportSource.GRADUATE
}

data class ImportResult(
    val courseCount: Int,
    val examCount: Int,
    val scoreCount: Int,
    val gradeExamCount: Int = 0,
    val studyPlanCount: Int = 0
)

internal data class InitialExamImportData(
    val successfulUrl: String,
    val exams: List<ExamInfo>
)

/**
 * 首次导入考试的持久化边界：空结果必须视为失败，避免覆盖用户已有的考试缓存。
 */
internal suspend fun importInitialExams(
    fetch: suspend () -> Result<InitialExamImportData>,
    replaceExams: suspend (List<ExamInfo>) -> Unit,
    saveSuccessfulUrl: suspend (String) -> Unit
): Result<Int> {
    return fetch().mapCatching { data ->
        check(data.exams.isNotEmpty()) { "未获取到考试安排" }
        replaceExams(data.exams)
        if (data.successfulUrl.isNotBlank()) {
            saveSuccessfulUrl(data.successfulUrl)
        }
        data.exams.size
    }
}

class DirectLoginViewModel(
    private val loginService: AcademicLoginService,
    private val sessionStore: AcademicSessionStore,
    private val credentialStore: CredentialStore,
    private val scheduleRepository: ScheduleRepository,
    private val settingsStore: ScheduleSettingsStore,
    private val apiProbeService: ApiProbeService,
    private val academicExamService: AcademicExamService,
    private val semesterImportService: AcademicSemesterImportService,
    private val semesterBulkDownloadCoordinator: SemesterBulkDownloadCoordinator,
    private val scoreParser: ScoreParser,
    private val gradeExamParser: GradeExamParser = GradeExamParser(),
    private val studyPlanParser: StudyPlanParser = StudyPlanParser(),
    private val autoDayCopyCoordinator: AutoDayCopyCoordinator,
    private val timorHolidayClient: TimorHolidayClient = TimorHolidayClient(),
    private val makeupTableClient: MakeupTableClient = MakeupTableClient(),
    /**
     * 南宁/研究生的两步验证码登录。
     *
     * 刻意**不复用**全局续期协调器里那一个：导入页的验证码弹窗与「菜单里原地续期」的弹窗
     * 可能同时存在，共用一个会话实例会让两边互相把对方的 CookieJar 清掉。
     */
    private val captchaLogin: CaptchaLoginSession = CaptchaLoginSession()
) : ViewModel() {
    private val loginGuard = SingleFlightGuard()

    private val _uiState = MutableStateFlow(
        DirectLoginUiState(
            rememberPassword = credentialStore.canPersistCredentials(),
            secureCredentialStorageAvailable = credentialStore.canPersistCredentials()
        )
    )
    val uiState: StateFlow<DirectLoginUiState> = _uiState
    val semesterDownloadState = semesterBulkDownloadCoordinator.state

    private var loginHttpClient = AcademicLoginHttpClient()
    private val oaLoginClient = AcademicOALoginClient()
    private var credentialsCleared = false
    /** 用户是否手动动过来源下拉框；动过就不再被「上次成功的来源」覆盖。 */
    private var sourceTouchedByUser = false

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
            // 首次进入默认桂林本部；之后恢复「上一次成功导入」的来源。
            val lastSource = settingsStore.lastImportSource.first()
            if (!sourceTouchedByUser) {
                _uiState.value = _uiState.value.copy(importSource = lastSource)
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
        // 不再按学号长度自动猜校区：来源已由下拉框显式选择，
        // 自动切换会在用户改学号时把刚选好的来源悄悄改回去。
        _uiState.value = _uiState.value.copy(username = username).afterLoginInputEdit()
    }

    fun updatePassword(password: String) {
        _uiState.value = _uiState.value.copy(password = password).afterLoginInputEdit()
    }
    fun updateRememberPassword(remember: Boolean) {
        _uiState.value = _uiState.value.copy(
            rememberPassword = effectiveRememberPassword(
                requested = remember,
                secureStorageAvailable = credentialStore.canPersistCredentials()
            )
        )
    }

    /** 切换导入来源；用户一旦手动选择，就不再用「上次成功的来源」覆盖它。 */
    fun updateImportSource(source: ImportSource) {
        sourceTouchedByUser = true
        _uiState.value = _uiState.value.copy(importSource = source, message = "", captchaInput = "")
            .afterLoginInputEdit()
    }

    /**
     * 用户点了导入页的「重新导入」。
     *
     * **会话还在就直接重导，不再让用户登录一次。** 这一条对微信登录尤其要紧：
     * 它没有可填的账号密码，若这里退回登录表单，用户就只能再扫一次码——
     * 而他想要的只是把数据重新拉一遍。
     *
     * 会话真的失效时才收回完成态、把用户送回登录流程，并如实说明原因。
     */
    fun restartImport() {
        viewModelScope.launch {
            val cookie = sessionStore.academicCookie.first()
            if (!hasUsableAcademicCookie(cookie)) {
                _uiState.value = _uiState.value.copy(
                    importCompleted = false,
                    message = "登录状态已过期，请重新登录"
                )
                return@launch
            }
            if (!loginGuard.tryStart()) return@launch
            try {
                val state = _uiState.value
                _uiState.value = state.copy(
                    isLoggingIn = true,
                    importCompleted = false,
                    message = "正在重新导入...",
                    importResult = null
                )
                onLoginSuccess(
                    cookie = cookie,
                    campusBaseUrl = sessionStore.campusBaseUrl.first(),
                    source = state.importSource,
                    remember = false,
                    // 学号已存在会话里；传空也不会出岔子，performImport 会解析兜底。
                    studentNumber = sessionStore.authenticatedStudentNumber.first(),
                    // 重新导入不该动凭据：用户没提供任何新密码。
                    updateCredentials = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoggingIn = false, message = "重新导入失败: ${e.message}")
            } finally {
                loginGuard.finish()
            }
        }
    }

    fun updateCaptchaInput(input: String) { _uiState.value = _uiState.value.copy(captchaInput = input) }

    /**
     * 共用验证码弹窗的提交入口：验证码与该弹窗里填的密码一起交上来。
     *
     * 弹窗自己不持有账号密码（它同时服务导入页与菜单里的原地续期），所以密码必须由它带进来。
     * 不能改成「界面先调两个 setter 再调 [submitCaptcha]」：那三步之间任何一次状态复位
     * 都会让提交用上界面以为已经写进去、实际却不是的值。
     */
    /**
     * 弹窗点了确认：把弹窗里三个输入框的值收进 state，再走既有的提交流程。
     *
     * 账号也要收：导入页虽然自己有账号框，但共用弹窗允许用户在弹窗里改，
     * 改了就该以弹窗里的为准，否则用户会看到自己改的账号没生效。
     */
    fun submitCaptcha(captcha: String, username: String, password: String) {
        _uiState.value = _uiState.value.copy(
            captchaInput = captcha,
            username = username,
            password = password
        )
        submitCaptcha()
    }

    fun downloadSemester(semesterId: String) {
        viewModelScope.launch {
            updateDownloadStartMessage(semesterBulkDownloadCoordinator.startSingle(semesterId))
        }
    }

    fun downloadAllSemesters() {
        viewModelScope.launch {
            updateDownloadStartMessage(semesterBulkDownloadCoordinator.startAll())
        }
    }

    fun retryFailedSemester(semesterId: String) {
        viewModelScope.launch {
            updateDownloadStartMessage(semesterBulkDownloadCoordinator.retryFailed(semesterId))
        }
    }

    private fun updateDownloadStartMessage(result: SemesterDownloadStartResult) {
        val message = when (result) {
            is SemesterDownloadStartResult.Started -> "下载任务已开始，可前往其他页面"
            SemesterDownloadStartResult.AlreadyRunning -> "已有学期下载任务正在进行"
            SemesterDownloadStartResult.NothingPending -> "历史学期均已缓存"
            SemesterDownloadStartResult.LoginRequired -> "登录状态已过期，请重新登录"
            SemesterDownloadStartResult.NotRetryable -> "该学期当前不可下载"
        }
        _uiState.value = _uiState.value.copy(message = message)
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
        captchaLogin.reset()
        sourceTouchedByUser = false
        _uiState.value = DirectLoginUiState(
            rememberPassword = false,
            secureCredentialStorageAvailable = credentialStore.canPersistCredentials()
        )
    }

    // ---- 微信登录（仅桂林本部） ----

    /**
     * 请求打开微信登录覆盖层，返回是否放行。
     *
     * 覆盖层的显隐由 MainActivity 掌握（它才是渲染方，且覆盖层画在 drawer 之外），
     * 这里只做守卫并给出拒绝理由。守卫放在这一层而不是只靠界面上的显示条件：
     * 界面条件随状态变化，这里才是权威——非桂林来源必须被挡住并说明原因，
     * 否则用户会对着一个南宁/研究生根本登不进去的页面发呆。
     */
    fun startWechatLogin(): Boolean {
        val state = _uiState.value
        if (state.importSource != ImportSource.GUILIN) {
            _uiState.value = state.copy(message = WECHAT_GUILIN_ONLY_MESSAGE)
            return false
        }
        if (state.isLoggingIn) return false
        _uiState.value = state.copy(message = "", importCompleted = false)
        return true
    }

    fun cancelWechatLogin() {
        _uiState.value = _uiState.value.copy(message = "")
    }

    fun onWechatLoginFailed(reason: String) {
        _uiState.value = _uiState.value.copy(message = reason)
    }

    /**
     * WebView 侧取到教务会话后的回传。
     *
     * 先校验再落库：CAS 登录成功**不等于**教务认了这个会话——中间还差 ticket 被教务消费那一步
     * （用户提前切走、页面被中断都会卡在这里）。此时若照常导入，用户收到的是一串
     * 「部分导入失败」，比直接说清楚「教务没建立会话」难懂得多。
     */
    fun onWechatLoginSucceeded(cookie: String, studentNumber: String? = null) {
        if (!hasLocalAcademicSession(cookie)) {
            _uiState.value = _uiState.value.copy(message = WECHAT_NO_ACADEMIC_SESSION_MESSAGE)
            return
        }
        if (!loginGuard.tryStart()) return
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    isLoggingIn = true,
                    message = "登录成功，正在导入数据...",
                    importCompleted = false
                )
                // 微信登录没有密码，绝不能借道 onLoginSuccess 里那套「存/清凭据」的逻辑：
                // uiState 里的账号密码是上一位用户留下的，写回去等于张冠李戴。
                onLoginSuccess(
                    cookie = cookie,
                    campusBaseUrl = AcademicLoginResult.DEFAULT_GUILIN_URL,
                    source = ImportSource.GUILIN,
                    remember = false,
                    // 学号来自 IAM 递给教务的跳转（权威）；拿不到才留空，由 performImport 去解析兜底。
                    studentNumber = studentNumber.orEmpty(),
                    updateCredentials = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoggingIn = false, message = "登录失败: ${e.message}")
            } finally {
                loginGuard.finish()
            }
        }
    }

    fun loginAndImport() {
        val state = _uiState.value
        if (state.username.isBlank() || state.password.isBlank()) {
            _uiState.value = state.copy(message = "请输入学号和密码")
            return
        }
        if (!loginGuard.tryStart()) return
        // 开始新一轮登录导入就不再是「已完成」：主按钮在登录过程中必须回到进度态，
        // 而不是残留着上一次的「查看课表」。
        _uiState.value = state.copy(isLoggingIn = true, message = "正在登录...", importCompleted = false)

        // 每次登录都换掉旧的教务客户端，避免上一次会话的 Cookie 影响这次登录。
        // 不这样做时，上一次的 CapturingCookieJar 会让新登录直接落到
        // "CaptchaOrInteractiveLoginRequired"。
        loginHttpClient = AcademicLoginHttpClient()

        when (state.importSource) {
            ImportSource.NANNING -> {
                startNanningLoginFlow(guardAlreadyStarted = true)
                return
            }
            ImportSource.GRADUATE -> {
                startGraduateLoginFlow(guardAlreadyStarted = true)
                return
            }
            ImportSource.GUILIN -> Unit
        }

        // Guilin: direct HTTP login
        viewModelScope.launch {
            try {
                val result = loginHttpClient.login(state.username, state.password)
                when (result) {
                    is AcademicLoginResult.Success -> onLoginSuccess(result.cookie, result.campusBaseUrl, ImportSource.GUILIN, state.rememberPassword, state.username)
                    AcademicLoginResult.MissingCredentials ->
                        _uiState.value = _uiState.value.copy(isLoggingIn = false, message = "请输入学号和密码")
                    AcademicLoginResult.InvalidCredentials ->
                        _uiState.value = _uiState.value.copy(isLoggingIn = false, message = "学号或密码错误，请重试")
                    AcademicLoginResult.CaptchaOrInteractiveLoginRequired -> {
                        _uiState.value = _uiState.value.copy(message = "正在尝试统一身份认证登录...")
                        val oaResult = oaLoginClient.login(state.username, state.password)
                        when (oaResult) {
                            is AcademicLoginResult.Success -> onLoginSuccess(oaResult.cookie, oaResult.campusBaseUrl, ImportSource.GUILIN, state.rememberPassword, state.username)
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
                showCaptchaOrError(captchaLogin.requestCaptcha(ImportSource.NANNING))
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoggingIn = false, message = "获取验证码失败: ${e.message}")
            } finally {
                loginGuard.finish()
            }
        }
    }

    /**
     * 把「取图」的结果反映到界面上。
     *
     * 取图本身在 [CaptchaLoginSession] 里，这里只负责把结果翻译成
     * 「弹窗（带图、清空输入）」或「一句失败原因」——界面状态不跨层。
     */
    private fun showCaptchaOrError(result: Result<Bitmap>) {
        result.fold(
            onSuccess = { bitmap ->
                _uiState.value = _uiState.value.copy(
                    isLoggingIn = false,
                    showCaptchaDialog = true,
                    captchaBitmap = bitmap,
                    captchaInput = "",
                    message = ""
                )
            },
            onFailure = { error ->
                _uiState.value = _uiState.value.copy(
                    isLoggingIn = false,
                    message = error.message?.takeIf { it.isNotBlank() } ?: "无法获取验证码图片"
                )
            }
        )
    }

    /** 用户确认验证码输入 —— 按当前来源分派到南宁或研究生登录。 */
    fun submitCaptcha() {
        val state = _uiState.value
        if (state.captchaInput.isBlank()) {
            _uiState.value = state.copy(message = "请输入验证码")
            return
        }
        if (state.importSource == ImportSource.GRADUATE) submitGraduateCaptcha() else submitNanningCaptcha()
    }

    private fun submitNanningCaptcha() {
        val state = _uiState.value
        if (!captchaLogin.hasSession()) {
            _uiState.value = state.copy(message = "会话已过期，请重新开始")
            return
        }
        if (!loginGuard.tryStart()) return
        _uiState.value = _uiState.value.copy(isLoggingIn = true, showCaptchaDialog = false, message = "正在登录...")

        viewModelScope.launch {
            try {
                captchaLogin.submit(
                    source = ImportSource.NANNING,
                    username = state.username,
                    password = state.password,
                    captcha = state.captchaInput.trim()
                ).fold(
                    onSuccess = { renewed ->
                        onLoginSuccess(
                            renewed.cookie,
                            renewed.campusBaseUrl,
                            ImportSource.NANNING,
                            state.rememberPassword,
                            state.username
                        )
                    },
                    onFailure = { error ->
                        refreshCaptcha()
                        _uiState.value = _uiState.value.copy(
                            isLoggingIn = false,
                            showCaptchaDialog = true,
                            captchaInput = "",
                            message = error.message?.takeIf { it.isNotBlank() }
                                ?: "验证码或密码错误，请重试"
                        )
                    }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoggingIn = false, message = "登录失败: ${e.message}")
            } finally {
                loginGuard.finish()
            }
        }
    }

    fun cancelCaptcha() {
        _uiState.value = _uiState.value.copy(showCaptchaDialog = false, captchaInput = "", isLoggingIn = false)
        captchaLogin.reset()
    }

    /** 刷新验证码图片（用户点了刷新按钮）。 */
    fun refreshCaptcha() {
        if (_uiState.value.importSource == ImportSource.GRADUATE) refreshGraduateCaptcha() else refreshNanningCaptcha()
    }

    fun refreshNanningCaptcha() {
        viewModelScope.launch {
            // 有会话就只换图（服务端验证码绑在会话上，换图不能连会话一起换）。
            captchaLogin.refreshCaptcha(ImportSource.NANNING).onSuccess { bitmap ->
                _uiState.value = _uiState.value.copy(captchaBitmap = bitmap, captchaInput = "")
            }
        }
    }

    // ---- 研究生验证码登录流程 ----

    /**
     * 研究生登录第一步：取验证码并弹出输入框。
     *
     * 门户的验证码与会话绑定在 Cookie 里，因此客户端要一直留到用户提交验证码为止，
     * 不能每步新建一个。
     */
    private fun startGraduateLoginFlow(guardAlreadyStarted: Boolean = false) {
        if (!guardAlreadyStarted && !loginGuard.tryStart()) return
        _uiState.value = _uiState.value.copy(isLoggingIn = true, message = "正在获取验证码...")
        viewModelScope.launch {
            try {
                // 取图失败的原因由 CaptchaLoginSession 组装好（网络 / HTTP 状态码 / 代码缺陷），
                // 这里原样展示，不再用 getOrNull() 把它吞成一句「无法获取验证码」。
                showCaptchaOrError(captchaLogin.requestCaptcha(ImportSource.GRADUATE))
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoggingIn = false, message = "获取验证码失败: ${e.message}")
            } finally {
                loginGuard.finish()
            }
        }
    }

    /** 刷新研究生验证码：换一张图，不新建会话。 */
    private fun refreshGraduateCaptcha() {
        viewModelScope.launch {
            captchaLogin.refreshCaptcha(ImportSource.GRADUATE).onSuccess { bitmap ->
                _uiState.value = _uiState.value.copy(captchaBitmap = bitmap, captchaInput = "")
            }
        }
    }

    private fun submitGraduateCaptcha() {
        val state = _uiState.value
        if (!captchaLogin.hasSession()) {
            _uiState.value = state.copy(message = "会话已过期，请重新开始")
            return
        }
        if (!loginGuard.tryStart()) return
        _uiState.value = state.copy(isLoggingIn = true, showCaptchaDialog = false, message = "正在登录...")
        viewModelScope.launch {
            try {
                captchaLogin.submit(
                    source = ImportSource.GRADUATE,
                    username = state.username,
                    password = state.password,
                    captcha = state.captchaInput.trim()
                ).fold(
                    onSuccess = { renewed ->
                        // 研究生导入要用整段会话（客户端 + 学年/学期/学号上下文），不只是 cookie。
                        val session = renewed.graduateSession
                        if (session == null) {
                            _uiState.value = _uiState.value.copy(
                                isLoggingIn = false,
                                message = "研究生登录失败，请重试"
                            )
                        } else {
                            onGraduateLoginSuccess(session, state.rememberPassword, state.username)
                        }
                    },
                    onFailure = { error ->
                        refreshGraduateCaptcha()
                        _uiState.value = _uiState.value.copy(
                            isLoggingIn = false,
                            showCaptchaDialog = true,
                            captchaInput = "",
                            message = error.message?.takeIf { it.isNotBlank() } ?: "研究生登录失败，请重试"
                        )
                    }
                )
            } catch (e: Exception) {
                refreshGraduateCaptcha()
                _uiState.value = _uiState.value.copy(
                    isLoggingIn = false,
                    showCaptchaDialog = true,
                    captchaInput = "",
                    message = e.message?.takeIf { it.isNotBlank() } ?: "研究生登录失败，请重试"
                )
            } finally {
                loginGuard.finish()
            }
        }
    }

    // ---- Shared helpers ----

    private suspend fun onLoginSuccess(
        cookie: String,
        campusBaseUrl: String,
        source: ImportSource,
        remember: Boolean,
        studentNumber: String,
        /**
         * 本次登录是否要改写本机保存的学号密码。
         *
         * 微信登录传 false：它没有密码，而 `uiState` 里的账号密码是上一位用户留下的。
         */
        updateCredentials: Boolean = true
    ) {
        val username = _uiState.value.username
        val password = _uiState.value.password
        val shouldRemember = effectiveRememberPassword(remember, credentialStore.canPersistCredentials())
        val previousStudent = sessionStore.authenticatedStudentNumber.first()
        val knownStudentNumber = studentNumber.trim()
        if (shouldClearAcademicData(previousStudent, knownStudentNumber)) {
            // 账号变化时先清空旧教务缓存，避免新账号短暂看到上一位学生的数据。
            semesterBulkDownloadCoordinator.cancelForAccountChange()
            scheduleRepository.clearAllData()
            sessionStore.clearAll()
        }
        if (updateCredentials) {
            if (shouldRemember) {
                credentialStore.saveCredentials(username, password)
            } else {
                credentialStore.clearCredentials()
            }
        }
        sessionStore.saveCookie(cookie)
        sessionStore.saveCampusBaseUrl(campusBaseUrl)
        // 微信登录此刻还拿不到学号。写空会把**上一位学生**的学号抹掉，
        // 下一轮 `shouldClearAcademicData` 读到空值就恒为 false，换账号清理彻底失效。
        // 真正的学号在 performImport 里解析出来后补写并补判。
        if (knownStudentNumber.isNotEmpty()) {
            sessionStore.saveAuthenticatedStudentNumber(knownStudentNumber)
        }
        // 来源切换**不在这里**：它必须发生在课表成功写入之后，否则中途失败会让界面停在
        // 「新来源的菜单 + 旧来源的数据」这种半切换状态。切换由 performImport 负责。
        _uiState.value = _uiState.value.copy(message = "登录成功，正在导入数据...")
        performImport(cookie, campusBaseUrl, source, knownStudentNumber)
    }

    /**
     * 研究生登录成功后的落库与导入。
     *
     * 与本科路径的区别：会话不能复用 `sessionStore` 的教务 Cookie（协议不同），
     * 上下文（学年/学期/学号）只活在内存里；导入完成后才切换 `CampusType`。
     */
    private suspend fun onGraduateLoginSuccess(
        session: GraduateLoginSession,
        remember: Boolean,
        studentNumber: String
    ) {
        val username = _uiState.value.username
        val password = _uiState.value.password
        val shouldRemember = effectiveRememberPassword(remember, credentialStore.canPersistCredentials())
        val previousStudent = sessionStore.authenticatedStudentNumber.first()
        val replacePreviousAccount = shouldClearAcademicData(previousStudent, studentNumber)
        if (shouldRemember) {
            credentialStore.saveCredentials(username, password)
        } else {
            credentialStore.clearCredentials()
        }
        _uiState.value = _uiState.value.copy(message = "登录成功，正在导入数据...")
        performGraduateImport(session, replacePreviousAccount)
    }

    /**
     * 按来源解析要写进学期行的作息。
     *
     * 桂林要区分雁山/屏风，所以走 [classPeriodProfile]；研究生与南宁各自独立。
     */
    private suspend fun sourceClassPeriods(source: ImportSource) =
        scheduleRepository.classPeriodsFor(
            classPeriodProfile(source.campus, settingsStore.guilinSubCampus.first())
        )

    private suspend fun performImport(
        cookie: String,
        campusBaseUrl: String,
        source: ImportSource,
        studentNumber: String
    ) {
        // 旧学号要在**任何写入之前**取：换账号判定有可能被推迟到解析出学号之后补做
        // （微信登录进来时 `studentNumber` 还是空串），那时这里读到的值已经被覆盖了。
        val previousStudent = sessionStore.authenticatedStudentNumber.first()
        var activeCookie = cookie
        var courseCount = 0
        var examCount = 0
        var scoreCount = 0
        var gradeExamCount = 0
        var studyPlanCount = 0
        val failedModules = linkedSetOf<String>()

        fun consumeProbeCookie(result: ApiProbeService.ProbeResult?) {
            if (result?.updatedCookie?.isNotBlank() == true) activeCookie = result.updatedCookie
        }

        try {
            val results = apiProbeService.probeAllEndpoints(cookie = activeCookie, baseUrl = campusBaseUrl)
            results.lastOrNull { it.updatedCookie.isNotBlank() }?.let(::consumeProbeCookie)
            sessionStore.saveCookie(activeCookie)
            val campus = if (campusBaseUrl == AcademicLoginResult.NANNING_URL) {
                com.glut.schedule.data.settings.CampusType.NANNING
            } else {
                com.glut.schedule.data.settings.CampusType.GUILIN
            }
            val calendar = ApiProbeService.extractAcademicCalendar(results)

            val catalogHtml = results.firstOrNull {
                it.url.contains("currcourse.jsdo") && it.httpCode in 200..299
            }?.body.orEmpty()
            val enrollmentResult = apiProbeService.probeUrl(
                activeCookie,
                "$campusBaseUrl/academic/student/studentinfo/studentInfoModifyIndex.do?frombase=0&wantTag=0"
            )
            consumeProbeCookie(enrollmentResult)
            val enrollmentHtml = enrollmentResult?.body.orEmpty()
            val studentName = AcademicSemesterParser.parseStudentName(enrollmentHtml)
            // 学号优先取教务自己返回的 `user` 对象，其次是个人信息页；两级都拿不到才退到调用方
            // 传入的值（密码登录是用户刚输入的，微信登录是空串），最后退到上一次已知学号——
            // 宁可保守地不清理，也不能把上一轮的学号抹成空，那会让换账号隔离整个失效。
            val frameBody = results.firstOrNull {
                it.url.contains("/personal/framePage.do", ignoreCase = true)
            }?.body.orEmpty()
            // 调用方给的学号排在最前：密码登录是用户刚输入的，微信登录是 IAM 递给教务的跳转里
            // 明写的 `userName`——两者都是权威来源。
            //
            // 页面反查只能垫后：教务页面里的 `user.id` 是**课表接口要的内部 id**（实测拿来查课表
            // 查得到、拿真学号反而查不到），它能通过「6~14 位纯数字」的形态校验，
            // 一旦排在前面就会把真学号顶掉，进而在推年级、选学期上错得毫无声响。
            val resolvedStudentNumber = listOfNotNull(
                studentNumber.trim().takeIf { it.isNotEmpty() },
                AcademicSemesterParser.parseStudentNumberFromFrameJson(frameBody),
                AcademicSemesterParser.parseStudentNumber(enrollmentHtml),
                previousStudent.trim().takeIf { it.isNotEmpty() }
            ).firstOrNull()
                ?: throw IllegalStateException("未能识别学号，请改用学号密码登录")
            if (shouldClearAcademicData(previousStudent, resolvedStudentNumber)) {
                // 微信登录走到这里才第一次拿到学号，换账号清理只能延迟到此刻补做。
                // clearAll() 会把上面刚存的 cookie 一并清掉，所以必须立刻补回。
                semesterBulkDownloadCoordinator.cancelForAccountChange()
                scheduleRepository.clearAllData()
                sessionStore.clearAll()
                sessionStore.saveCookie(activeCookie)
                sessionStore.saveCampusBaseUrl(campusBaseUrl)
            }
            sessionStore.saveAuthenticatedStudent(resolvedStudentNumber, studentName)
            // 凭据主人与当前学号对一次账。微信登录不写凭据，但机器上可能留着**另一个人**的密码，
            // 留着它，各页刷新时的静默登录就会拿别人的身份重建会话——界面是 B，数据翻回 A。
            // 密码登录路径下这里恒等（刚存的凭据就是本人），所以这一步对它是空操作。
            if (shouldClearStoredCredentialsForWechatLogin(credentialStore.getUsername(), resolvedStudentNumber)) {
                credentialStore.clearCredentials()
            }
            val enrollmentDate = AcademicSemesterParser.parseEnrollment(
                html = enrollmentHtml,
                studentNumber = resolvedStudentNumber
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
                    cookie = activeCookie,
                    baseUrl = campusBaseUrl,
                    semester = nextSemester,
                    studentIdFallback = resolvedStudentNumber
                ).also { result ->
                    result.getOrNull()?.updatedCookie?.takeIf(String::isNotBlank)?.let { activeCookie = it }
                }
            }
            val decision = AcademicSemesterProbePlanner.decide(catalogPlan, nextProbeResult)
            val semesterCatalog = decision.catalog
            val currentSemester = decision.currentSemester

            _uiState.value = _uiState.value.copy(message = "正在导入${currentSemester.displayName}课表...")
            val currentPayload = semesterImportService.importSemester(
                cookie = activeCookie,
                baseUrl = campusBaseUrl,
                semester = currentSemester,
                studentIdFallback = resolvedStudentNumber
            ).getOrElse { error ->
                throw IllegalStateException(
                    "${currentSemester.displayName}课表导入失败：${error.message.orEmpty()}",
                    error
                )
            }
            if (currentPayload.updatedCookie.isNotBlank()) activeCookie = currentPayload.updatedCookie
            sessionStore.saveCookie(activeCookie)
            sessionStore.saveHtmlPreview(currentPayload.currcourseHtml.take(3000))
            scheduleRepository.saveSemesterCatalog(semesterCatalog)
            settingsStore.setCurrentSemesterId(currentSemester.id)
            scheduleRepository.selectSemester(currentSemester.id)
            val resolvedCalendar = AcademicSemesterCalendarResolver.resolve(
                semester = currentSemester,
                today = LocalDate.now(),
                directCalendar = calendar,
                weeklyStartMonday = currentPayload.semesterStartMonday,
                portalMaxWeek = currentPayload.portalMaxWeek
            )
            settingsStore.setSemesterStartMonday(resolvedCalendar.startMonday)
            settingsStore.setSemesterEndDate(resolvedCalendar.endDate)
            settingsStore.setCurrentWeekNumber(resolvedCalendar.currentWeekNumber)
            // 学期日期刚写定，此时补齐该学期跨越年份的节假日数据。
            // 「重新导入课表」是新用户拿到节假日角标的唯一入口——他们必须先导入才能用，
            // 而其余取数入口（刷新课表 / 刷新学期概览）都需要用户主动点击。
            // 不依赖教务会话，失败也不影响导入，因此单独兜住异常。
            runCatching {
                refreshMissingHolidayYears(
                    client = timorHolidayClient,
                    years = resolvedCalendar.startMonday.year..resolvedCalendar.endDate.year,
                    cachedYears = settingsStore.holidayCacheByYear.first(),
                    saveYear = settingsStore::setHolidayYearCache
                )
                // 调休补课对照表同属这个入口：新用户导入完就拿到权威的调休安排，
                // 不必再跑到首页或学期概览去刷一次。同样失败不影响导入。
                refreshMakeupTable(makeupTableClient, settingsStore::setHolidayMakeupTableJson)
            }
            scheduleRepository.replaceSemesterSchedule(
                semester = currentSemester,
                courses = currentPayload.courses,
                adjustments = currentPayload.adjustments,
                // 按来源档案显式取作息：切换来源发生在下面写入成功之后，
                // 此刻读 currentClassPeriods 拿到的还是**上一个**来源的时间表。
                classPeriods = sourceClassPeriods(source),
                semesterStartDate = resolvedCalendar.startMonday,
                semesterEndDate = resolvedCalendar.endDate,
                portalMaxWeek = currentPayload.portalMaxWeek
            )
            // 课表已经落库，现在才切换来源。放在这之前会让导入失败时界面停在
            // 「新来源的菜单/作息 + 旧来源的数据」这种半切换状态。
            settingsStore.setCampusType(source.campus)
            settingsStore.setLastImportSource(source)
            courseCount = currentPayload.courses.countDistinctCourseTitles()
            // 重算排在落库与来源切换之后：让位判定要读刚落库的 semester_adjustments，
            // 而学期范围要读已写定的校历。开关没打开时它是 no-op。
            autoDayCopyCoordinator.recompose(currentSemester.id)

            // 与考试页共用多接口兜底链路，桂林首选接口返回空结构时仍会继续探测。
            val storedExamApiUrl = sessionStore.examApiUrl.first()
            importInitialExams(
                fetch = {
                    academicExamService.fetchExamData(
                        cookie = activeCookie,
                        storedExamApiUrl = storedExamApiUrl,
                        baseUrl = campusBaseUrl
                    ).map { exams ->
                        InitialExamImportData(
                            successfulUrl = academicExamService.lastSuccessfulExamUrl,
                            exams = exams
                        )
                    }
                },
                replaceExams = scheduleRepository::replaceExams,
                saveSuccessfulUrl = sessionStore::saveExamApiUrl
            ).onSuccess {
                examCount = it
            }.onFailure {
                failedModules += "考试"
            }

            fetchAndSaveScores(activeCookie, campusBaseUrl)
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
                    val planResult = apiProbeService.probeUrl(activeCookie, planUrl)
                    consumeProbeCookie(planResult)
                    if (planResult != null && planResult.httpCode == 200 && planResult.body.length > 500) {
                        var (groups, courses) = studyPlanParser.parseData(planResult.body)
                        // Step 3: 框架模式 — 任选课组详情
                        val selfBody = selfResult?.body ?: ""
                        val frameStudentId = if (selfBody.isNotEmpty()) studyPlanParser.parseFrameStudentId(selfBody) else null
                        if (frameStudentId != null) {
                            val frameUrl = "$campusBaseUrl/academic/manager/studyschedule/studentScheduleShowFrame.do?z=z&studentId=$frameStudentId&classId=$classId"
                            val frameResult = apiProbeService.probeUrl(activeCookie, frameUrl)
                            consumeProbeCookie(frameResult)
                            if (frameResult != null && frameResult.httpCode == 200 && frameResult.body.length > 500) {
                                val freeGroupIds = studyPlanParser.extractFreeGroupIds(frameResult.body)
                                if (freeGroupIds.isNotEmpty()) {
                                    val mg = groups.toMutableList()
                                    val mc = courses.toMutableList()
                                    for ((gid, gname) in freeGroupIds) {
                                        val gUrl = "$campusBaseUrl/academic/manager/studyschedule/scheduleFreeGroupCourseList.do?pojoTypeId=2&id=$gid"
                                        val gResult = apiProbeService.probeUrl(activeCookie, gUrl)
                                        consumeProbeCookie(gResult)
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
            sessionStore.saveCookie(activeCookie)

            _uiState.value = _uiState.value.copy(
                isLoggingIn = false,
                message = importCompletionMessage(
                    failedModules = failedModules,
                    skippedRowCount = currentPayload.skippedRowCount,
                    unparsedWeekTextCount = currentPayload.unparsedWeekTextCount
                ),
                importResult = ImportResult(courseCount, examCount, scoreCount, gradeExamCount, studyPlanCount),
                // 走到这里课表已落库（失败模块只影响考试成绩等旁支），因此算「导入完成」，
                // 主按钮变身「查看课表」。
                importCompleted = true,
                unparsedWeekTextCount = currentPayload.unparsedWeekTextCount
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
            scorePageUnavailableReason(html)?.let { reason ->
                // HTTP 200 的教务提示页不是权威空成绩，必须按失败处理并保留原缓存。
                return Result.failure(IllegalStateException(reason))
            }

            val scores = scoreParser.parseScoreHtml(html, isNanning = campusBaseUrl == AcademicLoginResult.NANNING_URL)
            scheduleRepository.replaceScores(scores)
            return Result.success(scores.size)
        } catch (error: Exception) {
            return Result.failure(error)
        }
    }

    // ---- 研究生导入 ----

    /**
     * 研究生导入主流程：学期目录 → 当前学期课表 → 成绩 → 培养计划。
     *
     * 与本科路径一样「各模块独立反馈失败」：课表失败即整体失败（没有课表就没有意义），
     * 成绩与培养计划失败只记在 `failedModules` 里，不影响已经写入的课表。
     *
     * 课表在本专科路径里是「考试 / 成绩 / 等级考试 / 教学计划」四件套；
     * 研究生门户只提供成绩与只读培养计划，其余模块在菜单层就是禁用状态。
     */
    private suspend fun performGraduateImport(
        session: GraduateLoginSession,
        replacePreviousAccount: Boolean
    ) {
        _uiState.value = _uiState.value.copy(message = "正在获取研究生学期目录...")
        val coordinator = GraduateImportCoordinator(
            effects = GraduateImportEffects(
                fetchCatalog = { GraduateImportService.catalog(it) },
                fetchSemester = { s, semester -> GraduateImportService.importSemester(s, semester) },
                fetchScores = { GraduateImportService.scores(it) },
                fetchStudyPlan = { GraduateImportService.studyPlan(it) },
                saveCatalog = { scheduleRepository.saveSemesterCatalog(it) },
                selectCurrentSemester = {
                    settingsStore.setCurrentSemesterId(it.id)
                    scheduleRepository.selectSemester(it.id)
                },
                saveCalendar = { start, end ->
                    settingsStore.setSemesterStartMonday(start)
                    settingsStore.setSemesterEndDate(end)
                    settingsStore.setCurrentWeekNumber(
                        academicWeekForDate(LocalDate.now(), start, academicMaxWeekForCalendar(start, end))
                    )
                },
                // 按档案取作息：切换来源之前读「当前校区」会拿到上一个来源的时间表。
                readClassPeriods = {
                    scheduleRepository.classPeriodsFor(ClassPeriodProfile.GRADUATE)
                },
                writeSchedule = { write ->
                    // 新账号的课表已经完成下载和解析，到这个提交点才清理旧账号缓存。
                    // 登录、目录或课表取数失败时，原账号数据保持可用。
                    if (replacePreviousAccount) {
                        semesterBulkDownloadCoordinator.cancelForAccountChange()
                        scheduleRepository.clearAllData()
                        sessionStore.clearAll()
                    }
                    scheduleRepository.replaceSemesterSchedule(
                        semester = write.semester,
                        courses = write.courses,
                        // 研究生的调课记录当前不并入课表：未经真实样本确认前，只作提示。
                        adjustments = emptyList(),
                        classPeriods = write.classPeriods,
                        semesterStartDate = write.semesterStartDate,
                        semesterEndDate = write.semesterEndDate,
                        portalMaxWeek = write.portalMaxWeek
                    )
                },
                writeScores = { scheduleRepository.replaceScores(it) },
                writeStudyPlan = { groups, courses ->
                    scheduleRepository.replaceStudyPlanData(groups, courses)
                },
                // 关键：只有课表写入成功之后才会走到这里。
                switchSource = {
                    // Cookie、主机、身份与来源在同一个成功提交点落库。登录后若目录或课表失败，
                    // 旧来源不会拿到研究生会话，也不会把后续刷新发往错误主机。
                    sessionStore.saveCookie(session.client.cookieHeader())
                    sessionStore.saveCampusBaseUrl(GraduateClient.BASE_URL)
                    sessionStore.saveAuthenticatedStudentNumber(session.context.studentId)
                    sessionStore.saveAuthenticatedStudent(session.context.studentId, session.context.name)
                    settingsStore.setCampusType(CampusType.GRADUATE)
                    settingsStore.setLastImportSource(ImportSource.GRADUATE)
                }
            )
        )
        coordinator.run(session)
            .onSuccess { outcome ->
                _uiState.value = _uiState.value.copy(
                    isLoggingIn = false,
                    message = graduateImportCompletionMessage(
                        failedModules = outcome.failedModules,
                        skippedRowCount = outcome.skippedRowCount,
                        unparsedWeekTextCount = outcome.unparsedWeekTextCount
                    ),
                    importResult = ImportResult(
                        courseCount = outcome.courseCount,
                        examCount = 0,
                        scoreCount = outcome.scoreCount,
                        gradeExamCount = 0,
                        studyPlanCount = outcome.studyPlanCount
                    ),
                    // 与本科路径同口径：课表成功写入即算导入完成。
                    importCompleted = true,
                    unparsedWeekTextCount = outcome.unparsedWeekTextCount,
                    adjustmentWarning = outcome.adjustmentWarning
                )
            }
            .onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isLoggingIn = false,
                    message = "部分导入失败: ${error.message}",
                    importResult = ImportResult(0, 0, 0, 0, 0)
                )
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
 * 用户改动登录输入（学号 / 密码 / 导入来源）后的状态：收回「本次会话已导入完成」。
 *
 * 为什么必须复位：`DirectLoginViewModel` 挂在 Activity 作用域（MainActivity 里由
 * `viewModel(...)` 创建），离开导入页不会销毁，完成标志一直留着。用户换个账号回来时
 * 若标志还在，主按钮就仍写着「查看课表」，而屏幕上那份课表是上一个账号的——
 * 点下去等于被送去看别人的课表。
 *
 * 抽成 [DirectLoginUiState] 的扩展而不是在三处内联 `copy(importCompleted = false)`：
 * 三处语义完全相同，且 ViewModel 的构造依赖一整套带 Context 的服务，
 * JVM 单测里起不来，只有纯函数才测得到复位语义。
 */
internal fun DirectLoginUiState.afterLoginInputEdit(): DirectLoginUiState =
    copy(importCompleted = false)

/**
 * 只有系统加密存储可用时才允许记住教务密码，避免任何明文降级路径。
 */
internal fun effectiveRememberPassword(
    requested: Boolean,
    secureStorageAvailable: Boolean
): Boolean = requested && secureStorageAvailable

/** 微信登录只覆盖桂林本部：南宁与研究生走的是完全不同的两套门户，没有可复用的入口。 */
internal const val WECHAT_GUILIN_ONLY_MESSAGE = "微信登录仅支持桂林本部，请切换到「桂林本部」后重试"

/** CAS 登录成功、教务却没建立会话（ticket 没被消费）——此时落库只会导入一堆失败。 */
internal const val WECHAT_NO_ACADEMIC_SESSION_MESSAGE =
    "已登录统一身份认证，但教务系统未建立会话，请重试或改用学号密码登录"

/**
 * 首次登录没有旧数据可清；只有切换到不同的已知学号时才隔离并清理教务缓存。
 */
internal fun shouldClearAcademicData(previousStudent: String, newStudent: String): Boolean {
    val previous = previousStudent.trim()
    val incoming = newStudent.trim()
    return previous.isNotEmpty() && incoming.isNotEmpty() && previous != incoming
}

internal fun importCompletionMessage(
    failedModules: Collection<String>,
    skippedRowCount: Int = 0,
    unparsedWeekTextCount: Int = 0
): String {
    val uniqueModules = failedModules.distinct()
    val message = if (uniqueModules.isEmpty()) {
        "导入完成"
    } else {
        "部分导入失败：${uniqueModules.joinToString("、")}；已保留原缓存"
    }
    val withSkipped = if (skippedRowCount > 0) "$message；已跳过 $skippedRowCount 条异常课程记录" else message
    // 周次无法识别的课次不会出现在任何一周。必须如实告知，否则用户看到的是
    // 「课程莫名其妙少了几门」，无从排查。
    return if (unparsedWeekTextCount > 0) {
        "$withSkipped；$unparsedWeekTextCount 条上课时间的周次无法识别"
    } else {
        withSkipped
    }
}

/**
 * 研究生导入的完成文案。
 *
 * 与本科版共用同一套口径（失败模块 + 跳过条数 + 无法识别的周次），但**不提「已保留原缓存」**：
 * 研究生课表失败时整体不写入，成绩/培养计划的失败各自保留自己的旧数据，
 * 笼统写「原缓存」会让用户以为课表也没更新。
 *
 * 「已跳过 N 条异常课程记录」与本科路径 `importCompletionMessage` 同一措辞：
 * 被跳过的记录不会出现在课表里，不告知就等于课程凭空消失。
 */
internal fun graduateImportCompletionMessage(
    failedModules: Collection<String>,
    skippedRowCount: Int = 0,
    unparsedWeekTextCount: Int = 0
): String {
    val uniqueModules = failedModules.distinct()
    val message = if (uniqueModules.isEmpty()) {
        "导入完成"
    } else {
        "部分导入失败：${uniqueModules.joinToString("、")}"
    }
    val withSkipped = if (skippedRowCount > 0) {
        "$message；已跳过 $skippedRowCount 条异常课程记录"
    } else {
        message
    }
    return if (unparsedWeekTextCount > 0) {
        "$withSkipped；$unparsedWeekTextCount 条上课时间的周次无法识别"
    } else {
        withSkipped
    }
}

class DirectLoginViewModelFactory(
    private val loginService: AcademicLoginService,
    private val sessionStore: AcademicSessionStore,
    private val credentialStore: CredentialStore,
    private val scheduleRepository: ScheduleRepository,
    private val settingsStore: ScheduleSettingsStore,
    private val apiProbeService: ApiProbeService,
    private val academicExamService: AcademicExamService,
    private val semesterImportService: AcademicSemesterImportService,
    private val semesterBulkDownloadCoordinator: SemesterBulkDownloadCoordinator,
    private val scoreParser: ScoreParser,
    private val gradeExamParser: GradeExamParser = GradeExamParser(),
    private val studyPlanParser: StudyPlanParser = StudyPlanParser(),
    private val autoDayCopyCoordinator: AutoDayCopyCoordinator,
    private val timorHolidayClient: TimorHolidayClient = TimorHolidayClient(),
    private val makeupTableClient: MakeupTableClient = MakeupTableClient()
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return DirectLoginViewModel(
            loginService, sessionStore, credentialStore,
            scheduleRepository, settingsStore, apiProbeService,
            academicExamService, semesterImportService, semesterBulkDownloadCoordinator,
            scoreParser, gradeExamParser, studyPlanParser, autoDayCopyCoordinator,
            timorHolidayClient, makeupTableClient
        ) as T
    }
}
