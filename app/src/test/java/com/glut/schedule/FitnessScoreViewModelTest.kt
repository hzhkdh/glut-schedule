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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FitnessScoreViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun openingLoginOnlyLoadsCaptchaInsteadOfShowingLoginInProgress() = runTest {
        val captchaGate = CompletableDeferred<FitnessApiResponse>()
        val vm = FitnessScoreViewModel(FakeGateway(captchaGate = captchaGate), FakeStorage())

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
        val vm = FitnessScoreViewModel(gateway, FakeStorage())
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
        val vm = FitnessScoreViewModel(FakeGateway(captchaGate = captchaGate), FakeStorage())
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
        val vm = FitnessScoreViewModel(gateway, store)

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
        val vm = FitnessScoreViewModel(gateway, FakeStorage())

        vm.showLogin()
        vm.updateUsername("2024001")
        vm.updatePassword("password")
        vm.updateCaptcha("1234")
        vm.login()
        withContext(Dispatchers.Default) {
            withTimeout(2_000) { vm.uiState.first { it.history.isNotEmpty() } }
        }
        vm.selectTab(FitnessTab.HISTORY)
        withContext(Dispatchers.Default) {
            withTimeout(2_000) { vm.uiState.first { it.historyDetails.isNotEmpty() } }
        }

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
        val vm = FitnessScoreViewModel(gateway, store)

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
        val vm = FitnessScoreViewModel(
            gateway,
            FakeStorage(storedSession = "school-session", initialHistory = legacyHistoryHtml())
        )
        withContext(Dispatchers.Default) {
            withTimeout(2_000) { vm.uiState.first { it.history.isNotEmpty() } }
        }
        vm.selectTab(FitnessTab.HISTORY)

        vm.refresh()
        withContext(Dispatchers.Default) {
            withTimeout(2_000) { vm.uiState.first { it.historyDetails.isNotEmpty() } }
        }

        assertEquals(1, gateway.refreshCalls)
        assertEquals(1, gateway.detailRequests.size)
    }

    @Test
    fun clearDataPreventsAnOlderRefreshFromRestoringScores() = runTest {
        val refreshGate = CompletableDeferred<FitnessApiResponse>()
        val store = FakeStorage(storedSession = "old-session")
        val gateway = FakeGateway(refreshGate = refreshGate)
        val vm = FitnessScoreViewModel(gateway, store)

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
        val vm = FitnessScoreViewModel(gateway, store)

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
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                vm.uiState.first { it.current != null && !it.showLoginDialog }
            }
        }
        advanceUntilIdle()

        assertEquals("new-session", store.storedSession)
        assertFalse(vm.uiState.value.showLoginDialog)
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
        val vm = FitnessScoreViewModel(gateway, store)
        withContext(Dispatchers.Default) {
            withTimeout(2_000) { vm.uiState.first { it.historyDetails.isNotEmpty() } }
        }

        vm.showLogin()
        vm.updateUsername("2024002")
        vm.updatePassword("new-password")
        vm.updateCaptcha("1234")
        vm.login()
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                vm.uiState.first {
                    it.visibleHistoryResult?.items?.any { item -> item.name == "50米跑" } == true &&
                        !it.showLoginDialog
                }
            }
        }
        advanceUntilIdle()

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
        val vm = FitnessScoreViewModel(gateway, store)

        vm.showLogin()
        vm.updateUsername("2024001")
        vm.updatePassword("password")
        vm.updateCaptcha("1234")
        vm.login()
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                vm.uiState.first { it.historyDetails.size == 2 && it.standards.isNotEmpty() }
            }
        }
        advanceUntilIdle()

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
        val vm = FitnessScoreViewModel(gateway, FakeStorage())

        vm.showLogin()
        vm.updateUsername("2024001")
        vm.updatePassword("password")
        vm.updateCaptcha("1234")
        vm.login()
        withContext(Dispatchers.Default) {
            withTimeout(2_000) { gateway.detailStarted.await() }
        }
        vm.selectTab(FitnessTab.HISTORY)
        vm.selectTab(FitnessTab.STANDARD)
        vm.refresh()
        firstDetail.complete(FitnessApiResponse(success = true, detailHtml = currentHtml("肺活量", "81")))
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                vm.uiState.first { it.historyDetails.size == 2 && it.standards.isNotEmpty() }
            }
        }
        advanceUntilIdle()

        assertEquals(2, gateway.detailRequests.size)
        assertEquals(1, gateway.standardCookies.size)
    }

    @Test
    fun selectingStandardWhileLoggedOutDoesNotOpenLoginOutsideLatestTab() = runTest {
        val gateway = FakeGateway()
        val vm = FitnessScoreViewModel(gateway, FakeStorage())

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
        val vm = FitnessScoreViewModel(gateway, FakeStorage())

        vm.showLogin()
        vm.updateUsername("2024001")
        vm.updatePassword("password")
        vm.updateCaptcha("1234")
        vm.login()
        withContext(Dispatchers.Default) {
            withTimeout(2_000) { vm.uiState.first { it.standards.isNotEmpty() && !it.isRefreshing } }
        }
        advanceUntilIdle()

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
        val vm = FitnessScoreViewModel(gateway, store)

        vm.showLogin()
        vm.updateUsername("2024001")
        vm.updatePassword("password")
        vm.updateCaptcha("1234")
        vm.login()
        withContext(Dispatchers.Default) {
            withTimeout(2_000) { gateway.detailStarted.await() }
        }
        vm.clearData()
        firstDetail.complete(FitnessApiResponse(success = true, detailHtml = currentHtml()))
        withContext(Dispatchers.Default) { delay(50) }
        runCurrent()

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
