package com.glut.schedule

import com.glut.schedule.service.academic.AcademicLoginHttpClient
import com.glut.schedule.service.academic.AcademicLoginResult
import okhttp3.MediaType.Companion.toMediaType
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AcademicLoginHttpClientTest {
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
        assertTrue(seen[0].contains("GET http://jw.glut.edu.cn/academic/affairLogin.do"))
        assertTrue(seen[1].contains("GET http://jw.glut.edu.cn/academic/j_acegi_security_check;jsessionid=login-session"))
        assertTrue(seen[1].contains("j_username=20240001"))
        assertTrue(seen[1].contains("j_password=secret%20word"))
        assertTrue(seen[2].contains("POST http://jw.glut.edu.cn/academic/personal/framePage.do"))
    }
}

private fun String.toResponseBody(): okhttp3.ResponseBody =
    toResponseBody("text/html;charset=UTF-8".toMediaType())
