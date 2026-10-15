package com.glut.schedule

import android.graphics.Bitmap
import com.glut.schedule.data.settings.ImportSource
import com.glut.schedule.service.academic.AcademicLoginResult
import com.glut.schedule.service.academic.AcademicSessionAuthMethod
import com.glut.schedule.service.academic.CaptchaLoginSession
import com.glut.schedule.service.academic.RenewalCredentialReader
import com.glut.schedule.service.academic.RenewalSessionWriter
import com.glut.schedule.service.academic.RenewalUiState
import com.glut.schedule.service.academic.RenewedSession
import com.glut.schedule.service.academic.SessionRenewalCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SessionRenewalCoordinator` 的状态机契约。
 *
 * 这里测的全部是「没有哪个单测能替你发现、但真机上用户一定会撞上」的行为：
 * 单飞（不弹两次窗）、取消（返回 false，调用方据此保留缓存）、以及**连续两次调用都要弹**
 * （不拿一个可能又失效的会话去搪塞用户）。取数与写库在真机上要靠 `CaptchaLoginSession`
 * 打真实门户，所以全部用手写 fake。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionRenewalCoordinatorTest {

    @Test
    fun nanningEntersAwaitingCaptchaAndSucceedsAfterSubmit() = runTest {
        val captchaLogin = FakeCaptchaLoginSession()
        val store = FakeSessionStore()
        val coordinator = coordinator(captchaLogin, store, ImportSource.NANNING)

        val awaiting = async { coordinator.ensureFreshSession() }
        advanceUntilIdle()

        val state = coordinator.state.value as RenewalUiState.AwaitingCaptcha
        // 「取图成功」这一支必须与「取图失败」分得开：后者会把原因写进 error。
        assertEquals("", state.error)
        assertFalse(awaiting.isCompleted)

        coordinator.submit(captcha = "1234")
        advanceUntilIdle()

        assertTrue(awaiting.await())
        assertEquals("cookie-after-renew", store.cookie)
        assertTrue(coordinator.state.value is RenewalUiState.Idle)
    }

    @Test
    fun submitFailureKeepsTheDialogOpenWithAnError() = runTest {
        val coordinator = coordinator(
            FakeCaptchaLoginSession(submitSucceeds = false), FakeSessionStore(), ImportSource.NANNING
        )

        val awaiting = async { coordinator.ensureFreshSession() }
        advanceUntilIdle()
        coordinator.submit(captcha = "0000")
        advanceUntilIdle()

        val state = coordinator.state.value as RenewalUiState.AwaitingCaptcha
        assertTrue(state.error.isNotBlank())
        assertFalse("弹窗必须留在原地", awaiting.isCompleted)

        // 收尾：把一直开着的等待取消掉，否则 runTest 会因「子协程未结束」报错。
        coordinator.cancel()
        advanceUntilIdle()
        assertFalse(awaiting.await())
    }

    @Test
    fun cancelReturnsFalseAndResetsState() = runTest {
        val coordinator = coordinator(FakeCaptchaLoginSession(), FakeSessionStore(), ImportSource.NANNING)

        val awaiting = async { coordinator.ensureFreshSession() }
        advanceUntilIdle()
        coordinator.cancel()
        advanceUntilIdle()

        assertFalse(awaiting.await())
        assertTrue(coordinator.state.value is RenewalUiState.Idle)
    }

    @Test
    fun cancelledCaptchaRequestCannotReopenTheDialogAfterItCompletes() = runTest {
        val gate = CompletableDeferred<Unit>()
        val coordinator = coordinator(
            FakeCaptchaLoginSession(requestGate = gate), FakeSessionStore(), ImportSource.NANNING
        )

        val awaiting = async { coordinator.ensureFreshSession() }
        runCurrent()
        assertTrue(coordinator.state.value is RenewalUiState.Busy)

        coordinator.cancel()
        assertFalse(awaiting.await())
        assertTrue(coordinator.state.value is RenewalUiState.Idle)

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue("取消后的旧取图不得重新打开验证码弹窗", coordinator.state.value is RenewalUiState.Idle)
    }

    @Test
    fun concurrentCallsShareOneDialogAndOneResult() = runTest {
        val captchaLogin = FakeCaptchaLoginSession()
        val coordinator = coordinator(captchaLogin, FakeSessionStore(), ImportSource.NANNING)

        val a = async { coordinator.ensureFreshSession() }
        val b = async { coordinator.ensureFreshSession() }
        advanceUntilIdle()

        assertEquals("只应取一次验证码", 1, captchaLogin.requestCount)

        coordinator.submit(captcha = "1234")
        advanceUntilIdle()

        assertTrue(a.await())
        assertTrue(b.await())
    }

    @Test
    fun guilinUsesSilentLoginAndNeverShowsADialog() = runTest {
        val store = FakeSessionStore()
        val coordinator = coordinator(
            FakeCaptchaLoginSession(), store, ImportSource.GUILIN,
            silentLogin = { AcademicLoginResult.Success("cookie-from-silent") }
        )

        assertTrue(coordinator.ensureFreshSession())
        assertTrue(coordinator.state.value is RenewalUiState.Idle)
        assertEquals("cookie-from-silent", store.cookie)
    }

    @Test
    fun guilinSilentLoginFailureReturnsFalseWithoutDialog() = runTest {
        val coordinator = coordinator(
            FakeCaptchaLoginSession(), FakeSessionStore(), ImportSource.GUILIN,
            silentLogin = { AcademicLoginResult.MissingCredentials }
        )

        assertFalse(coordinator.ensureFreshSession())
        assertTrue(coordinator.state.value is RenewalUiState.Idle)
    }

    @Test
    fun guilinWechatSessionUsesSavedWebSessionInsteadOfPasswordLogin() = runTest {
        val store = FakeSessionStore()
        val coordinator = coordinator(
            captchaLogin = FakeCaptchaLoginSession(),
            store = store,
            source = ImportSource.GUILIN,
            currentSessionAuthMethod = { AcademicSessionAuthMethod.WECHAT_SSO },
            silentLogin = { error("微信会话不能退回到账号密码登录") },
            silentWechatLogin = { AcademicLoginResult.Success("cookie-from-wechat") }
        )

        assertTrue(coordinator.ensureFreshSession())
        assertEquals("cookie-from-wechat", store.cookie)
        assertTrue(coordinator.state.value is RenewalUiState.Idle)
    }

    @Test
    fun expiredWechatSessionRequestsInteractiveWechatLogin() = runTest {
        val coordinator = coordinator(
            captchaLogin = FakeCaptchaLoginSession(),
            store = FakeSessionStore(),
            source = ImportSource.GUILIN,
            currentSessionAuthMethod = { AcademicSessionAuthMethod.WECHAT_SSO },
            silentWechatLogin = { AcademicLoginResult.CaptchaOrInteractiveLoginRequired }
        )

        assertFalse(coordinator.ensureFreshSession())
        assertTrue(coordinator.state.value is RenewalUiState.WechatReauthenticationRequired)
    }

    @Test
    fun captchaFetchFailureShowsErrorAndAllowsRetry() = runTest {
        val captchaLogin = FakeCaptchaLoginSession(requestSucceeds = false)
        val coordinator = coordinator(captchaLogin, FakeSessionStore(), ImportSource.NANNING)

        val awaiting = async { coordinator.ensureFreshSession() }
        advanceUntilIdle()

        val state = coordinator.state.value as RenewalUiState.AwaitingCaptcha
        assertTrue("取图失败要在弹窗里说明", state.error.isNotBlank())
        assertFalse(awaiting.isCompleted)

        // 再点刷新应当重试
        coordinator.refreshCaptcha()
        advanceUntilIdle()
        assertEquals(2, captchaLogin.requestCount)

        // 收尾：同 submitFailureKeepsTheDialogOpenWithAnError，取消释放等待。
        coordinator.cancel()
        advanceUntilIdle()
        assertFalse(awaiting.await())
    }

    @Test
    fun secondCallAfterASuccessStillPrompts() = runTest {
        val coordinator = coordinator(FakeCaptchaLoginSession(), FakeSessionStore(), ImportSource.NANNING)

        val first = async { coordinator.ensureFreshSession() }
        advanceUntilIdle()
        coordinator.submit(captcha = "1234")
        advanceUntilIdle()
        assertTrue(first.await())

        val second = async { coordinator.ensureFreshSession() }
        advanceUntilIdle()
        assertTrue("第二次仍应弹窗", coordinator.state.value is RenewalUiState.AwaitingCaptcha)
        coordinator.cancel()
        advanceUntilIdle()
        assertFalse(second.await())
    }

    @Test
    fun missingStoredCredentialsRequiresReturningToTheImportPage() = runTest {
        val captchaLogin = FakeCaptchaLoginSession()
        val coordinator = coordinator(captchaLogin, FakeSessionStore(username = "", password = ""), ImportSource.NANNING)

        assertFalse(coordinator.ensureFreshSession())
        assertEquals(0, captchaLogin.requestCount)
        assertTrue(coordinator.state.value is RenewalUiState.ImportLoginRequired)
    }

    @Test
    fun busyKeepsTheFormSoTheDialogIsNotUnmounted() = runTest {
        // 取图与提交都会先把状态置为 Busy。若 Busy 不带表单内容，界面就只能把弹窗整个卸掉：
        // 用户点完「确认登录」会看到弹窗凭空消失好几秒（南宁要串行跑验证码校验、登录、
        // 重定向链、框架页验证），既没有进度反馈、又连取消都按不了；
        // 所以 Busy 必须把刚才那张验证码表单一起带上。
        val coordinator = coordinator(FakeCaptchaLoginSession(), FakeSessionStore(), ImportSource.NANNING)

        val awaiting = async { coordinator.ensureFreshSession() }
        advanceUntilIdle()
        coordinator.submit(captcha = "1234")

        val busy = coordinator.state.value as RenewalUiState.Busy
        assertNotNull("Busy 期间界面要能继续显示表单，否则弹窗被卸载", busy.form)

        advanceUntilIdle()
        assertTrue(awaiting.await())
    }

    @Test
    fun renewalAlsoWritesBackTheStudentNumber() = runTest {
        // 「学号为空」本身就被算作会话失效的一种（ScheduleViewModel 的 sessionLooksDead）。
        // 若续期成功后只重读 cookie、不重读学号，重放仍拿那个空学号去请求，必然再失败一次——
        // 用户认真输完验证码，什么都没换来。学号在 GraduateLoginSession.context 里是现成的，
        // 协调器必须一并写回，否则调用方「重读学号」也读不到新值。
        val captchaLogin = FakeCaptchaLoginSession(
            renewedSession = RenewedSession(
                cookie = "cookie-after-renew",
                campusBaseUrl = "https://yjsy.example",
                studentNumber = "2026000001"
            )
        )
        val store = FakeSessionStore(username = "2026000001")
        val coordinator = coordinator(captchaLogin, store, ImportSource.GRADUATE)

        val awaiting = async { coordinator.ensureFreshSession() }
        advanceUntilIdle()
        coordinator.submit(captcha = "1234")
        advanceUntilIdle()

        assertTrue(awaiting.await())
        assertEquals("续期必须把学号写回，否则重放还是空学号", "2026000001", store.studentNumber)
    }

    // ---- 手写 fake（本仓库既有测试不引 mock 库）----

    /**
     * 组装被测协调器。
     *
     * [SessionRenewalCoordinator] 的 `scope` 用测试作用域本身（`this`）而**不是** `backgroundScope`：
     * `backgroundScope` 里的协程不会被 [advanceUntilIdle] 推进，协调器内部 `launch` 出去的取图/提交
     * 就永远不会执行——症状是状态一直停在 `Idle`、`requestCount` 恒为 0。
     * 因此「等用户输入」的等待协程也放在前台，由各用例自己在收尾时 [SessionRenewalCoordinator.cancel] 释放。
     */
    private fun TestScope.coordinator(
        captchaLogin: CaptchaLoginSession,
        store: FakeSessionStore,
        source: ImportSource,
        currentSessionAuthMethod: suspend () -> AcademicSessionAuthMethod = {
            AcademicSessionAuthMethod.PASSWORD
        },
        silentLogin: suspend () -> AcademicLoginResult = {
            AcademicLoginResult.Success("cookie-from-silent")
        },
        silentWechatLogin: suspend () -> AcademicLoginResult = {
            AcademicLoginResult.CaptchaOrInteractiveLoginRequired
        }
    ) = SessionRenewalCoordinator(
        sessionWriter = store,
        credentialReader = store,
        captchaLogin = captchaLogin,
        currentSource = { source },
        silentLogin = silentLogin,
        currentSessionAuthMethod = currentSessionAuthMethod,
        silentWechatLogin = silentWechatLogin,
        scope = this
    )

    private class FakeCaptchaLoginSession(
        private val submitSucceeds: Boolean = true,
        private val requestSucceeds: Boolean = true,
        private val requestGate: CompletableDeferred<Unit>? = null,
        private val renewedSession: RenewedSession =
            RenewedSession("cookie-after-renew", AcademicLoginResult.NANNING_URL)
    ) : CaptchaLoginSession() {
        /** 取图次数：首次取图与「换一张」都算一次。 */
        var requestCount = 0
            private set

        /** 最后一次提交用的账号，用来钉死「用的是用户当场填的那个，而不是本地存的」。 */
        var lastUsername: String = ""
            private set

        override suspend fun requestCaptcha(source: ImportSource): Result<Bitmap> {
            requestGate?.await()
            requestCount++
            return if (requestSucceeds) successWithoutCreatingABitmap() else networkFailure()
        }

        override suspend fun refreshCaptcha(source: ImportSource): Result<Bitmap> {
            requestCount++
            return if (requestSucceeds) successWithoutCreatingABitmap() else networkFailure()
        }

        override suspend fun submit(
            source: ImportSource, username: String, password: String, captcha: String
        ): Result<RenewedSession> {
            lastUsername = username
            return if (submitSucceeds) {
                Result.success(renewedSession)
            } else {
                Result.failure(IllegalStateException("验证码或密码错误"))
            }
        }

        /**
         * JVM 单测里 `android.graphics.Bitmap` 的方法全是 stub，调用即抛，造不出真图。
         *
         * 协调器只按 `Result` 的成败分支决定走向，位图本身**只透传**给界面状态、从不读它的像素，
         * 所以这里给 null 即可（`Bitmap` 是 Java 平台类型，`Result<Bitmap>` 允许持有 null）。
         */
        private fun successWithoutCreatingABitmap(): Result<Bitmap> =
            @Suppress("UNCHECKED_CAST")
            (Result.success(null) as Result<Bitmap>)

        private fun networkFailure(): Result<Bitmap> =
            Result.failure(IllegalStateException("网络不可用"))
    }

    /**
     * [username] 可传空串，用来模拟「用户没勾记住密码」——此时本地账号与密码都被清掉了。
     * 这是**默认人群**，不是边缘情况，所以必须能测。
     */
    private class FakeSessionStore(
        private val username: String = "2026000000",
        private val password: String = "pw"
    ) :
        RenewalSessionWriter, RenewalCredentialReader {
        var cookie: String = ""
        var campusBaseUrl: String = ""
        var studentNumber: String = ""

        override suspend fun saveCookie(cookie: String) { this.cookie = cookie }

        override suspend fun saveCampusBaseUrl(url: String) { this.campusBaseUrl = url }

        override suspend fun saveStudentNumber(studentNumber: String) {
            this.studentNumber = studentNumber
        }

        override fun getUsername(): String = username

        override fun getPassword(): String = password
    }
}
