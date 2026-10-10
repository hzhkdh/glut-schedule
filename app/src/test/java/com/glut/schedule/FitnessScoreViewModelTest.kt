package com.glut.schedule

import com.glut.schedule.data.model.FitnessHistoryRequest
import com.glut.schedule.service.fitness.FitnessApiResponse
import com.glut.schedule.service.fitness.FitnessGateway
import com.glut.schedule.service.fitness.FitnessStorage
import com.glut.schedule.ui.pages.FitnessScoreViewModel
import com.glut.schedule.ui.pages.FitnessTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FitnessScoreViewModelTest {
    /**
     * Main 调度器。UnconfinedTestDispatcher 与 runTest 共享同一个 TestCoroutineScheduler，
     * 所以 advanceUntilIdle() 能驱动挂在它上面的协程（包含 delay）。
     */
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    /**
     * 统一构造被测 VM，并把 VM 内部的 workerDispatcher 也指向测试调度器。
     *
     * 这里必须显式注入，否则测试会偶发地红：VM 原本把解析、读写缓存硬编码在 Dispatchers.Default 上，
     * 而 setMain 只能接管 viewModelScope 用的 Main。于是 withContext(Dispatchers.Default) 返回后，
     * 协程会以 unconfined 语义继续跑在真实线程池线程上；advanceUntilIdle() 只清空测试调度器，
     * 排不掉真实线程上的续体。结果是测试体已经结束、tearDown 的 resetMain() 已经执行，
     * 该协程才走到 showMessage() → viewModelScope.launch{}，访问已失效的 Dispatchers.Main 抛
     * IllegalStateException（全量跑时负载一高就会复现）。
     *
     * 注入之后 VM 的所有协程都由同一个测试调度器驱动，advanceUntilIdle() 必然能把它们排空，
     * 测试结束时不会再有协程滞留在别的线程上，断言也就不再依赖真实时间与线程调度。
     */
    private fun createViewModel(
        gateway: FitnessGateway,
        store: FitnessStorage = FakeStorage()
    ) = FitnessScoreViewModel(gateway, store, workerDispatcher = dispatcher)

    @Test
    fun openingLoginOnlyLoadsCaptchaInsteadOfShowingLoginInProgress() = runTest {
        val captchaGate = CompletableDeferred<FitnessApiResponse>()
        val vm = createViewModel(FakeGateway(captchaGate = captchaGate), FakeStorage())

        vm.showLogin()

        assertTrue(vm.uiState.value.showLoginDialog)
        assertTrue(vm.uiState.value.isCaptchaLoading)
        assertFalse(vm.uiState.value.isLoggingIn)
        assertFalse(vm.uiState.value.isRefreshing)

        captchaGate.complete(captchaResponse())
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isCaptchaLoading)
    }

    @Test
    fun blankCredentialsNeverEnterLoginState() = runTest {
        val gateway = FakeGateway()
        val vm = createViewModel(gateway, FakeStorage())
        vm.showLogin()
        advanceUntilIdle()

        vm.login()

        assertFalse(vm.uiState.value.isLoggingIn)
        assertFalse(vm.uiState.value.isRefreshing)
        assertTrue(gateway.loginCalls.isEmpty())
        assertEquals("请输入学号", vm.uiState.value.loginError)
    }

    @Test
    fun dismissingDialogClearsPendingCaptchaLoadingState() = runTest {
        val captchaGate = CompletableDeferred<FitnessApiResponse>()
        val vm = createViewModel(FakeGateway(captchaGate = captchaGate), FakeStorage())
        vm.showLogin()

        vm.dismissLogin()

        assertFalse(vm.uiState.value.showLoginDialog)
        assertFalse(vm.uiState.value.isCaptchaLoading)
        assertFalse(vm.uiState.value.isLoggingIn)
        assertFalse(vm.uiState.value.isRefreshing)

        captchaGate.complete(captchaResponse())
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isCaptchaLoading)
    }

    @Test
    fun loginSubmissionRequiresAllFieldsAndLoadedCaptcha() {
        val ready = com.glut.schedule.ui.pages.FitnessScoreUiState(
            username = "2024001",
            password = "password",
            captcha = "1234",
            captchaImage = "data:image/jpeg;base64,aW1hZ2U="
        )

        assertFalse(com.glut.schedule.ui.pages.FitnessScoreUiState().canSubmitLogin)
        assertTrue(ready.canSubmitLogin)
        assertFalse(ready.copy(isCaptchaLoading = true).canSubmitLogin)
        assertFalse(ready.copy(isLoggingIn = true).canSubmitLogin)
    }

    @Test
    fun captchaKeyAndCookieArePassedToTheMatchingLogin() = runTest {
        val gateway = FakeGateway()
        val store = FakeStorage()
        val vm = createViewModel(gateway, store)

        vm.showLogin()
        vm.updateUsername("2024001")
        vm.updatePassword("password")
        vm.updateCaptcha("1234")
        vm.login()
        advanceUntilIdle()

        assertEquals(
            LoginCall("2024001", "password", "1234", "captcha-cookie", "captcha-key"),
            gateway.loginCalls.single()
        )
        assertEquals("school-session", store.storedSession)
        assertFalse(vm.uiState.value.showLoginDialog)
    }

    @Test
    fun historyDetailUsesFieldsParsedFromTheOfficialForm() = runTest {
        val request = FitnessHistoryRequest("student-a", "2024-2025", "1", "2024", "1")
        val gateway = FakeGateway(loginResponse = FitnessApiResponse(
            success = true,
            fitnessCookie = "school-session",
            historyHtml = historyHtml()
        ))
        val vm = createViewModel(gateway, FakeStorage())

        vm.showLogin()
        vm.updateUsername("2024001")
        vm.updatePassword("password")
        vm.updateCaptcha("1234")
        vm.login()
        // VM 与 loadCache 都在测试调度器上，advanceUntilIdle() 一次就能把它们全部跑完，
        // 不再需要「真实时间内轮询状态」那种写法（它会放过仍停在别的线程上的续体）。
        advanceUntilIdle()
        vm.selectTab(FitnessTab.HISTORY)
        advanceUntilIdle()

        assertEquals(1, gateway.detailRequests.size)
        assertEquals(request, gateway.detailRequests.single())
    }

    @Test
    fun expiredRefreshClearsSessionAndShowsLogin() = runTest {
        val store = FakeStorage(storedSession = "expired")
        val gateway = FakeGateway(refreshResponse = FitnessApiResponse(
            success = false,
            code = "FITNESS_SESSION_EXPIRED",
            message = "体测登录已失效，请重新登录"
        ))
        val vm = createViewModel(gateway, store)

        vm.refresh()
        advanceUntilIdle()

        assertEquals("", store.storedSession)
        assertTrue(vm.uiState.value.showLoginDialog)
        assertEquals("登录态已过期，请重新登录", vm.uiState.value.message)
    }

    @Test
    fun refreshingLegacyHistoryReloadsTheListBeforeRequestingDetail() = runTest {
        val gateway = FakeGateway(refreshResponse = FitnessApiResponse(
            success = true,
            fitnessCookie = "school-session",
            historyHtml = historyHtml()
        ))
        val vm = createViewModel(
            gateway,
            FakeStorage(storedSession = "school-session", initialHistory = legacyHistoryHtml())
        )
        // 构造时的 loadCache 已经把 legacy 列表解析进状态；刷新后的详情请求同样由测试调度器驱动
        advanceUntilIdle()
        vm.selectTab(FitnessTab.HISTORY)

        vm.refresh()
        advanceUntilIdle()

        assertEquals(1, gateway.refreshCalls)
        assertEquals(1, gateway.detailRequests.size)
    }

    @Test
    fun clearDataPreventsAnOlderRefreshFromRestoringScores() = runTest {
        val refreshGate = CompletableDeferred<FitnessApiResponse>()
        val store = FakeStorage(storedSession = "old-session")
        val gateway = FakeGateway(refreshGate = refreshGate)
        val vm = createViewModel(gateway, store)

        vm.refresh()
        vm.clearData()
        refreshGate.complete(FitnessApiResponse(
            success = true,
            fitnessCookie = "restored-session",
            currentHtml = currentHtml(),
            historyHtml = historyHtml()
        ))
        advanceUntilIdle()

        assertEquals("", store.storedSession)
        assertEquals("", store.getCurrentHtml())
        assertTrue(vm.uiState.value.current == null)
        assertTrue(vm.uiState.value.history.isEmpty())
    }

    @Test
    fun staleExpiredRefreshCannotClearANewerLoginSession() = runTest {
        val refreshGate = CompletableDeferred<FitnessApiResponse>()
        val store = FakeStorage(storedSession = "old-session")
        val gateway = FakeGateway(
            loginResponse = FitnessApiResponse(
                success = true,
                fitnessCookie = "new-session",
                currentHtml = currentHtml(),
                historyHtml = historyHtml()
            ),
            refreshGate = refreshGate
        )
        val vm = createViewModel(gateway, store)

        vm.refresh()
        vm.showLogin()
        vm.updateUsername("2024002")
        vm.updatePassword("new-password")
        vm.updateCaptcha("1234")
        vm.login()
        refreshGate.complete(FitnessApiResponse(
            success = false,
            code = "FITNESS_SESSION_EXPIRED",
            message = "体测登录已失效，请重新登录"
        ))
        advanceUntilIdle()

        // 等价于原来等待的「新一轮登录已经落地」：成绩已写入、登录弹窗已关闭
        assertTrue(vm.uiState.value.current != null)
        assertFalse(vm.uiState.value.showLoginDialog)
        assertEquals("new-session", store.storedSession)
    }

    @Test
    fun successfulLoginClearsHistoryDetailsFromThePreviousAccount() = runTest {
        val store = FakeStorage(
            storedSession = "old-session",
            initialHistory = historyHtml(),
            initialDetails = mapOf(
                "2024-2025-1" to currentHtml("肺活量", "60")
            )
        )
        val gateway = FakeGateway(
            loginResponse = FitnessApiResponse(
                success = true,
                fitnessCookie = "new-session",
                currentHtml = currentHtml(),
                historyHtml = historyHtml()
            ),
            detailResponses = ArrayDeque(listOf(
                FitnessApiResponse(success = true, detailHtml = currentHtml("50米跑", "90"))
            ))
        )
        val vm = createViewModel(gateway, store)
        // 构造时的 loadCache 已把上一个账号的详情读进状态
        advanceUntilIdle()

        vm.showLogin()
        vm.updateUsername("2024002")
        vm.updatePassword("new-password")
        vm.updateCaptcha("1234")
        vm.login()
        advanceUntilIdle()

        // 原等待要求「新详情已展示且弹窗已关」，这里显式断言，避免状态没到位时下面两条断言被误判为通过
        assertTrue(vm.uiState.value.visibleHistoryResult?.items.orEmpty().any { it.name == "50米跑" })
        assertFalse(vm.uiState.value.showLoginDialog)
        assertFalse(vm.uiState.value.visibleHistoryResult?.items.orEmpty().any { it.name == "肺活量" })
        assertTrue(store.getHistoryDetail("2024-2025", "1").contains("50米跑"))
    }

    @Test
    fun loginLoadsEveryHistoryDetailAndStandardWithTheLatestSession() = runTest {
        val gateway = FakeGateway(
            loginResponse = FitnessApiResponse(
                success = true,
                fitnessCookie = "login-session",
                currentHtml = currentHtml(),
                historyHtml = twoRecordHistoryHtml()
            ),
            detailResponses = ArrayDeque(listOf(
                FitnessApiResponse(
                    success = true,
                    fitnessCookie = "detail-session-1",
                    detailHtml = currentHtml("肺活量", "81")
                ),
                FitnessApiResponse(
                    success = true,
                    fitnessCookie = "detail-session-2",
                    detailHtml = currentHtml("50米跑", "82")
                )
            )),
            standardResponse = FitnessApiResponse(
                success = true,
                fitnessCookie = "standard-session",
                standardHtml = standardHtml()
            )
        )
        val store = FakeStorage()
        val vm = createViewModel(gateway, store)

        vm.showLogin()
        vm.updateUsername("2024001")
        vm.updatePassword("password")
        vm.updateCaptcha("1234")
        vm.login()
        advanceUntilIdle()

        // 等价于原来等待的「两条详情 + 评分标准都已装填」，改成确定性断言
        assertEquals(2, vm.uiState.value.historyDetails.size)
        assertTrue(vm.uiState.value.standards.isNotEmpty())

        assertEquals(listOf("login-session", "detail-session-1"), gateway.detailCookies)
        assertEquals(listOf("detail-session-2"), gateway.standardCookies)
        assertEquals("standard-session", store.storedSession)
    }

    @Test
    fun tabChangesDuringLoginDoNotCancelTheFullDataLoad() = runTest {
        val firstDetail = CompletableDeferred<FitnessApiResponse>()
        val gateway = FakeGateway(
            loginResponse = FitnessApiResponse(
                success = true,
                fitnessCookie = "login-session",
                currentHtml = currentHtml(),
                historyHtml = twoRecordHistoryHtml()
            ),
            detailGate = firstDetail,
            detailResponses = ArrayDeque(listOf(
                FitnessApiResponse(success = true, detailHtml = currentHtml("50米跑", "82"))
            )),
            standardResponse = FitnessApiResponse(success = true, standardHtml = standardHtml())
        )
        val vm = createViewModel(gateway, FakeStorage())

        vm.showLogin()
        vm.updateUsername("2024001")
        vm.updatePassword("password")
        vm.updateCaptcha("1234")
        vm.login()
        // login() 会同步跑到第一个详情请求处才挂上 detailGate，所以这里可以直接断言请求已发出
        assertTrue(gateway.detailStarted.isCompleted)
        vm.selectTab(FitnessTab.HISTORY)
        vm.selectTab(FitnessTab.STANDARD)
        vm.refresh()
        firstDetail.complete(FitnessApiResponse(success = true, detailHtml = currentHtml("肺活量", "81")))
        advanceUntilIdle()

        // 等价于原来等待的「两条详情 + 评分标准都已装填」
        assertEquals(2, vm.uiState.value.historyDetails.size)
        assertTrue(vm.uiState.value.standards.isNotEmpty())

        assertEquals(2, gateway.detailRequests.size)
        assertEquals(1, gateway.standardCookies.size)
    }

    @Test
    fun selectingStandardWhileLoggedOutDoesNotOpenLoginOutsideLatestTab() = runTest {
        val gateway = FakeGateway()
        val vm = createViewModel(gateway, FakeStorage())

        vm.selectTab(FitnessTab.STANDARD)
        advanceUntilIdle()

        assertEquals(FitnessTab.STANDARD, vm.uiState.value.activeTab)
        assertFalse(vm.uiState.value.showLoginDialog)
        assertEquals(0, gateway.captchaCalls)
    }

    @Test
    fun partialDetailFailureStillPassesItsRotatedSessionToTheNextRequest() = runTest {
        val gateway = FakeGateway(
            loginResponse = FitnessApiResponse(
                success = true,
                fitnessCookie = "login-session",
                currentHtml = currentHtml(),
                historyHtml = twoRecordHistoryHtml()
            ),
            detailResponses = ArrayDeque(listOf(
                FitnessApiResponse(
                    success = false,
                    code = "FITNESS_UNEXPECTED_PAGE",
                    fitnessCookie = "rotated-after-failure"
                ),
                FitnessApiResponse(
                    success = true,
                    fitnessCookie = "detail-session-2",
                    detailHtml = currentHtml("50米跑", "82")
                )
            )),
            standardResponse = FitnessApiResponse(success = true, standardHtml = standardHtml())
        )
        val vm = createViewModel(gateway, FakeStorage())

        vm.showLogin()
        vm.updateUsername("2024001")
        vm.updatePassword("password")
        vm.updateCaptcha("1234")
        vm.login()
        // 登录、两条历年详情、评分标准这一整轮全部由测试调度器驱动（workerDispatcher 注入），
        // advanceUntilIdle() 会把它们确定性地跑完。
        // 之前的写法是在真实线程上 withTimeout + first{} 轮询状态：它既排不掉 VM 残留的续体，
        // 又会在负载高时让测试体先结束 —— VM 协程随后才走到 showMessage() 里的
        // viewModelScope.launch{}，撞上 tearDown 里已执行的 resetMain()，
        // 抛 “Dispatchers.Main was accessed ... after Dispatchers.resetMain()”。
        advanceUntilIdle()

        // 原来那条等待所要求的终态，改成确定性断言（断言强度只增不减）
        assertTrue(vm.uiState.value.standards.isNotEmpty())
        assertFalse(vm.uiState.value.isRefreshing)

        assertEquals(listOf("login-session", "rotated-after-failure"), gateway.detailCookies)
        assertEquals(listOf("detail-session-2"), gateway.standardCookies)
        vm.clearData()
    }

    @Test
    fun clearDataDuringFullLoadPreventsTheOldBatchFromWritingAResultMessage() = runTest {
        val firstDetail = CompletableDeferred<FitnessApiResponse>()
        val gateway = FakeGateway(
            loginResponse = FitnessApiResponse(
                success = true,
                fitnessCookie = "login-session",
                currentHtml = currentHtml(),
                historyHtml = twoRecordHistoryHtml()
            ),
            detailGate = firstDetail
        )
        val store = FakeStorage()
        val vm = createViewModel(gateway, store)

        vm.showLogin()
        vm.updateUsername("2024001")
        vm.updatePassword("password")
        vm.updateCaptcha("1234")
        vm.login()
        // 断言旧批次确实已发出并卡在网关上（否则下面的断言会失去意义）
        assertTrue(gateway.detailStarted.isCompleted)
        vm.clearData()
        firstDetail.complete(FitnessApiResponse(success = true, detailHtml = currentHtml()))
        // 原来用真实线程 delay(50) 等旧批次收尾；现在整条链路都在测试调度器上，排空即可
        advanceUntilIdle()

        assertEquals("", store.storedSession)
        assertEquals("", vm.uiState.value.message)
        assertTrue(vm.uiState.value.history.isEmpty())
        assertTrue(vm.uiState.value.historyDetails.isEmpty())
    }

    private data class LoginCall(
        val username: String,
        val password: String,
        val captcha: String,
        val cookie: String,
        val loginKey: String
    )

    private class FakeGateway(
        private val loginResponse: FitnessApiResponse = FitnessApiResponse(success = true, fitnessCookie = "school-session"),
        private val refreshResponse: FitnessApiResponse = FitnessApiResponse(success = true),
        private val refreshGate: CompletableDeferred<FitnessApiResponse>? = null,
        private val captchaGate: CompletableDeferred<FitnessApiResponse>? = null,
        private val detailResponses: ArrayDeque<FitnessApiResponse> = ArrayDeque(),
        private val detailGate: CompletableDeferred<FitnessApiResponse>? = null,
        private val standardResponse: FitnessApiResponse = FitnessApiResponse(success = true)
    ) : FitnessGateway {
        val loginCalls = mutableListOf<LoginCall>()
        val detailRequests = mutableListOf<FitnessHistoryRequest>()
        val detailCookies = mutableListOf<String>()
        val standardCookies = mutableListOf<String>()
        val detailStarted = CompletableDeferred<Unit>()
        var captchaCalls = 0
        var refreshCalls = 0

        override suspend fun getCaptcha(cookie: String): FitnessApiResponse {
            captchaCalls++
            return captchaGate?.await() ?: captchaResponse()
        }

        override suspend fun login(
            username: String,
            password: String,
            captcha: String,
            cookie: String,
            loginKey: String
        ): FitnessApiResponse {
            loginCalls += LoginCall(username, password, captcha, cookie, loginKey)
            return loginResponse
        }

        override suspend fun refresh(cookie: String): FitnessApiResponse {
            refreshCalls++
            return refreshGate?.await() ?: refreshResponse
        }

        override suspend fun getHistoryDetail(cookie: String, request: FitnessHistoryRequest): FitnessApiResponse {
            detailRequests += request
            detailCookies += cookie
            if (!detailStarted.isCompleted) detailStarted.complete(Unit)
            if (detailRequests.size == 1 && detailGate != null) return detailGate.await()
            return detailResponses.removeFirstOrNull()
                ?: FitnessApiResponse(success = true, detailHtml = "<table></table>")
        }

        override suspend fun getStandard(cookie: String): FitnessApiResponse {
            standardCookies += cookie
            return standardResponse
        }
    }

    private class FakeStorage(
        var storedSession: String = "",
        initialHistory: String = "",
        initialDetails: Map<String, String> = emptyMap()
    ) : FitnessStorage {
        private var username = ""
        private var password = ""
        private var current = ""
        private var history = initialHistory
        private var standard = ""
        private val details = initialDetails.toMutableMap()

        override fun saveCredentials(username: String, password: String) { this.username = username; this.password = password }
        override fun getUsername() = username
        override fun getPassword() = password
        override fun saveSession(cookie: String) { storedSession = cookie }
        override fun getSession() = storedSession
        override fun clearSession() { storedSession = "" }
        override fun saveSnapshot(currentHtml: String, historyHtml: String) { current = currentHtml; history = historyHtml }
        override fun getCurrentHtml() = current
        override fun getHistoryHtml() = history
        override fun saveHistoryDetail(year: String, term: String, html: String) { details["$year-$term"] = html }
        override fun getHistoryDetail(year: String, term: String) = details["$year-$term"].orEmpty()
        override fun saveStandard(html: String) { standard = html }
        override fun getStandardHtml() = standard
        override fun clearAccountCache() { storedSession = ""; current = ""; history = ""; details.clear() }
        override fun clearAll() { username = ""; password = ""; storedSession = ""; current = ""; history = ""; standard = ""; details.clear() }
    }

    companion object {
        private fun captchaResponse() = FitnessApiResponse(
            success = true,
            fitnessCookie = "captcha-cookie",
            captchaImage = "data:image/jpeg;base64,aW1hZ2U=",
            loginKey = "captcha-key"
        )

        private fun historyHtml() = """
            <form method="post" action="/SportWeb/health_info/listdetalhistroyScore.jsp">
              <input name="studentNo" value="student-a"><input name="academicYear" value="2024-2025">
              <input name="term" value="1"><input name="gradeNo" value="2024"><input name="sex" value="1">
              <table><tr><td>学年</td><td>学期</td><td>年级</td><td>体测成绩</td><td>体测等级</td></tr>
              <tr><td>2024-2025</td><td>1</td><td>2024</td><td>88</td><td>良好</td></tr></table>
            </form>
        """.trimIndent()

        private fun legacyHistoryHtml() = """
            <table><tr><td>学年</td><td>学期</td><td>年级</td><td>体测成绩</td><td>体测等级</td></tr>
            <tr><td>2024-2025</td><td>1</td><td>2024</td><td>88</td><td>良好</td></tr></table>
        """.trimIndent()

        private fun currentHtml(name: String = "肺活量", score: String = "80") = """
            <table><tr><td>项目名称</td><td>测试成绩</td></tr>
            <tr><td>$name</td><td>$score</td><td>$score</td><td>良好</td></tr></table>
        """.trimIndent()

        private fun twoRecordHistoryHtml() = """
            <table>
              <tr><td>学年</td><td>学期</td><td>年级</td><td>体测成绩</td><td>体测等级</td><td>详细</td></tr>
              <tr><td>2024-2025</td><td>1</td><td>1</td><td>88</td><td>良好</td><td>
                <form action="/SportWeb/health_info/listdetalhistroyScore.jsp">
                  <input name="studentNo" value="student-a"><input name="academicYear" value="2024-2025">
                  <input name="term" value="1"><input name="gradeNo" value="1"><input name="sex" value="1">
                </form></td></tr>
              <tr><td>2023-2024</td><td>2</td><td>1</td><td>80</td><td>及格</td><td>
                <form action="/SportWeb/health_info/listdetalhistroyScore.jsp">
                  <input name="studentNo" value="student-a"><input name="academicYear" value="2023-2024">
                  <input name="term" value="2"><input name="gradeNo" value="1"><input name="sex" value="1">
                </form></td></tr>
            </table>
        """.trimIndent()

        private fun standardHtml() = """
            <table><tr><td>表一：学生体质测试评分标准（大学男生）</td></tr>
            <tr><td>分值\项目</td><td>肺活量(12年级)</td></tr>
            <tr><td>优秀</td><td>100</td><td>5040</td></tr></table>
        """.trimIndent()
    }
}
