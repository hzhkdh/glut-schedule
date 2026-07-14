package com.glut.schedule

import com.glut.schedule.data.model.FitnessHistoryRequest
import com.glut.schedule.service.fitness.FitnessApiService
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FitnessApiServiceTest {
    private val loginKey = "MDEyMzQ1Njc4OWFiY2RlZg=="

    @Test
    fun captchaLoadsOfficialLoginPageAndImageInOneCookieSession() = runTest {
        MockWebServer().use { server ->
            server.enqueue(html("<script>var secretKey='$loginKey';</script>").addHeader("Set-Cookie", "JSESSIONID=one; Path=/; HttpOnly"))
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "image/jpeg").addHeader("Set-Cookie", "route=a; Path=/").setBody("image"))
            val service = FitnessApiService(OkHttpClient(), server.url("/"))

            val response = service.getCaptcha("")

            assertTrue(response.success)
            assertEquals(loginKey, response.loginKey)
            assertEquals("data:image/jpeg;base64,aW1hZ2U=", response.captchaImage)
            assertEquals("JSESSIONID=one; route=a", response.fitnessCookie)
            assertEquals("/index.jsp", server.takeRequest().path)
            val captcha = server.takeRequest()
            assertEquals("/servlet/UpdateDate?method=validateCode", captcha.path)
            assertTrue(captcha.getHeader("Cookie").orEmpty().contains("JSESSIONID=one"))
        }
    }

    @Test
    fun loginSubmitsTwiceThenFetchesCurrentAndHistoryPages() = runTest {
        MockWebServer().use { server ->
            server.enqueue(text("success").addHeader("Set-Cookie", "JSESSIONID=two; Path=/"))
            server.enqueue(html("<frameset><frame src='/student/studentInfo.jsp'></frameset>"))
            server.enqueue(html("<table><td>项目名称</td><td>测试成绩</td></table>"))
            server.enqueue(html("<table><td>学年</td><td>体测成绩</td><td>体测等级</td></table>"))
            val service = FitnessApiService(OkHttpClient(), server.url("/"))

            val response = service.login("2024001", "password", "1234", "JSESSIONID=one", loginKey)

            assertTrue(response.success)
            assertTrue(response.currentHtml.contains("项目名称"))
            assertTrue(response.historyHtml.contains("体测等级"))
            assertTrue(response.fitnessCookie.contains("JSESSIONID=two"))

            val first = server.takeRequest(1, TimeUnit.SECONDS)!!
            val second = server.takeRequest(1, TimeUnit.SECONDS)!!
            val current = server.takeRequest(1, TimeUnit.SECONDS)!!
            val history = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/servlet/adminservlet", first.path)
            assertEquals("POST", first.method)
            val firstBody = first.body.readUtf8()
            assertTrue(firstBody.contains("operType=911"))
            assertTrue(firstBody.contains("loginflag=1"))
            assertTrue(firstBody.contains("loginType=1"))
            assertTrue(firstBody.contains("validCode=1234"))
            assertTrue(firstBody.contains("userName=Xi9v7VbllM2%252B7X0P52dVgA%253D%253D"))
            assertFalse(firstBody.contains("password"))
            assertTrue(second.body.readUtf8().contains("loginType=0"))
            assertEquals("/student/queryHealthInfo.jsp", current.path)
            assertEquals("/student/Historyhealth.jsp", history.path)
        }
    }

    @Test
    fun historyDetailPostsEveryFieldFromTheOfficialForm() = runTest {
        MockWebServer().use { server ->
            server.enqueue(html("<table><td>项目名称</td><td>测试成绩</td></table>"))
            val service = FitnessApiService(OkHttpClient(), server.url("/"))
            val request = FitnessHistoryRequest("student-a", "2024-2025", "1", "2024", "1")

            val response = service.getHistoryDetail("JSESSIONID=one", request)

            assertTrue(response.success)
            val recorded = server.takeRequest()
            assertEquals("/SportWeb/health_info/listdetalhistroyScore.jsp", recorded.path)
            val body = recorded.body.readUtf8()
            listOf(
                "studentNo=student-a",
                "academicYear=2024-2025",
                "term=1",
                "gradeNo=2024",
                "sex=1",
                "submit=%E6%9F%A5%E7%9C%8B"
            ).forEach { assertTrue("Missing $it in $body", body.contains(it)) }
        }
    }

    @Test
    fun refreshReturnsSessionExpiredWithoutParsingLoginPageAsScores() = runTest {
        MockWebServer().use { server ->
            server.enqueue(html("<input name=\"passwd\"><img src=\"/servlet/UpdateDate?method=validateCode\">"))
            val service = FitnessApiService(OkHttpClient(), server.url("/"))

            val response = service.refresh("JSESSIONID=expired")

            assertFalse(response.success)
            assertEquals("FITNESS_SESSION_EXPIRED", response.code)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun maintenancePageIsRejectedInsteadOfReplacingScoreCache() = runTest {
        MockWebServer().use { server ->
            server.enqueue(html("<html><h1>系统维护中</h1><p>请稍后重试</p></html>"))
            val service = FitnessApiService(OkHttpClient(), server.url("/"))

            val response = service.refresh("JSESSIONID=one")

            assertFalse(response.success)
            assertEquals("FITNESS_UNEXPECTED_PAGE", response.code)
            assertTrue(response.currentHtml.isBlank())
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun scoreKeywordsOutsideATableAreNotAcceptedAsAValidPage() = runTest {
        MockWebServer().use { server ->
            server.enqueue(html("<html><p>项目名称 测试成绩</p><p>系统维护中</p></html>"))

            val response = FitnessApiService(OkHttpClient(), server.url("/"))
                .refresh("JSESSIONID=one")

            assertFalse(response.success)
            assertEquals("FITNESS_UNEXPECTED_PAGE", response.code)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun historyDetailRejectsAnUnexpectedPage() = runTest {
        MockWebServer().use { server ->
            server.enqueue(html("<html><h1>系统维护中</h1></html>"))
            val request = FitnessHistoryRequest("student-a", "2024-2025", "1", "2024", "1")

            val response = FitnessApiService(OkHttpClient(), server.url("/"))
                .getHistoryDetail("JSESSIONID=one", request)

            assertFalse(response.success)
            assertEquals("FITNESS_UNEXPECTED_PAGE", response.code)
            assertTrue(response.detailHtml.isBlank())
        }
    }

    @Test
    fun gbkPagesAreDecodedBeforeParsing() = runTest {
        MockWebServer().use { server ->
            val bytes = "<table><td>项目名称</td><td>测试成绩</td></table>".toByteArray(Charset.forName("GBK"))
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html; charset=GBK").setBody(Buffer().write(bytes)))
            server.enqueue(html("<table><td>学年</td><td>体测成绩</td><td>体测等级</td></table>"))

            val response = FitnessApiService(OkHttpClient(), server.url("/")).refresh("JSESSIONID=one")

            assertTrue(response.currentHtml.contains("项目名称"))
        }
    }

    @Test
    fun loginFailureMapsCaptchaMessageWithoutSecondSubmission() = runTest {
        MockWebServer().use { server ->
            server.enqueue(text("<message>验证码不正确</message>"))

            val response = FitnessApiService(OkHttpClient(), server.url("/"))
                .login("2024001", "password", "0000", "JSESSIONID=one", loginKey)

            assertFalse(response.success)
            assertEquals("FITNESS_CAPTCHA_REQUIRED", response.code)
            assertTrue(response.message.contains("验证码"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun unknownLoginFailureDoesNotExposeTheServerResponse() = runTest {
        MockWebServer().use { server ->
            server.enqueue(text("<html><body>unexpected debug response: student=2024001 secret-value</body></html>"))

            val response = FitnessApiService(OkHttpClient(), server.url("/"))
                .login("2024001", "password", "0000", "JSESSIONID=one", loginKey)

            assertFalse(response.success)
            assertEquals("FITNESS_LOGIN_FAILED", response.code)
            assertEquals("登录失败，请稍后重试", response.message)
            assertFalse(response.message.contains("2024001"))
            assertFalse(response.message.contains("secret-value"))
        }
    }

    @Test
    fun captchaRejectsNonImageResponse() = runTest {
        MockWebServer().use { server ->
            server.enqueue(html("<script>var secretKey='$loginKey';</script>"))
            server.enqueue(html("<html>系统维护中</html>"))

            val response = runCatching {
                FitnessApiService(OkHttpClient(), server.url("/")).getCaptcha("")
            }

            assertTrue(response.exceptionOrNull()?.message.orEmpty().contains("验证码图片"))
        }
    }

    @Test
    fun externalRedirectIsRejectedBeforeFollowingIt() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.com/login"))

            val error = runCatching {
                FitnessApiService(OkHttpClient(), server.url("/")).getCaptcha("")
            }.exceptionOrNull()

            assertTrue(error?.message.orEmpty().contains("不安全的跳转地址"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun oversizedHtmlIsRejectedBeforeReadingTheBody() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("x").setHeader("Content-Length", 4L * 1024 * 1024 + 1))

            val error = runCatching {
                FitnessApiService(OkHttpClient(), server.url("/")).refresh("JSESSIONID=one")
            }.exceptionOrNull()

            assertTrue(error?.message.orEmpty().contains("响应数据过大"))
            assertEquals(1, server.requestCount)
        }
    }

    private fun html(body: String) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "text/html; charset=UTF-8")
        .setBody(body)

    private fun text(body: String) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "text/plain; charset=UTF-8")
        .setBody(body)
}
