package com.glut.schedule.service.academic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

sealed class AcademicLoginResult {
    data class Success(val cookie: String) : AcademicLoginResult()
    data object MissingCredentials : AcademicLoginResult()
    data object InvalidCredentials : AcademicLoginResult()
    data object CaptchaOrInteractiveLoginRequired : AcademicLoginResult()
    data class NetworkError(val message: String) : AcademicLoginResult()
}

class AcademicLoginHttpClient(
    private val cookieJar: CapturingCookieJar = CapturingCookieJar(),
    client: OkHttpClient? = null,
    private val baseUrl: String = "http://jw.glut.edu.cn"
) {
    private val client: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .cookieJar(cookieJar)
        .build()

    suspend fun login(username: String, password: String): AcademicLoginResult = withContext(Dispatchers.IO) {
        if (username.isBlank() || password.isBlank()) {
            return@withContext AcademicLoginResult.MissingCredentials
        }

        runCatching {
            val loginPage = fetchLoginPage()
            val loginPath = buildLoginPath(loginPage.sessionId)
            val loginUrl = "$baseUrl$loginPath" +
                "?j_username=${encode(username)}&j_password=${encode(password)}&j_captcha="

            val request = Request.Builder()
                .url(loginUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/json,*/*")
                .header("Referer", "$baseUrl/academic/affairLogin.do")
                .apply {
                    if (loginPage.cookie.isNotBlank()) {
                        header("Cookie", loginPage.cookie)
                    }
                }
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val cookie = extractCookie(response.headers("Set-Cookie")).ifBlank {
                    cookieJar.cookieHeader().ifBlank { loginPage.cookie }
                }
                when {
                    response.code == 401 || body.contains("用户名") && body.contains("密码错误") ->
                        AcademicLoginResult.InvalidCredentials
                    looksLikeLoginPage(body) ->
                        AcademicLoginResult.CaptchaOrInteractiveLoginRequired
                    cookie.isBlank() ->
                        AcademicLoginResult.CaptchaOrInteractiveLoginRequired
                    else ->
                        verifyLogin(cookie)
                }
            }
        }.getOrElse { error ->
            AcademicLoginResult.NetworkError(error.message ?: "网络请求失败")
        }
    }

    private data class LoginPage(
        val sessionId: String,
        val cookie: String
    )

    private fun fetchLoginPage(): LoginPage {
        val request = Request.Builder()
            .url("$baseUrl/academic/affairLogin.do")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,*/*")
            .get()
            .build()

        return client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val cookie = extractCookie(response.headers("Set-Cookie")).ifBlank { cookieJar.cookieHeader() }
            val sessionId = Regex(""";jsessionid=([^"'?;>\s]+)""", RegexOption.IGNORE_CASE)
                .find(body)
                ?.groupValues
                ?.get(1)
                .orEmpty()
                .ifBlank {
                    Regex("""JSESSIONID=([^;]+)""", RegexOption.IGNORE_CASE)
                        .find(cookie)
                        ?.groupValues
                        ?.get(1)
                        .orEmpty()
                }
            LoginPage(sessionId = sessionId, cookie = cookie)
        }
    }

    private fun buildLoginPath(sessionId: String): String {
        return if (sessionId.isBlank()) {
            "/academic/j_acegi_security_check"
        } else {
            "/academic/j_acegi_security_check;jsessionid=$sessionId"
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun verifyLogin(cookie: String): AcademicLoginResult {
        val verifyRequest = Request.Builder()
            .url("$baseUrl/academic/personal/framePage.do")
            .header("Cookie", cookie)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/json,*/*")
            .post(FormBody.Builder().build())
            .build()

        return client.newCall(verifyRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            when {
                response.code == 401 || response.code == 403 -> AcademicLoginResult.InvalidCredentials
                looksLikeLoginPage(body) -> AcademicLoginResult.CaptchaOrInteractiveLoginRequired
                response.isSuccessful -> AcademicLoginResult.Success(cookie)
                else -> AcademicLoginResult.NetworkError("教务系统返回 HTTP ${response.code}")
            }
        }
    }

    private fun extractCookie(setCookieHeaders: List<String>): String {
        return setCookieHeaders
            .mapNotNull { header ->
                val nameValue = header.substringBefore(";").trim()
                nameValue.takeIf {
                    it.startsWith("JSESSIONID=", ignoreCase = true) ||
                        it.startsWith("CASTGC=", ignoreCase = true) ||
                        it.startsWith("TGC=", ignoreCase = true)
                }
            }
            .distinct()
            .joinToString("; ")
    }

    private fun looksLikeLoginPage(body: String): Boolean {
        val compact = body.replace(Regex("""\s+"""), "")
        return compact.contains("欢迎登录") ||
            compact.contains("请输入密码") ||
            compact.contains("账号登录") ||
            compact.contains("统一身份认证") ||
            compact.contains("验证码")
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
    }
}

class CapturingCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        this.cookies.removeAll { existing -> cookies.any { it.name == existing.name && it.domain == existing.domain } }
        this.cookies.addAll(cookies)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return cookies.filter { cookie -> cookie.matches(url) }
    }

    fun cookieHeader(): String {
        return cookies
            .filter { cookie ->
                cookie.name.equals("JSESSIONID", ignoreCase = true) ||
                    cookie.name.equals("CASTGC", ignoreCase = true) ||
                    cookie.name.equals("TGC", ignoreCase = true)
            }
            .joinToString("; ") { cookie -> "${cookie.name}=${cookie.value}" }
    }
}

class AcademicLoginService(
    private val sessionStore: AcademicSessionStore,
    private val credentialStore: CredentialStore,
    private val loginClient: AcademicLoginHttpClient = AcademicLoginHttpClient()
) {
    suspend fun silentLogin(): AcademicLoginResult {
        val username = credentialStore.getUsername()
        val password = credentialStore.getPassword()
        val result = loginClient.login(username, password)
        if (result is AcademicLoginResult.Success) {
            sessionStore.saveCookie(result.cookie)
        }
        return result
    }
}
