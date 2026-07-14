package com.glut.schedule.service.fitness

import com.glut.schedule.data.model.FitnessHistoryRequest
import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class FitnessApiResponse(
    val success: Boolean,
    val code: String = "",
    val message: String = "",
    val fitnessCookie: String = "",
    val captchaImage: String = "",
    val loginKey: String = "",
    val currentHtml: String = "",
    val historyHtml: String = "",
    val detailHtml: String = "",
    val standardHtml: String = ""
)

interface FitnessGateway {
    suspend fun getCaptcha(cookie: String): FitnessApiResponse

    suspend fun login(
        username: String,
        password: String,
        captcha: String,
        cookie: String,
        loginKey: String
    ): FitnessApiResponse

    suspend fun refresh(cookie: String): FitnessApiResponse
    suspend fun getHistoryDetail(cookie: String, request: FitnessHistoryRequest): FitnessApiResponse
    suspend fun getStandard(cookie: String): FitnessApiResponse
}

class FitnessApiService(
    client: OkHttpClient = defaultClient(),
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl()
) : FitnessGateway {
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun getCaptcha(cookie: String): FitnessApiResponse = withContext(Dispatchers.IO) {
        val loginPage = get("/index.jsp", cookie, MAX_HTML_BYTES)
        val loginKey = FitnessLoginPage.secretKey(loginPage.text)
        val captcha = get(
            "/servlet/UpdateDate?method=validateCode",
            loginPage.cookie,
            MAX_IMAGE_BYTES,
            accept = "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
            referer = "/index.jsp"
        )
        val mime = captcha.contentType.substringBefore(';').trim()
        if (!mime.startsWith("image/") || captcha.bytes.isEmpty()) {
            throw IOException("学校体测系统未返回有效的验证码图片")
        }
        FitnessApiResponse(
            success = true,
            fitnessCookie = captcha.cookie,
            captchaImage = "data:$mime;base64,${Base64.getEncoder().encodeToString(captcha.bytes)}",
            loginKey = loginKey
        )
    }

    override suspend fun login(
        username: String,
        password: String,
        captcha: String,
        cookie: String,
        loginKey: String
    ): FitnessApiResponse = withContext(Dispatchers.IO) {
        if (username.isBlank() || password.isBlank() || captcha.isBlank()) {
            return@withContext failure("FITNESS_CREDENTIALS_REQUIRED", "请输入学号、体测平台密码和验证码")
        }
        if (loginKey.isBlank()) {
            return@withContext failure("FITNESS_LOGIN_FAILED", "验证码已失效，请刷新后重试")
        }
        val encryptedUsername = runCatching { FitnessLoginCrypto.formValue(username.trim(), loginKey) }
            .getOrElse { return@withContext failure("FITNESS_LOGIN_FAILED", "登录准备失败，请刷新验证码后重试") }
        val encryptedPassword = runCatching { FitnessLoginCrypto.formValue(password, loginKey) }
            .getOrElse { return@withContext failure("FITNESS_LOGIN_FAILED", "登录准备失败，请刷新验证码后重试") }

        val first = post(
            "/servlet/adminservlet",
            loginForm("1", encryptedUsername, encryptedPassword, captcha.trim()),
            cookie,
            MAX_HTML_BYTES
        )
        if (!first.text.trim().equals("success", ignoreCase = true)) {
            val responseText = plainText(first.text).take(MAX_LOGIN_MESSAGE_CHARS)
            val captchaFailed = responseText.contains("验证码")
            val credentialsFailed = listOf("密码", "账号", "用户", "学号").any(responseText::contains)
            return@withContext failure(
                if (captchaFailed) "FITNESS_CAPTCHA_REQUIRED" else "FITNESS_LOGIN_FAILED",
                when {
                    captchaFailed -> "验证码错误，请重新输入"
                    credentialsFailed -> "学号或体测平台密码错误，请检查后重试"
                    else -> "登录失败，请稍后重试"
                },
                first.cookie
            )
        }

        val second = post(
            "/servlet/adminservlet",
            loginForm("0", encryptedUsername, encryptedPassword, captcha.trim()),
            first.cookie,
            MAX_HTML_BYTES
        )
        if (FitnessResponses.isSessionExpired(second.text)) {
            return@withContext failure("FITNESS_LOGIN_FAILED", "学号或体测平台密码错误，请检查后重试", second.cookie)
        }
        snapshot(second.cookie)
    }

    override suspend fun refresh(cookie: String): FitnessApiResponse = withContext(Dispatchers.IO) {
        snapshot(cookie)
    }

    override suspend fun getHistoryDetail(
        cookie: String,
        request: FitnessHistoryRequest
    ): FitnessApiResponse = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("studentNo", request.studentNo)
            .add("academicYear", request.academicYear)
            .add("term", request.term)
            .add("gradeNo", request.gradeNo)
            .add("sex", request.sex)
            .add("submit", "查看")
            .build()
        val page = post(
            "/SportWeb/health_info/listdetalhistroyScore.jsp",
            form,
            cookie,
            MAX_HTML_BYTES,
            referer = "/student/Historyhealth.jsp"
        )
        if (FitnessResponses.isSessionExpired(page.text)) sessionExpired(page.cookie)
        else if (!isExpectedDetail(page.text)) unexpectedPage(page.cookie)
        else FitnessApiResponse(success = true, fitnessCookie = page.cookie, detailHtml = page.text)
    }

    override suspend fun getStandard(cookie: String): FitnessApiResponse = withContext(Dispatchers.IO) {
        val page = get("/SportWeb/essential_info/health_standard/exerPFB.jsp", cookie, MAX_HTML_BYTES)
        if (FitnessResponses.isSessionExpired(page.text)) sessionExpired(page.cookie)
        else FitnessApiResponse(success = true, fitnessCookie = page.cookie, standardHtml = page.text)
    }

    private fun snapshot(cookie: String): FitnessApiResponse {
        val current = get("/student/queryHealthInfo.jsp", cookie, MAX_HTML_BYTES)
        if (FitnessResponses.isSessionExpired(current.text)) return sessionExpired(current.cookie)
        if (!isExpectedCurrent(current.text)) return unexpectedPage(current.cookie)
        val history = get("/student/Historyhealth.jsp", current.cookie, MAX_HTML_BYTES)
        if (FitnessResponses.isSessionExpired(history.text)) return sessionExpired(history.cookie)
        if (!isExpectedHistory(history.text)) return unexpectedPage(history.cookie)
        return FitnessApiResponse(
            success = true,
            fitnessCookie = history.cookie,
            currentHtml = current.text,
            historyHtml = history.text
        )
    }

    private fun loginForm(
        loginType: String,
        username: String,
        password: String,
        captcha: String
    ): FormBody = FormBody.Builder()
        .add("operType", "911")
        .add("loginflag", "1")
        .add("loginType", loginType)
        .add("userName", username)
        .add("passwd", password)
        .add("validCode", captcha)
        .build()

    private fun get(
        path: String,
        cookie: String,
        maxBytes: Long,
        accept: String = HTML_ACCEPT,
        referer: String? = null
    ): Page = execute(
        request(path, cookie, accept, referer).get().build(),
        cookie,
        maxBytes
    )

    private fun post(
        path: String,
        form: FormBody,
        cookie: String,
        maxBytes: Long,
        referer: String = "/index.jsp"
    ): Page = execute(
        request(path, cookie, HTML_ACCEPT, referer)
            .header("Origin", origin())
            .post(form)
            .build(),
        cookie,
        maxBytes
    )

    private fun request(path: String, cookie: String, accept: String, referer: String?): Request.Builder {
        val url = baseUrl.resolve(path) ?: throw IOException("学校体测系统地址无效")
        return Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("User-Agent", USER_AGENT)
            .apply {
                referer?.let { path ->
                    val refererUrl = baseUrl.resolve(path) ?: throw IOException("学校体测系统来源地址无效")
                    header("Referer", refererUrl.toString())
                }
            }
            .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
    }

    private fun execute(initial: Request, initialCookie: String, maxBytes: Long): Page {
        var request = initial
        var cookie = initialCookie
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val requestWithCookie = request.newBuilder().apply {
                if (cookie.isBlank()) removeHeader("Cookie") else header("Cookie", cookie)
            }.build()
            client.newCall(requestWithCookie).execute().use { response ->
                cookie = FitnessCookies.merge(cookie, response.headers("Set-Cookie"))
                if (response.isRedirect) {
                    if (redirectCount == MAX_REDIRECTS) throw IOException("学校体测系统跳转次数过多")
                    val location = response.header("Location") ?: throw IOException("学校体测系统跳转地址为空")
                    val next = response.request.url.resolve(location) ?: throw IOException("学校体测系统跳转地址无效")
                    if (!FitnessResponses.isSafeRedirect(next, baseUrl)) {
                        throw IOException("学校体测系统返回了不安全的跳转地址")
                    }
                    request = if (response.code == 307 || response.code == 308) {
                        request.newBuilder().url(next).build()
                    } else {
                        request.newBuilder().url(next).get().build()
                    }
                    return@use
                }
                if (!response.isSuccessful) throw IOException("学校体测系统返回错误 ${response.code}")
                val contentLength = response.body.contentLength()
                if (contentLength > maxBytes) throw IOException("学校体测系统响应数据过大")
                val source = response.body.source()
                source.request(maxBytes + 1)
                if (source.buffer.size > maxBytes) throw IOException("学校体测系统响应数据过大")
                val bytes = source.readByteArray()
                val contentType = response.header("Content-Type").orEmpty()
                return Page(bytes, decode(bytes, contentType), cookie, contentType)
            }
        }
        throw IOException("学校体测系统跳转次数过多")
    }

    private fun decode(bytes: ByteArray, contentType: String): String {
        val declared = Regex("charset\\s*=\\s*['\"]?([A-Za-z0-9._-]+)", RegexOption.IGNORE_CASE)
            .find(contentType)?.groupValues?.get(1)
        val preview = String(bytes.take(2048).toByteArray(), StandardCharsets.ISO_8859_1)
        val meta = Regex("charset\\s*=\\s*['\"]?([A-Za-z0-9._-]+)", RegexOption.IGNORE_CASE)
            .find(preview)?.groupValues?.get(1)
        val charset = charset(declared ?: meta)
        return String(bytes, charset)
    }

    private fun charset(name: String?): Charset = when (name?.lowercase()) {
        "gbk", "gb2312", "gb18030" -> Charset.forName("GBK")
        null, "" -> StandardCharsets.UTF_8
        else -> runCatching { Charset.forName(name) }.getOrDefault(StandardCharsets.UTF_8)
    }

    private fun origin(): String = buildString {
        append(baseUrl.scheme).append("://").append(baseUrl.host)
        val defaultPort = if (baseUrl.scheme == "https") 443 else 80
        if (baseUrl.port != defaultPort) append(':').append(baseUrl.port)
    }

    private fun plainText(value: String): String = value
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun isExpectedCurrent(html: String): Boolean =
        hasTableHeaders(html, listOf("项目名称", "测试成绩"))

    private fun isExpectedHistory(html: String): Boolean =
        hasTableHeaders(html, listOf("学年", "体测成绩", "体测等级"))

    private fun isExpectedDetail(html: String): Boolean =
        hasTableHeaders(html, listOf("项目名称", "测试成绩"))

    private fun hasTableHeaders(html: String, required: List<String>): Boolean =
        TABLE_BLOCK.findAll(html).any { table ->
            val cells = TABLE_CELL.findAll(table.value).map { plainText(it.value) }.toList()
            required.all { header -> cells.any { cell -> cell.contains(header) } }
        }

    private fun failure(code: String, message: String, cookie: String = "") = FitnessApiResponse(
        success = false,
        code = code,
        message = message,
        fitnessCookie = cookie
    )

    private fun sessionExpired(cookie: String) = failure(
        "FITNESS_SESSION_EXPIRED",
        "体测登录已失效，请重新登录",
        cookie
    )

    private fun unexpectedPage(cookie: String) = failure(
        "FITNESS_UNEXPECTED_PAGE",
        "学校体测系统返回了无法识别的页面，请稍后重试",
        cookie
    )

    private data class Page(
        val bytes: ByteArray,
        val text: String,
        val cookie: String,
        val contentType: String
    )

    companion object {
        const val DEFAULT_BASE_URL = "https://tzcs.glut.edu.cn/"
        private const val MAX_HTML_BYTES = 4L * 1024 * 1024
        private const val MAX_IMAGE_BYTES = 1024L * 1024
        private const val MAX_REDIRECTS = 5
        private const val MAX_LOGIN_MESSAGE_CHARS = 256
        private const val HTML_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
        private val TABLE_BLOCK = Regex("<table\\b[\\s\\S]*?</table>", RegexOption.IGNORE_CASE)
        private val TABLE_CELL = Regex("<t[dh]\\b[\\s\\S]*?</t[dh]>", RegexOption.IGNORE_CASE)

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
