# Android Finance Menu Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a read-only Android finance menu that directly queries the Guilin finance website, caches each module independently, and ships as version 0.18.0.

**Architecture:** A finance-only OkHttp service owns website requests and cookies, a parser converts upstream JSON into stable Kotlin models, and an encrypted store separates credentials/session from module caches. A StateFlow ViewModel exposes the approved A1 Compose UI and retries an interrupted refresh after captcha login.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, OkHttp 5, org.json, EncryptedSharedPreferences, coroutines/StateFlow, JUnit 4.

## Global Constraints

- Only Guilin campus may create finance network requests; Nanning shows an unsupported placeholder.
- The Android app connects directly to `https://cwjf.glut.edu.cn/`; it does not call the Worker.
- The app is read-only and opens payment/password-reset pages in the system browser.
- Credentials and finance cookies are encrypted and isolated from academic and fitness storage.
- Switching tabs reads cache only; refresh requests only the active module.
- Preserve old cache on network, timeout, login, or parse failure.
- Never log passwords, cookies, captcha values, or complete finance responses.
- Use `#244F46`, `#397267`, `#BD573E`, and `#F5F1E9` for the approved A1 design.
- Set `versionCode = 114` and `versionName = "0.18.0"`.
- Build a local Release APK; do not run `publishUpdate`.

---

### Task 1: Stable finance models and upstream parser

**Files:**
- Create: `app/src/main/java/com/glut/schedule/data/model/FinanceModels.kt`
- Create: `app/src/main/java/com/glut/schedule/service/finance/FinanceParser.kt`
- Create: `app/src/test/java/com/glut/schedule/FinanceParserTest.kt`

**Interfaces:**
- Produces: `FinanceModule`, `FinanceSummary`, `FinanceItem`, `FinanceOverview`, `FinanceTableSection`, `FinancePayload`, and `FinanceParser.parse(module, value)`.
- Consumes: `org.json.JSONObject` and `org.json.JSONArray` returned by Task 2.

- [ ] **Step 1: Write failing parser tests for overview, list aliases, tickets, and dynamic credit tables**

```kotlin
class FinanceParserTest {
    private val parser = FinanceParser()

    @Test fun overviewKeepsOnlySummaryAndPendingFields() {
        val personal = JSONObject("""{"ReceivableMoney":"5600","PayMoney":"4320","SlurMoney":"1280"}""")
        val pending = JSONObject("""{"items":[{"xm":"住宿费","qianfei":"1200"}]}""")
        val value = JSONObject().put("personal", personal).put("pending", pending)
        val payload = parser.parse(FinanceModule.OVERVIEW, value) as FinancePayload.Overview
        assertEquals("1280", payload.value.summary.outstandingTotal)
        assertEquals("住宿费", payload.value.pendingItems.single().name)
    }

    @Test fun creditTablesKeepDynamicColumnOrder() {
        val value = JSONObject("""{"Title_1":"学分专业标准","Data_1":[{"类别":"必修","要求":"42"}]}""")
        val payload = parser.parse(FinanceModule.CREDIT_SETTLEMENT, value) as FinancePayload.Tables
        assertEquals(listOf("类别", "要求"), payload.sections.single().columns)
        assertEquals(listOf("必修", "42"), payload.sections.single().rows.single())
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails because finance types do not exist**

Run: `./gradlew.bat testDebugUnitTest --tests "com.glut.schedule.FinanceParserTest"`

Expected: compilation failure for unresolved `FinanceParser`/`FinanceModule`.

- [ ] **Step 3: Add minimal domain types with explicit module grouping**

```kotlin
enum class FinanceModule(val key: String, val group: FinanceGroup, val label: String) {
    OVERVIEW("overview", FinanceGroup.OVERVIEW, "概览"),
    PENDING("pending", FinanceGroup.PAYMENT, "待缴项目"),
    OTHER_PAYMENTS("otherPayments", FinanceGroup.PAYMENT, "其他缴费"),
    FEE_PROJECTS("feeProjects", FinanceGroup.PAYMENT, "收费项目"),
    TRANSACTIONS("transactions", FinanceGroup.RECORDS, "交易记录"),
    PAYMENT_DETAILS("paymentDetails", FinanceGroup.RECORDS, "缴费明细"),
    COURSE_RECORDS("courseRecords", FinanceGroup.RECORDS, "选课记录"),
    ELECTRONIC_TICKETS("electronicTickets", FinanceGroup.RECORDS, "电子票据"),
    CREDIT_SETTLEMENT("creditSettlement", FinanceGroup.CREDIT, "学分结算")
}

