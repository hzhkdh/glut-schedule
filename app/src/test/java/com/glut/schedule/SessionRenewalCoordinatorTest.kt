package com.glut.schedule

import android.graphics.Bitmap
import com.glut.schedule.data.settings.ImportSource
import com.glut.schedule.service.academic.AcademicLoginResult
import com.glut.schedule.service.academic.CaptchaLoginSession
import com.glut.schedule.service.academic.RenewalCredentialReader
import com.glut.schedule.service.academic.RenewalSessionWriter
import com.glut.schedule.service.academic.RenewalUiState
import com.glut.schedule.service.academic.RenewedSession
import com.glut.schedule.service.academic.SessionRenewalCoordinator
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        coordinator.submit(captcha = "1234", password = "pw")
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
        coordinator.submit(captcha = "0000", password = "pw")
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
    fun concurrentCallsShareOneDialogAndOneResult() = runTest {
        val captchaLogin = FakeCaptchaLoginSession()
        val coordinator = coordinator(captchaLogin, FakeSessionStore(), ImportSource.NANNING)

        val a = async { coordinator.ensureFreshSession() }
        val b = async { coordinator.ensureFreshSession() }
        advanceUntilIdle()

        assertEquals("只应取一次验证码", 1, captchaLogin.requestCount)

        coordinator.submit("1234", "pw")
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
    fun emptyPasswordIsRejectedWithoutClosingTheDialog() = runTest {
        val coordinator = coordinator(FakeCaptchaLoginSession(), FakeSessionStore(), ImportSource.NANNING)

        val awaiting = async { coordinator.ensureFreshSession() }
        advanceUntilIdle()
        coordinator.submit(captcha = "1234", password = "   ")
        advanceUntilIdle()

        val state = coordinator.state.value as RenewalUiState.AwaitingCaptcha
        assertTrue(state.error.contains("密码"))
        assertFalse(awaiting.isCompleted)

        // 收尾：取消释放等待，否则 runTest 会因「子协程未结束」报错。
        coordinator.cancel()
        advanceUntilIdle()
        assertFalse(awaiting.await())
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
        coordinator.submit("1234", "pw")
        advanceUntilIdle()
        assertTrue(first.await())

        val second = async { coordinator.ensureFreshSession() }
        advanceUntilIdle()
        assertTrue("第二次仍应弹窗", coordinator.state.value is RenewalUiState.AwaitingCaptcha)
        coordinator.cancel()
        advanceUntilIdle()
        assertFalse(second.await())
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
        silentLogin: suspend () -> AcademicLoginResult = {
            AcademicLoginResult.Success("cookie-from-silent")
        }
    ) = SessionRenewalCoordinator(
        sessionWriter = store,
        credentialReader = store,
        captchaLogin = captchaLogin,
        currentSource = { source },
        silentLogin = silentLogin,
        scope = this
    )

    private class FakeCaptchaLoginSession(
        private val submitSucceeds: Boolean = true,
        private val requestSucceeds: Boolean = true
    ) : CaptchaLoginSession() {
        /** 取图次数：首次取图与「换一张」都算一次。 */
        var requestCount = 0
            private set

        override suspend fun requestCaptcha(source: ImportSource): Result<Bitmap> {
            requestCount++
            return if (requestSucceeds) successWithoutCreatingABitmap() else networkFailure()
        }

        override suspend fun refreshCaptcha(source: ImportSource): Result<Bitmap> {
            requestCount++
            return if (requestSucceeds) successWithoutCreatingABitmap() else networkFailure()
        }

        override suspend fun submit(
            source: ImportSource, username: String, password: String, captcha: String
        ): Result<RenewedSession> =
            if (submitSucceeds) {
                Result.success(RenewedSession("cookie-after-renew", AcademicLoginResult.NANNING_URL))
            } else {
                Result.failure(IllegalStateException("验证码或密码错误"))
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

    private class FakeSessionStore : RenewalSessionWriter, RenewalCredentialReader {
        var cookie: String = ""
        var campusBaseUrl: String = ""

        override suspend fun saveCookie(cookie: String) { this.cookie = cookie }

        override suspend fun saveCampusBaseUrl(url: String) { this.campusBaseUrl = url }

        /** 本地没存密码：弹窗应留空、由用户当场填。 */
        override fun getUsername(): String = "2026000000"

        override fun getPassword(): String = ""
    }
}
