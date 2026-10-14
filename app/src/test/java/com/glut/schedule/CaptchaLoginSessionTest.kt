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

    /**
     * 研究生取图必须和南宁一样挡住「解码失败」。
     *
     * 门户返回非图片（200 的 HTML 错误页、空 body）时 `BitmapFactory.decodeByteArray` 返回 null，
     * 而 `Result<Bitmap>` 允许持有 null——于是界面拿到一个「成功但没有图」的状态：
     * 弹窗里既没有验证码图、也没有任何原因，只剩一句「点这里重试」。
     * 用户无从知道发生了什么，也永远输不对那张不存在的图。
     *
     * 为什么用源码断言而不是行为测试：JVM 单测里 `android.graphics.BitmapFactory` 的方法
     * 全是 stub、调用即抛，**造不出「解码返回 null」这个状态**。南宁那条一直有
     * `?: return Result.failure(...)` 兜底，所以这里钉的是**两条分支形状一致**。
     */
    @Test
    fun bothCaptchaBranchesRejectADecodeFailure() {
        val source = java.io.File(
            "src/main/java/com/glut/schedule/service/academic/CaptchaLoginSession.kt"
        )
            .let {
                if (it.exists()) it
                else java.io.File("app/src/main/java/com/glut/schedule/service/academic/CaptchaLoginSession.kt")
            }
            .readText()

        val guards = Regex("""\?:\s*return Result\.failure\(""").findAll(source).count()
        assertTrue("南宁与研究生两条取图都必须挡住解码失败，实际只有 $guards 处", guards >= 2)

        assertFalse(
            "不能把可能为 null 的解码结果直接当成成功返回",
            source.contains("Result.success(BitmapFactory.decodeByteArray")
        )
    }
}