sealed interface FinancePayload {
    data class Overview(val value: FinanceOverview) : FinancePayload
    data class Items(val values: List<FinanceItem>, val hasMore: Boolean = false) : FinancePayload
    data class Tables(val sections: List<FinanceTableSection>) : FinancePayload
    data class TicketImage(val dataUrl: String) : FinancePayload
}
```

- [ ] **Step 4: Port the proven normalization aliases from `worker-get-schedule/src/finance.js` into focused parser functions**

Implement `parseOverview`, `parsePending`, `parseOtherPayments`, `parseTransactions`, `parseFeeProjects`, `parsePaymentDetails`, `parseCourseRecords`, `parseTickets`, and `parseCreditSettlement`. Use `optText(vararg keys)` and `jsonList()` helpers so aliases are defined once.

- [ ] **Step 5: Run the parser tests and commit**

Run: `./gradlew.bat testDebugUnitTest --tests "com.glut.schedule.FinanceParserTest"`

Expected: all `FinanceParserTest` tests pass.

```powershell
git add app/src/main/java/com/glut/schedule/data/model/FinanceModels.kt app/src/main/java/com/glut/schedule/service/finance/FinanceParser.kt app/src/test/java/com/glut/schedule/FinanceParserTest.kt
git commit -m "实现安卓财务数据解析"
```

### Task 2: Direct website client, cookie isolation, and login protocol

**Files:**
- Create: `app/src/main/java/com/glut/schedule/service/finance/FinanceApiService.kt`
- Create: `app/src/test/java/com/glut/schedule/FinanceApiServiceTest.kt`

**Interfaces:**
- Consumes: `FinanceModule`, `FinancePayload`, and `FinanceParser` from Task 1.
- Produces: `FinanceGateway`, `FinanceApiService`, `FinanceCaptcha`, `FinanceResponse`, and `FinanceFailure`.

- [ ] **Step 1: Write failing tests around pure request construction and session-expiry recognition**

```kotlin
@Test fun moduleRequestMatchesOfficialWebsiteContract() {
    assertEquals(
        mapOf("method" to "getorderlist", "stuid" to "1", "start" to "1", "pagesize" to "20"),
        FinanceRequests.module(FinanceModule.TRANSACTIONS, page = 2, pageSize = 20)
    )
}

@Test fun userNoLoginIsAlwaysSessionExpired() {
    assertTrue(FinanceResponses.isSessionExpired(" user_no_login "))
    assertTrue(FinanceResponses.isSessionExpired("USERNOLOGIN"))
}
```

- [ ] **Step 2: Run the test and verify the request helpers are missing**

Run: `./gradlew.bat testDebugUnitTest --tests "com.glut.schedule.FinanceApiServiceTest"`

Expected: unresolved `FinanceRequests` and `FinanceResponses`.

- [ ] **Step 3: Define a testable gateway and stable failures**

```kotlin
interface FinanceGateway {
    suspend fun captcha(): FinanceCaptcha
    suspend fun login(username: String, password: String, captcha: String, cookie: String): FinanceResponse
    suspend fun fetch(module: FinanceModule, cookie: String, page: Int = 1, pageSize: Int = 20): FinanceResponse
    suspend fun ticketImage(cookie: String, chargeId: String, receiptNo: String): FinanceResponse
}

sealed class FinanceFailure(message: String) : IOException(message) {
    class SessionExpired : FinanceFailure("财务登录已失效，请重新登录")
    class CaptchaInvalid(message: String) : FinanceFailure(message)
    class CredentialsInvalid(message: String) : FinanceFailure(message)
    class Parse : FinanceFailure("财务系统返回格式异常")
    class Upstream(message: String) : FinanceFailure(message)
}
```

- [ ] **Step 4: Implement the exact website protocol with a finance-only OkHttp client**

Use these endpoints and forms:

```kotlin
private const val BASE = "https://cwjf.glut.edu.cn"
private const val LOGIN = "$BASE/home/login"
private const val INDEX = "$BASE/home/index"
private const val INTERFACE = "$BASE/interface/index"

