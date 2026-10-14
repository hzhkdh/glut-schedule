package com.glut.schedule

import com.glut.schedule.data.settings.ImportSource
import com.glut.schedule.service.academic.CaptchaLoginSession
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `CaptchaLoginSession` 的状态契约。
 *
 * 它必须「先用 [CaptchaLoginSession.requestCaptcha] 建立会话，才能 submit」——南宁的验证码与
 * 会话绑定在 CookieJar 里，研究生的绑定在 GraduateClient 上，两者都跨两步存活。没有先建立
 * 会话就提交，唯一正确的行为是立刻失败并说明「会话已过期」，而不是发出一个注定失败的请求。
 */
class CaptchaLoginSessionTest {

    @Test
    fun submitWithoutRequestingCaptchaFailsInsteadOfPostingBlind() = runTest {
        val session = CaptchaLoginSession()

        val result = session.submit(
            source = ImportSource.NANNING,
            username = "2026000000",
            password = "pw",
            captcha = "1234"
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("会话已过期"))
    }

    @Test
    fun resetDropsTheHeldSessionSoSubmitFailsAgain() = runTest {
        val session = CaptchaLoginSession()

        session.reset()

        assertFalse(session.hasSession())
        assertTrue(
            session.submit(ImportSource.GRADUATE, "2026000000", "pw", "1234").isFailure
        )
    }

    @Test
    fun freshSessionHasNoHeldState() {
        assertFalse(CaptchaLoginSession().hasSession())
    }
}
