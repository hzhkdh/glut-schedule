package com.glut.schedule

import com.glut.schedule.data.model.FinanceModule
import com.glut.schedule.service.finance.FinanceCookies
import com.glut.schedule.service.finance.FinanceApiService
import com.glut.schedule.service.finance.FinanceFailure
import com.glut.schedule.service.finance.FinanceNetworkStage
import com.glut.schedule.service.finance.FinanceRequests
import com.glut.schedule.service.finance.FinanceResponses
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ConnectException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceApiServiceTest {
    @Test
    fun moduleRequestMatchesOfficialWebsiteContract() {
        assertEquals(
            mapOf("method" to "getorderlist", "stuid" to "1", "start" to "1", "pagesize" to "20"),
            FinanceRequests.module(FinanceModule.TRANSACTIONS, page = 2, pageSize = 20)
        )
        assertEquals(
            mapOf("method" to "getchargeitems", "money" to "0", "stuid" to "1"),
            FinanceRequests.module(FinanceModule.PENDING)
        )
    }

    @Test
    fun userNoLoginVariantsAreSessionExpired() {
        assertTrue(FinanceResponses.isSessionExpired(" user_no_login "))
        assertTrue(FinanceResponses.isSessionExpired("USERNOLOGIN"))
        assertTrue(FinanceResponses.isSessionExpired("登录状态已过期"))
        assertFalse(FinanceResponses.isSessionExpired("查询成功"))
    }

    @Test
    fun cookiesMergeByNameWithoutLeakingAttributes() {
        val merged = FinanceCookies.merge(
            "ASP.NET_SessionId=old; route=a",
            listOf("ASP.NET_SessionId=new; Path=/; HttpOnly", "token=t1; Secure")
        )

        assertEquals("ASP.NET_SessionId=new; route=a; token=t1", merged)
    }

    @Test
    fun redirectsRequireOfficialHostAndHttps() {
        assertTrue(FinanceResponses.isSafeRedirect("https://cwjf.glut.edu.cn/home/index"))
        assertFalse(FinanceResponses.isSafeRedirect("http://cwjf.glut.edu.cn/home/index"))
        assertFalse(FinanceResponses.isSafeRedirect("https://evil.example/home/index"))
    }

    @Test
    fun captchaRetriesTheWholeSessionOnceAndKeepsBrowserHeaders() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).addHeader("Set-Cookie", "sid=fresh; Path=/").setBody("<html></html>"))
            server.enqueue(success("ok"))
            server.enqueue(success("ok"))
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "image/jpeg").setBody("fake-captcha-image"))
            var calls = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                if (calls == 1) throw ConnectException("synthetic")
                chain.proceed(chain.request())
            }.build()
            val service = FinanceApiService(client = client, baseUrl = server.url("/"))

            val captcha = service.captcha()

            assertTrue(captcha.imageDataUrl.startsWith("data:image/jpeg;base64,"))
            assertEquals(5, calls)
            assertEquals(4, server.requestCount)
            server.takeRequest()
            val authConfig = server.takeRequest()
            assertEquals("https://cwjf.glut.edu.cn", authConfig.getHeader("Origin"))
            assertEquals("zh-CN,zh;q=0.9", authConfig.getHeader("Accept-Language"))
            assertTrue(authConfig.getHeader("Cookie").orEmpty().contains("sid=fresh"))
        }
    }

    @Test
    fun captchaFailureReportsTheExactStageAfterOneRetry() = runTest {
        MockWebServer().use { server ->
            var calls = 0
            val client = OkHttpClient.Builder().addInterceptor {
                calls++
                throw ConnectException("synthetic")
            }.build()
            val error = runCatching { FinanceApiService(client = client, baseUrl = server.url("/")).captcha() }.exceptionOrNull()

            assertTrue(error is FinanceFailure.Network)
            assertEquals(FinanceNetworkStage.LOGIN_PAGE, (error as FinanceFailure.Network).stage)
            assertTrue(error.message.orEmpty().contains("打开财务登录页"))
            assertEquals(2, calls)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun loginSubmissionIsNeverRetried() = runTest {
        MockWebServer().use { server ->
            server.enqueue(success("ok"))
            var loginAttempts = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                if (chain.request().url.encodedPath == "/interface/login") {
                    loginAttempts++
                    throw ConnectException("synthetic")
                }
                chain.proceed(chain.request())
            }.build()
            val error = runCatching {
                FinanceApiService(client = client, baseUrl = server.url("/")).login("2024001", "secret", "1234", "sid=one")
            }.exceptionOrNull()

            assertTrue(error is FinanceFailure.Network)
            assertEquals(FinanceNetworkStage.LOGIN_SUBMIT, (error as FinanceFailure.Network).stage)
            assertEquals(1, loginAttempts)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun loginRedirectDoesNotReplayCredentialsOn302() = runTest {
        MockWebServer().use { server ->
            server.enqueue(success("ok"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/home/index"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("<html>signed in</html>"))
            runCatching {
                FinanceApiService(client = OkHttpClient(), baseUrl = server.url("/"))
                    .login("2024001", "secret", "1234", "sid=one")
            }

            val config = server.takeRequest(1, TimeUnit.SECONDS)!!
            val submit = server.takeRequest(1, TimeUnit.SECONDS)!!
            val redirected = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("POST", config.method)
            assertEquals("POST", submit.method)
            assertEquals("GET", redirected.method)
            assertEquals(0L, redirected.bodySize)
        }
    }

    @Test
    fun financeDnsPrefersIpv4WithoutDroppingIpv6Fallback() {
        val ipv6 = InetAddress.getByName("2001:db8::1") as Inet6Address
        val ipv4 = InetAddress.getByName("192.0.2.1") as Inet4Address

        val sorted = com.glut.schedule.service.finance.FinanceDns.prioritize(listOf(ipv6, ipv4))

        assertEquals(listOf(ipv4, ipv6), sorted)
    }

    private fun success(data: String) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"state":200,"data":"$data","success":true}""")
}