// Captcha: GET /home/login -> POST loginauthtype -> POST usernameinputtips
// -> GET /interface/getVerifyCode?<timestamp>
// Login form: sid, base64(passWord), verifycode, ismobile=0
// Module forms: exact keys returned by FinanceRequests.module(...)
```

Follow redirects only while the hostname remains `cwjf.glut.edu.cn`. Merge `Set-Cookie` values by cookie name and send AJAX headers (`Referer`, `X-Requested-With`, JSON `Accept`) on interface calls. Never install this CookieJar globally.

- [ ] **Step 5: Parse the website envelope before calling `FinanceParser`**

Accept `state == "200"` or `success == true`, unwrap `data`, parse nested JSON strings, and convert login-page redirects, HTML bodies, and `usernologin` sentinels into `FinanceFailure.SessionExpired`.

- [ ] **Step 6: Run service tests and commit**

Run: `./gradlew.bat testDebugUnitTest --tests "com.glut.schedule.FinanceApiServiceTest"`

Expected: request, cookie, envelope, and expiry tests pass without live network access.

```powershell
git add app/src/main/java/com/glut/schedule/service/finance/FinanceApiService.kt app/src/test/java/com/glut/schedule/FinanceApiServiceTest.kt
git commit -m "接入安卓财务官网直连"
```

### Task 3: Encrypted credentials and independently keyed cache

**Files:**
- Create: `app/src/main/java/com/glut/schedule/service/finance/FinanceStore.kt`
- Create: `app/src/main/java/com/glut/schedule/service/finance/FinanceCacheCodec.kt`
- Create: `app/src/test/java/com/glut/schedule/FinanceCacheCodecTest.kt`

**Interfaces:**
- Consumes: finance domain models from Task 1.
- Produces: `FinanceStorage` interface and Android `FinanceStore` implementation.

- [ ] **Step 1: Write a failing cache round-trip and module-isolation test**

```kotlin
@Test fun updatingOneModuleDoesNotReplaceAnother() {
    val cache = FakeStringPreferences()
    val store = TestFinanceStore(cache)
    store.saveModule(FinanceModule.PENDING, pendingPayload, 100L)
    store.saveModule(FinanceModule.TRANSACTIONS, transactionPayload, 200L)
    assertEquals(pendingPayload, store.getModule(FinanceModule.PENDING)?.payload)
    assertEquals(transactionPayload, store.getModule(FinanceModule.TRANSACTIONS)?.payload)
}
```

- [ ] **Step 2: Run and verify failure because no cache codec/store exists**

Run: `./gradlew.bat testDebugUnitTest --tests "com.glut.schedule.FinanceCacheCodecTest"`

- [ ] **Step 3: Implement the storage contract**

```kotlin
interface FinanceStorage {
    fun credentials(): FinanceCredentials
    fun saveCredentials(value: FinanceCredentials)
    fun sessionCookie(): String
    fun saveSessionCookie(value: String)
    fun clearSession()
    fun module(module: FinanceModule): CachedFinancePayload?
    fun saveModule(module: FinanceModule, value: CachedFinancePayload)
    fun clearAll()
}
```

`FinanceStore` must use `EncryptedSharedPreferences` named `finance_secure_data` for username/password/cookie and ordinary private preferences named `finance_cache` for one JSON value plus timestamp per `FinanceModule.key`.

- [ ] **Step 4: Implement explicit JSON encode/decode for every sealed payload subtype**

Store a schema version and discard only incompatible finance cache entries. Do not use Java serialization or reflection.

- [ ] **Step 5: Run tests and commit**

Run: `./gradlew.bat testDebugUnitTest --tests "com.glut.schedule.FinanceCacheCodecTest"`

```powershell
git add app/src/main/java/com/glut/schedule/service/finance/FinanceStore.kt app/src/main/java/com/glut/schedule/service/finance/FinanceCacheCodec.kt app/src/test/java/com/glut/schedule/FinanceCacheCodecTest.kt
git commit -m "增加财务加密存储与独立缓存"
```

### Task 4: ViewModel state machine and interrupted-refresh recovery

**Files:**
- Create: `app/src/main/java/com/glut/schedule/ui/pages/FinanceViewModel.kt`
- Create: `app/src/test/java/com/glut/schedule/FinanceViewModelTest.kt`

**Interfaces:**
- Consumes: `FinanceGateway` and `FinanceStorage`.
- Produces: `FinanceUiState`, `FinanceViewModel`, and `FinanceViewModelFactory` for Task 5/6.

- [ ] **Step 1: Write failing coroutine tests for cache-only switching and exact refresh**

```kotlin
@Test fun switchingModuleReadsCacheWithoutNetwork() = runTest {
    val gateway = FakeFinanceGateway()
    val vm = FinanceViewModel(gateway, store, CampusType.GUILIN, StandardTestDispatcher(testScheduler))
    vm.selectModule(FinanceModule.TRANSACTIONS)
    advanceUntilIdle()
    assertTrue(gateway.fetches.isEmpty())
}

