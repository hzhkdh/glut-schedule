package com.glut.schedule

import com.glut.schedule.service.academic.AcademicLoginHttpClient
import com.glut.schedule.service.academic.AcademicLoginResult
import com.glut.schedule.service.academic.CapturingCookieJar
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AcademicLoginHttpClientTest {
    @Test
    fun seededSessionCookieIsReplacedBySecureHttpsSessionCookie() {
        val jar = CapturingCookieJar()
        val httpUrl = "http://jw.glut.edu.cn/academic/affairLogin.do".toHttpUrl()
        val httpsUrl = "https://jw.glut.edu.cn/academic/student/currcourse/currcourse.jsdo".toHttpUrl()

        jar.seedFromCookieHeader("JSESSIONID=initial", httpUrl)
        assertEquals("initial", jar.loadForRequest(httpUrl).single().value)

        jar.saveFromResponse(
            httpsUrl,
            listOf(
                Cookie.Builder()
                    .name("JSESSIONID")
                    .value("rotated")
                    .hostOnlyDomain("jw.glut.edu.cn")
                    .path("/academic")
                    .secure()
                    .build()
            )
        )

        assertEquals("rotated", jar.loadForRequest(httpsUrl).single().value)
        assertTrue(jar.loadForRequest(httpUrl).isEmpty())
    }

    @Test
    fun silentLoginRequiresCredentials() = runTest {
        val client = AcademicLoginHttpClient(client = OkHttpClient())

        val result = client.login(username = "", password = "")

        assertEquals(AcademicLoginResult.MissingCredentials, result)
    }

    @Test
    fun loginPageResponseIsInteractiveLoginRequiredEvenWhenCookieExists() = runTest {
        val client = AcademicLoginHttpClient(
            client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    okhttp3.Response.Builder()
                        .request(chain.request())
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Set-Cookie", "JSESSIONID=abc; Path=/academic")
                        .body("欢迎登录 请输入密码".toResponseBody())
                        .build()
                }
                .build()
        )

        val result = client.login(username = "20240001", password = "secret")

        assertEquals(AcademicLoginResult.CaptchaOrInteractiveLoginRequired, result)
    }

    @Test
    fun successfulLoginReturnsSavedCookie() = runTest {
        val client = AcademicLoginHttpClient(
            client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    okhttp3.Response.Builder()
                        .request(chain.request())
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Set-Cookie", "JSESSIONID=abc; Path=/academic")
                        .body("""{"code":1,"data":{"user":{"id":1001}}}""".toResponseBody())
                        .build()
                }
                .build()
        )

        val result = client.login(username = "20240001", password = "secret")

        assertTrue(result is AcademicLoginResult.Success)
        assertEquals("JSESSIONID=abc", (result as AcademicLoginResult.Success).cookie)
    }

    @Test
    fun successfulLoginReturnsCookieRotatedByVerificationRequest() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .addHeader("Set-Cookie", "JSESSIONID=initial; Path=/academic")
                    .setBody("""<script src="/academic/x.js;jsessionid=initial"></script>""")
            )
            server.enqueue(
                MockResponse()
                    .addHeader("Set-Cookie", "JSESSIONID=logged-in; Path=/academic")
                    .setBody("登录成功")
            )
            server.enqueue(
                MockResponse()
                    .addHeader("Set-Cookie", "JSESSIONID=verified; Path=/academic")
                    .setBody("""{"code":1,"data":{"user":{"id":1001}}}""")
            )
            val jar = CapturingCookieJar()
            val client = AcademicLoginHttpClient(
                cookieJar = jar,
                client = OkHttpClient.Builder().cookieJar(jar).build(),
                baseUrl = server.url("/").toString().trimEnd('/')
            )

            val result = client.login(username = "20240001", password = "secret")

            assertTrue(result is AcademicLoginResult.Success)
            assertEquals("JSESSIONID=verified", (result as AcademicLoginResult.Success).cookie)
        }
    }

    @Test
    fun silentLoginMatchesAcademicPageGetLoginFlow() = runTest {
        val seen = mutableListOf<String>()
        val counter = AtomicInteger(0)
        val client = AcademicLoginHttpClient(
            client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    seen.add("${chain.request().method} ${chain.request().url}")
                    when (counter.getAndIncrement()) {
                        0 -> okhttp3.Response.Builder()
                            .request(chain.request())
                            .protocol(okhttp3.Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .header("Set-Cookie", "JSESSIONID=login-session; Path=/academic")
                            .body("""<script src="/academic/x.js;jsessionid=login-session"></script>""".toResponseBody())
                            .build()
                        1 -> okhttp3.Response.Builder()
                            .request(chain.request())
                            .protocol(okhttp3.Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .header("Set-Cookie", "JSESSIONID=logged-in; Path=/academic")
                            .body("登录成功".toResponseBody())
                            .build()
                        else -> okhttp3.Response.Builder()
                            .request(chain.request())
                            .protocol(okhttp3.Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("""{"code":1,"data":{"user":{"id":1001}}}""".toResponseBody())
                            .build()
                    }
                }
                .build()
        )

        val result = client.login(username = "20240001", password = "secret word")

        assertTrue(result is AcademicLoginResult.Success)
        assertTrue(seen[0].contains("GET https://jw.glut.edu.cn/academic/affairLogin.do"))
        assertTrue(seen[1].contains("GET https://jw.glut.edu.cn/academic/j_acegi_security_check;jsessionid=login-session"))
        assertTrue(seen[1].contains("j_username=20240001"))
        assertTrue(seen[1].contains("j_password=secret%20word"))
        assertTrue(seen[2].contains("POST https://jw.glut.edu.cn/academic/personal/framePage.do"))
    }
}

private fun String.toResponseBody(): okhttp3.ResponseBody =
    toResponseBody("text/html;charset=UTF-8".toMediaType())
