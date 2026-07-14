package com.glut.schedule

import com.glut.schedule.data.model.CachedFinancePayload
import com.glut.schedule.data.model.FinanceCredentials
import com.glut.schedule.data.model.FinanceItem
import com.glut.schedule.data.model.FinanceModule
import com.glut.schedule.data.model.FinancePayload
import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.service.finance.FinanceCaptcha
import com.glut.schedule.service.finance.FinanceFailure
import com.glut.schedule.service.finance.FinanceGateway
import com.glut.schedule.service.finance.FinanceResponse
import com.glut.schedule.service.finance.FinanceStorage
import com.glut.schedule.ui.pages.FinanceViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class FinanceViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun switchingModuleReadsCacheWithoutNetwork() = runTest {
        val gateway = FakeGateway()
        val store = FakeStore().apply {
            saveModule(FinanceModule.TRANSACTIONS, CachedFinancePayload(FinancePayload.Items(listOf(FinanceItem("1", "缓存交易"))), 10L))
        }
        val vm = FinanceViewModel(gateway, store, CampusType.GUILIN)

        vm.selectModule(FinanceModule.TRANSACTIONS)

        assertTrue(gateway.fetches.isEmpty())
        assertEquals("缓存交易", (vm.uiState.value.activePayload as FinancePayload.Items).values.single().name)
    }

    @Test
    fun pendingModuleReusesPendingItemsAlreadyStoredInOverview() = runTest {
        val gateway = FakeGateway()
        val overview = com.glut.schedule.data.model.FinanceOverview(
            pendingItems = listOf(FinanceItem("p1", "住宿费", amount = "1200"))
        )
        val store = FakeStore().apply {
            saveModule(FinanceModule.OVERVIEW, CachedFinancePayload(FinancePayload.Overview(overview), 10L))
        }
        val vm = FinanceViewModel(gateway, store, CampusType.GUILIN)

        vm.selectModule(FinanceModule.PENDING)

        assertEquals("住宿费", (vm.uiState.value.activePayload as FinancePayload.Items).values.single().name)
        assertTrue(gateway.fetches.isEmpty())
    }

    @Test
    fun refreshRequestsOnlyActiveModule() = runTest {
        val gateway = FakeGateway()
        val store = FakeStore(cookie = "session")
        val vm = FinanceViewModel(gateway, store, CampusType.GUILIN)
        vm.selectModule(FinanceModule.PENDING)

        vm.refresh()

        assertEquals(listOf(FinanceModule.PENDING), gateway.fetches)
        assertFalse(vm.uiState.value.isRefreshing)
    }

    @Test
    fun expiredSessionResumesSameModuleAfterCaptchaLogin() = runTest {
        val gateway = FakeGateway(expireFirstFetch = true)
        val store = FakeStore(cookie = "expired")
        val vm = FinanceViewModel(gateway, store, CampusType.GUILIN)
        vm.selectModule(FinanceModule.PENDING)

        vm.refresh()
        assertTrue(vm.uiState.value.login.visible)
        assertEquals("data:image/jpeg;base64,abc", vm.uiState.value.login.captchaImage)

        vm.updateCaptcha("1234")
        vm.login()

        assertEquals(FinanceModule.PENDING, gateway.fetches.first())
        assertEquals(FinanceModule.entries.filterNot { it == FinanceModule.PENDING }.toSet(), gateway.fetches.drop(2).toSet())
        assertEquals("new-session", store.cookie)
        assertFalse(vm.uiState.value.login.visible)
    }

    @Test
    fun successfulLoginPreloadsEveryIndependentFinanceModule() = runTest {
        val gateway = FakeGateway()
        val vm = FinanceViewModel(gateway, FakeStore(), CampusType.GUILIN)

        vm.showLogin()
        vm.updateCaptcha("1234")
        vm.login()

        assertEquals(FinanceModule.entries.filterNot { it == FinanceModule.PENDING }.toSet(), gateway.fetches.toSet())
        assertEquals(FinanceModule.entries.filterNot { it == FinanceModule.PENDING }.size, gateway.fetches.size)
        assertTrue(vm.uiState.value.payloads.keys.containsAll(FinanceModule.entries.filterNot { it == FinanceModule.PENDING }))
        assertEquals("登录成功，财务数据已全部更新", vm.uiState.value.message)
    }

    @Test
    fun nanningNeverCreatesFinanceRequest() = runTest {
        val gateway = FakeGateway()
        val vm = FinanceViewModel(gateway, FakeStore(cookie = "session"), CampusType.NANNING)

        vm.refresh()

        assertTrue(vm.uiState.value.campusUnsupported)
        assertTrue(gateway.fetches.isEmpty())
    }

    @Test
    fun clearDataRemovesCredentialsSessionAndVisibleCache() = runTest {
        val store = FakeStore(cookie = "session").apply {
            saveModule(FinanceModule.OVERVIEW, CachedFinancePayload(FinancePayload.Items(emptyList()), 1L))
        }
        val vm = FinanceViewModel(FakeGateway(), store, CampusType.GUILIN)

        vm.clearData()

        assertEquals(FinanceCredentials(), store.credentials())
        assertEquals("", store.sessionCookie())
        assertTrue(vm.uiState.value.payloads.isEmpty())
    }

    @Test
    fun clearDataInvalidatesAnInFlightResponse() = runTest {
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<FinanceResponse>()
        val gateway = object : FinanceGateway {
            override suspend fun captcha() = FinanceCaptcha("image", "captcha")
            override suspend fun login(username: String, password: String, captcha: String, cookie: String) = FinanceResponse(cookie = "login")
            override suspend fun fetch(module: FinanceModule, cookie: String, page: Int, pageSize: Int): FinanceResponse {
                started.complete(Unit)
                return response.await()
            }
            override suspend fun ticketImage(cookie: String, chargeId: String, receiptNo: String) = FinanceResponse(cookie = cookie)
        }
        val store = FakeStore(cookie = "session")
        val vm = FinanceViewModel(gateway, store, CampusType.GUILIN)

        vm.refresh()
        started.await()
        vm.clearData()
        response.complete(FinanceResponse(FinancePayload.Items(listOf(FinanceItem("late", "旧响应"))), "late-session"))

        assertEquals("", store.sessionCookie())
        assertEquals(null, store.module(FinanceModule.OVERVIEW))
        assertTrue(vm.uiState.value.payloads.isEmpty())
    }

    @Test
    fun clearDataInvalidatesInFlightLoginPreloadResponses() = runTest {
        val started = CompletableDeferred<Unit>()
        val delayed = CompletableDeferred<FinanceResponse>()
        val gateway = object : FinanceGateway {
            override suspend fun captcha() = FinanceCaptcha("image", "captcha")
            override suspend fun login(username: String, password: String, captcha: String, cookie: String) = FinanceResponse(cookie = "login")
            override suspend fun fetch(module: FinanceModule, cookie: String, page: Int, pageSize: Int): FinanceResponse {
                if (module == FinanceModule.OTHER_PAYMENTS) {
                    started.complete(Unit)
                    return delayed.await()
                }
                return FinanceResponse(FinancePayload.Items(emptyList()), cookie)
            }
            override suspend fun ticketImage(cookie: String, chargeId: String, receiptNo: String) = FinanceResponse(cookie = cookie)
        }
        val store = FakeStore()
        val vm = FinanceViewModel(gateway, store, CampusType.GUILIN)

        vm.showLogin()
        vm.updateCaptcha("1234")
        vm.login()
        started.await()
        vm.clearData()
        delayed.complete(FinanceResponse(FinancePayload.Items(listOf(FinanceItem("late", "旧预取"))), "late-session"))
        advanceUntilIdle()

        assertEquals("", store.sessionCookie())
        assertTrue(vm.uiState.value.payloads.isEmpty())
    }

    @Test
    fun clearDataDuringResumedFetchPreventsLoginPreloadFromRestarting() = runTest {
        val resumed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fetches = mutableListOf<FinanceModule>()
        val gateway = object : FinanceGateway {
            override suspend fun captcha() = FinanceCaptcha("image", "captcha")
            override suspend fun login(username: String, password: String, captcha: String, cookie: String) = FinanceResponse(cookie = "login")
            override suspend fun fetch(module: FinanceModule, cookie: String, page: Int, pageSize: Int): FinanceResponse {
                fetches += module
                if (module == FinanceModule.OVERVIEW) {
                    resumed.complete(Unit)
                    release.await()
                }
                return FinanceResponse(FinancePayload.Items(emptyList()), cookie)
            }
            override suspend fun ticketImage(cookie: String, chargeId: String, receiptNo: String) = FinanceResponse(cookie = cookie)
        }
        val store = FakeStore()
        val vm = FinanceViewModel(gateway, store, CampusType.GUILIN)

        vm.showLogin()
        vm.updateCaptcha("1234")
        vm.login()
        resumed.await()
        vm.clearData()
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(FinanceModule.OVERVIEW), fetches)
        assertEquals("", store.sessionCookie())
        assertTrue(vm.uiState.value.payloads.isEmpty())
    }

    @Test
    fun failedResumedModuleIsRetriedByLoginPreloadBeforeReportingComplete() = runTest {
        var overviewAttempts = 0
        val gateway = object : FinanceGateway {
            override suspend fun captcha() = FinanceCaptcha("image", "captcha")
            override suspend fun login(username: String, password: String, captcha: String, cookie: String) = FinanceResponse(cookie = "login")
            override suspend fun fetch(module: FinanceModule, cookie: String, page: Int, pageSize: Int): FinanceResponse {
                if (module == FinanceModule.OVERVIEW && ++overviewAttempts == 1) throw java.io.IOException("first overview failed")
                return FinanceResponse(FinancePayload.Items(emptyList()), cookie)
            }
            override suspend fun ticketImage(cookie: String, chargeId: String, receiptNo: String) = FinanceResponse(cookie = cookie)
        }
        val vm = FinanceViewModel(gateway, FakeStore(), CampusType.GUILIN)

        vm.showLogin()
        vm.updateCaptcha("1234")
        vm.login()

        assertEquals(2, overviewAttempts)
        assertTrue(vm.uiState.value.payloads.containsKey(FinanceModule.OVERVIEW))
        assertEquals("登录成功，财务数据已全部更新", vm.uiState.value.message)
    }

    @Test
    fun sessionExpiryWhileResumingStopsLoginPreload() = runTest {
        val gateway = FakeGateway(expireFirstFetch = true)
        val store = FakeStore()
        val vm = FinanceViewModel(gateway, store, CampusType.GUILIN)

        vm.showLogin()
        vm.updateCaptcha("1234")
        vm.login()

        assertEquals(listOf(FinanceModule.OVERVIEW), gateway.fetches)
        assertEquals("", store.sessionCookie())
        assertTrue(vm.uiState.value.login.visible)
    }

    @Test
    fun sessionExpiryFromAnyPreloadedModuleReturnsToLogin() = runTest {
        val gateway = object : FinanceGateway {
            override suspend fun captcha() = FinanceCaptcha("image", "captcha")
            override suspend fun login(username: String, password: String, captcha: String, cookie: String) = FinanceResponse(cookie = "sid=login")
            override suspend fun fetch(module: FinanceModule, cookie: String, page: Int, pageSize: Int): FinanceResponse {
                if (module == FinanceModule.OTHER_PAYMENTS) throw FinanceFailure.SessionExpired()
                return FinanceResponse(FinancePayload.Items(emptyList()), "sid=overview")
            }
            override suspend fun ticketImage(cookie: String, chargeId: String, receiptNo: String) = FinanceResponse(cookie = cookie)
        }
        val store = FakeStore()
        val vm = FinanceViewModel(gateway, store, CampusType.GUILIN)

        vm.showLogin()
        vm.updateCaptcha("1234")
        vm.login()

        assertEquals("", store.sessionCookie())
        assertTrue(vm.uiState.value.login.visible)
        assertEquals("财务登录已失效，请重新登录", vm.uiState.value.message)
    }

    @Test
    fun overviewCookieRemainsAuthoritativeAfterParallelPreload() = runTest {
        val gateway = object : FinanceGateway {
            override suspend fun captcha() = FinanceCaptcha("image", "captcha")
            override suspend fun login(username: String, password: String, captcha: String, cookie: String) = FinanceResponse(cookie = "sid=login")
            override suspend fun fetch(module: FinanceModule, cookie: String, page: Int, pageSize: Int) = FinanceResponse(
                FinancePayload.Items(emptyList()),
                if (module == FinanceModule.OVERVIEW) "sid=overview" else "sid=stale-${module.key}"
            )
            override suspend fun ticketImage(cookie: String, chargeId: String, receiptNo: String) = FinanceResponse(cookie = cookie)
        }
        val store = FakeStore()
        val vm = FinanceViewModel(gateway, store, CampusType.GUILIN)

        vm.showLogin()
        vm.updateCaptcha("1234")
        vm.login()

        assertEquals("sid=overview", store.sessionCookie())
    }

    @Test
    fun failedLoadMoreResumeNeverFallsBackToRefreshingPageOne() = runTest {
        val pages = mutableListOf<Int>()
        var firstExpired = true
        val gateway = object : FinanceGateway {
            override suspend fun captcha() = FinanceCaptcha("image", "captcha")
            override suspend fun login(username: String, password: String, captcha: String, cookie: String) = FinanceResponse(cookie = "sid=login")
            override suspend fun fetch(module: FinanceModule, cookie: String, page: Int, pageSize: Int): FinanceResponse {
                if (module == FinanceModule.TRANSACTIONS) {
                    pages += page
                    if (firstExpired) {
                        firstExpired = false
                        throw FinanceFailure.SessionExpired()
                    }
                    throw java.io.IOException("page two unavailable")
                }
                return FinanceResponse(FinancePayload.Items(emptyList()), cookie)
            }
            override suspend fun ticketImage(cookie: String, chargeId: String, receiptNo: String) = FinanceResponse(cookie = cookie)
        }
        val store = FakeStore(cookie = "expired").apply {
            saveModule(FinanceModule.TRANSACTIONS, CachedFinancePayload(FinancePayload.Items(emptyList(), page = 1, total = 40, hasMore = true), 1L))
        }
        val vm = FinanceViewModel(gateway, store, CampusType.GUILIN)
        vm.selectModule(FinanceModule.TRANSACTIONS)

        vm.loadMore()
        vm.updateCaptcha("1234")
        vm.login()

        assertEquals(listOf(2, 2, 2), pages)
        assertEquals("登录成功，部分财务数据暂未同步", vm.uiState.value.message)
    }

    @Test
    fun expiredLoadMoreResumesTheSamePageAfterLogin() = runTest {
        val gateway = FakeGateway(expireFirstFetch = true)
        val store = FakeStore(cookie = "expired").apply {
            saveModule(
                FinanceModule.TRANSACTIONS,
                CachedFinancePayload(FinancePayload.Items(emptyList(), page = 1, total = 40, hasMore = true), 1L)
            )
        }
        val vm = FinanceViewModel(gateway, store, CampusType.GUILIN)
        vm.selectModule(FinanceModule.TRANSACTIONS)

        vm.loadMore()
        vm.updateCaptcha("1234")
        vm.login()

        assertEquals(2, gateway.fetchPages.first())
        assertEquals(2, gateway.fetchPages[1])
        assertTrue(gateway.fetchPages.drop(2).all { it == 1 })
    }

    @Test
    fun newestCaptchaKeepsItsMatchingCookieWhenRequestsFinishOutOfOrder() = runTest {
        val first = CompletableDeferred<FinanceCaptcha>()
        val second = CompletableDeferred<FinanceCaptcha>()
        val replies = ArrayDeque(listOf(first, second))
        val gateway = object : FinanceGateway {
            override suspend fun captcha() = replies.removeFirst().await()
            override suspend fun login(username: String, password: String, captcha: String, cookie: String) = FinanceResponse(cookie = cookie)
            override suspend fun fetch(module: FinanceModule, cookie: String, page: Int, pageSize: Int) = FinanceResponse(cookie = cookie)
            override suspend fun ticketImage(cookie: String, chargeId: String, receiptNo: String) = FinanceResponse(cookie = cookie)
        }
        val vm = FinanceViewModel(gateway, FakeStore(), CampusType.GUILIN)

        vm.showLogin()
        vm.refreshCaptcha()
        second.complete(FinanceCaptcha("new-image", "new-cookie"))
        first.complete(FinanceCaptcha("old-image", "old-cookie"))

        assertEquals("new-image", vm.uiState.value.login.captchaImage)
        assertEquals("new-cookie", vm.uiState.value.login.cookie)
    }

    private class FakeGateway(private var expireFirstFetch: Boolean = false) : FinanceGateway {
        val fetches = mutableListOf<FinanceModule>()
        val fetchPages = mutableListOf<Int>()
        override suspend fun captcha() = FinanceCaptcha("data:image/jpeg;base64,abc", "captcha-session")
        override suspend fun login(username: String, password: String, captcha: String, cookie: String) = FinanceResponse(cookie = "new-session")
        override suspend fun fetch(module: FinanceModule, cookie: String, page: Int, pageSize: Int): FinanceResponse {
            fetches += module
            fetchPages += page
            if (expireFirstFetch) {
                expireFirstFetch = false
                throw FinanceFailure.SessionExpired()
            }
            return FinanceResponse(FinancePayload.Items(emptyList()), cookie)
        }
        override suspend fun ticketImage(cookie: String, chargeId: String, receiptNo: String) =
            FinanceResponse(FinancePayload.TicketImage("data:image/jpeg;base64,image"), cookie)
    }

    private class FakeStore(
        private var credentialsValue: FinanceCredentials = FinanceCredentials("2024001", "password"),
        var cookie: String = ""
    ) : FinanceStorage {
        private val modules = mutableMapOf<FinanceModule, CachedFinancePayload>()
        override fun credentials() = credentialsValue
        override fun saveCredentials(value: FinanceCredentials) { credentialsValue = value }
        override fun sessionCookie() = cookie
        override fun saveSessionCookie(value: String) { cookie = value }
        override fun clearSession() { cookie = "" }
        override fun module(module: FinanceModule) = modules[module]
        override fun saveModule(module: FinanceModule, value: CachedFinancePayload) { modules[module] = value }
        override fun clearAll() { modules.clear(); cookie = ""; credentialsValue = FinanceCredentials() }
    }
}