@Test fun refreshRequestsOnlyActiveModule() = runTest {
    val vm = newViewModel(active = FinanceModule.PENDING)
    vm.refresh()
    advanceUntilIdle()
    assertEquals(listOf(FinanceModule.PENDING), gateway.fetches.map { it.module })
}
```

- [ ] **Step 2: Add failing tests for session expiry and resume after captcha login**

The fake gateway first throws `FinanceFailure.SessionExpired`, then returns a captcha, then succeeds after `login`. Assert the pending module is fetched exactly once after login and unrelated modules are never fetched.

- [ ] **Step 3: Implement immutable state and one-request-at-a-time guards**

```kotlin
data class FinanceUiState(
    val campusUnsupported: Boolean = false,
    val group: FinanceGroup = FinanceGroup.OVERVIEW,
    val module: FinanceModule = FinanceModule.OVERVIEW,
    val payloads: Map<FinanceModule, CachedFinancePayload> = emptyMap(),
    val isRefreshing: Boolean = false,
    val message: String = "",
    val login: FinanceLoginState = FinanceLoginState()
)
```

Keep `pendingRefresh: FinanceModule?` private. `selectGroup`/`selectModule` update state from store only. `refresh` uses the saved session or opens captcha. A successful login stores credentials/session and resumes `pendingRefresh`.

- [ ] **Step 4: Implement pagination and ticket image loading without cross-module writes**

Only append when the response belongs to the same module and requested page. Use monotonically increasing request IDs so stale results cannot replace current state.

- [ ] **Step 5: Run ViewModel tests and commit**

Run: `./gradlew.bat testDebugUnitTest --tests "com.glut.schedule.FinanceViewModelTest"`

```powershell
git add app/src/main/java/com/glut/schedule/ui/pages/FinanceViewModel.kt app/src/test/java/com/glut/schedule/FinanceViewModelTest.kt
git commit -m "实现财务页面状态与独立刷新"
```

### Task 5: Approved A1 Compose screen

**Files:**
- Create: `app/src/main/java/com/glut/schedule/ui/pages/FinanceScreen.kt`
- Create: `app/src/test/java/com/glut/schedule/FinanceUiContractTest.kt`

**Interfaces:**
- Consumes: `FinanceUiState` and ViewModel event methods from Task 4.
- Produces: `FinanceScreen(viewModel, onTableGestureActive)`.

- [ ] **Step 1: Add a source contract test for the approved labels, colors, and external URLs**

```kotlin
@Test fun screenKeepsApprovedA1Contract() {
    val source = financeScreenSource()
    assertTrue(source.contains("概览") && source.contains("缴费") && source.contains("记录") && source.contains("学分"))
    assertTrue(source.contains("0xFF244F46") && source.contains("0xFFBD573E") && source.contains("0xFFF5F1E9"))
    assertTrue(source.contains("https://cwjf.glut.edu.cn/home/login"))
    assertTrue(source.contains("https://cwjf.glut.edu.cn/home/mmcz"))
}
```

- [ ] **Step 2: Implement the page shell and A1 overview**

Use a `Column`, main text tabs, a green gradient summary card, cached timestamp, and `LazyColumn` pending cards. Keep the top app bar in `MainActivity`; do not duplicate it inside the screen.

- [ ] **Step 3: Implement payment/record chips, cards, pagination, and detail bottom sheet**

The payment handoff opens the system browser. Electronic ticket detail calls `loadTicketImage` and displays decoded base64 without exposing ticket URLs.

- [ ] **Step 4: Implement the two-axis credit table and gesture ownership callback**

Wrap the table in horizontal and vertical scroll states and call `onTableGestureActive(true)` only while a horizontal table gesture is active; always reset on cancellation/disposal.

- [ ] **Step 5: Implement secure captcha login dialog and Nanning placeholder**

Apply `FLAG_SECURE` while the dialog is visible, prefill credentials, allow password visibility toggle, refresh captcha on image tap, and open the reset URL in the browser.

- [ ] **Step 6: Run UI contract test and compile**

Run: `./gradlew.bat testDebugUnitTest --tests "com.glut.schedule.FinanceUiContractTest"`

Run: `./gradlew.bat compileDebugKotlin`

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/com/glut/schedule/ui/pages/FinanceScreen.kt app/src/test/java/com/glut/schedule/FinanceUiContractTest.kt
git commit -m "实现安卓财务页面"
```

### Task 6: Application container, drawer, and lifecycle integration

**Files:**
- Modify: `app/src/main/java/com/glut/schedule/ScheduleApplication.kt`
- Modify: `app/src/main/java/com/glut/schedule/ui/navigation/DrawerItem.kt`
- Modify: `app/src/main/java/com/glut/schedule/MainActivity.kt`
- Test: `app/src/test/java/com/glut/schedule/FinanceUiContractTest.kt`

**Interfaces:**
- Consumes: `FinanceApiService`, `FinanceStore`, `FinanceViewModelFactory`, and `FinanceScreen`.
- Produces: a reachable drawer destination and refresh action.

- [ ] **Step 1: Extend the contract test to require container, drawer, and destination registration**

Assert `DrawerItem.Finance`, `container.financeApiService`, `container.financeStore`, the Finance ViewModel factory, the current-module refresh action, and `FinanceScreen` are present.

- [ ] **Step 2: Register finance dependencies in `AppContainer`**

```kotlin
val financeStore = FinanceStore(application)
val financeParser = FinanceParser()
val financeApiService = FinanceApiService(financeParser)
```

- [ ] **Step 3: Add the drawer item and MainActivity destination**

Add `Finance("财务", Icons.Outlined.AccountBalanceWallet)` after `FitnessScore`, include it in the score/service drawer group, create the ViewModel from current campus state, add refresh action, and render `FinanceScreen`.

- [ ] **Step 4: Share gesture suppression with fitness and finance tables**

Rename the local `fitnessTableGestureActive` state to `drawerGestureBlocked` and let either table screen update it. This is the only adjacent refactor permitted in this task.

- [ ] **Step 5: Run tests and debug build, then commit**

Run: `./gradlew.bat testDebugUnitTest`

Run: `./gradlew.bat assembleDebug`

```powershell
git add app/src/main/java/com/glut/schedule/ScheduleApplication.kt app/src/main/java/com/glut/schedule/ui/navigation/DrawerItem.kt app/src/main/java/com/glut/schedule/MainActivity.kt app/src/test/java/com/glut/schedule/FinanceUiContractTest.kt
git commit -m "接入安卓财务菜单导航"
```

### Task 7: Version 0.18.0, full verification, and local Release APK

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: About page only if it contains a hard-coded app version.

**Interfaces:**
- Consumes: all prior tasks.
- Produces: verified local `0.18.0` Release APK.

- [ ] **Step 1: Update version metadata**

```kotlin
versionCode = 114
versionName = "0.18.0"
```

- [ ] **Step 2: Run complete unit tests**

Run: `$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot'; ./gradlew.bat testDebugUnitTest`

Expected: BUILD SUCCESSFUL with all tests passing.

- [ ] **Step 3: Build both APK variants**

Run: `./gradlew.bat assembleDebug assembleRelease`

Expected: `app/build/outputs/apk/debug/app-debug.apk` and a release APK containing `0.18.0` are created.

- [ ] **Step 4: Inspect the final repository state and APK metadata**

Run: `git diff --check`

Run: `git status --short`

Run: `apkanalyzer manifest version-name <release-apk>` when available; otherwise inspect the Gradle output and generated APK filename.

- [ ] **Step 5: Commit version metadata**

```powershell
git add app/build.gradle.kts
git commit -m "发布安卓端 0.18.0"
```

- [ ] **Step 6: Hand off manual device checks**

Verify on a real device: captcha refresh, login, cached relaunch, active-module refresh, pagination, ticket image preview, credit table gestures, browser handoff, and Nanning placeholder. Do not run `publishUpdate` without a separate explicit request.
