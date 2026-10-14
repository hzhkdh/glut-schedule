package com.glut.schedule.service.academic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.glut.schedule.data.settings.ImportSource
import com.glut.schedule.service.graduate.GraduateClient
import com.glut.schedule.service.graduate.GraduateImportService
import com.glut.schedule.service.graduate.GraduateLoginSession
import com.glut.schedule.service.network.MAX_BOOLEAN_RESPONSE_BYTES
import com.glut.schedule.service.network.MAX_HTML_RESPONSE_BYTES
import com.glut.schedule.service.network.MAX_IMAGE_RESPONSE_BYTES
import com.glut.schedule.service.network.readBytesLimited
import com.glut.schedule.service.network.readStringLimited
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** 会话已失效、必须先取验证码才能提交；文案里带「会话已过期」供界面直接展示。 */
internal const val CAPTCHA_SESSION_EXPIRED_MESSAGE = "会话已过期，请重新获取验证码"

/** 只有南宁与研究生两条路需要验证码，其它来源调到这里属于调用方写错。 */
internal const val CAPTCHA_UNSUPPORTED_SOURCE_MESSAGE = "该来源不需要验证码登录"

/**
 * 续期成功后可以交给调用方保存的东西。
 *
 * 南宁与研究生产出的东西形状不同，所以用一个类型同时表达：
 * - [cookie] / [campusBaseUrl]：两条路都有，协调器据此写回 `AcademicSessionStore` 并重放取数
 * - [graduateSession]：**只有研究生非空**，导入页要拿它去跑整段导入（客户端 + 学年/学期/学号上下文）
 *
 * 为什么研究生不能只回一个 cookie：`GraduateImportService.login(...)` 返回的
 * [GraduateLoginSession] 里除了承载会话的 client，还有课程目录、成绩、培养计划都需要的
 * [com.glut.schedule.service.graduate.GraduatePortalContext]——只回 cookie 的话，
 * 导入页就得自己再登一次才能拿到上下文。
 */
data class RenewedSession(
    val cookie: String,
    val campusBaseUrl: String,
    val graduateSession: GraduateLoginSession? = null,
    /**
     * 门户在这次登录里认到的学号，**只有研究生非空**。
     *
     * 必须往上带：学号为空本身就被算作「会话失效」的一种，而调用方续期成功后要拿它去重放。
     * 若这里不带回来，`AcademicSessionStore` 里那个空学号就永远不会被补上，
     * 用户认真输完验证码、重放却仍用空学号请求，必然再失败一次——白登一场。
     */
    val studentNumber: String? = null
)

/**
 * 南宁与研究生共用的「验证码登录会话」。
 *
 * 这两条路都必须**跨两步持有同一个会话载体**：南宁的验证码与会话绑定在 CookieJar 里
 * （且还要先用它拉一次登录页播种 JSESSIONID），研究生的绑定在 GraduateClient 上。
 * 因此不能每步新建一个客户端。
 *
 * 本类是这两步流程的**全仓唯一一份实现**：导入页与「会话续期」协调器共用它，
 * 避免两处各写一份、日后各自漂移。
 *
 * 它只做 I/O 与结果，**不碰任何界面状态**：取到图交给谁、失败时显示什么文案，由调用方决定。
 */
open class CaptchaLoginSession {

    private var nanningCookieJar: CapturingCookieJar? = null
    private var graduateClient: GraduateClient? = null

    /** 是否已经建立了一个可提交的会话（即 [requestCaptcha] 成功过且未 [reset]）。 */
    fun hasSession(): Boolean =
        nanningCookieJar != null || graduateClient != null

    /** 丢弃当前会话与验证码。 */
    fun reset() {
        nanningCookieJar = null
        graduateClient = null
    }

    /**
     * 建立一个**全新**会话并取一张验证码图。
     *
     * 为什么要「全新」：南宁那步必须先拉一次登录页把 JSESSIONID 播种进 CookieJar，
     * 拿上一轮剩下的那个 jar 去要验证码，服务端认不出来。
     *
     * 失败时不留下半截状态（[reset] 已在开头调用，且只在成功时才记下会话），
     * 这样 [hasSession] 为真就一定是「能提交」。
     */
    open suspend fun requestCaptcha(source: ImportSource): Result<Bitmap> {
        reset()
        return when (source) {
            ImportSource.NANNING -> requestNanningCaptcha()
            ImportSource.GRADUATE -> requestGraduateCaptcha()
            else -> Result.failure(IllegalStateException(CAPTCHA_UNSUPPORTED_SOURCE_MESSAGE))
        }
    }

    /**
     * 在**现有会话**上换一张验证码图。
     *
     * 与 [requestCaptcha] 的区别是刻意保留的：用户点「换一张」时绝不能重建会话——
     * 服务端的验证码是绑在会话上的，换了 jar 等于把刚填的会话丢掉了。
     * 还没有会话时退化为 [requestCaptcha]。
     */
    open suspend fun refreshCaptcha(source: ImportSource): Result<Bitmap> {
        val nanningJar = nanningCookieJar
        val graduate = graduateClient
        return when (source) {
            ImportSource.NANNING ->
                if (nanningJar == null) requestCaptcha(source) else fetchNanningCaptcha(nanningJar)
            ImportSource.GRADUATE ->
                if (graduate == null) requestCaptcha(source) else fetchGraduateCaptcha(graduate)
            else -> Result.failure(IllegalStateException(CAPTCHA_UNSUPPORTED_SOURCE_MESSAGE))
        }
    }

    /**
     * 提交验证码（+ 账号密码），成功返回可保存的会话。
     *
     * 未先 [requestCaptcha] 就提交，唯一正确的行为是立刻失败并说明「会话已过期」——
     * 发出一个注定失败的请求只会让用户等一圈再看到一句看不懂的错误。
     */
    open suspend fun submit(
        source: ImportSource,
        username: String,
        password: String,
        captcha: String
    ): Result<RenewedSession> = when (source) {
        ImportSource.NANNING -> {
            val jar = nanningCookieJar
                ?: return Result.failure(IllegalStateException(CAPTCHA_SESSION_EXPIRED_MESSAGE))
            performNanningLogin(jar, username, password, captcha)
                ?.let { Result.success(RenewedSession(it, AcademicLoginResult.NANNING_URL)) }
                ?: Result.failure(IllegalStateException("验证码或密码错误，请重试"))
        }

        ImportSource.GRADUATE -> {
            val client = graduateClient
                ?: return Result.failure(IllegalStateException(CAPTCHA_SESSION_EXPIRED_MESSAGE))
            runCatching {
                GraduateImportService.login(
                    username = username,
                    password = password,
                    captcha = captcha,
                    client = client
                )
            }.map { session ->
                // 研究生的会话在客户端的 CookieJar 里，cookie 要现取；
                // 主机固定是门户根地址（导入页落库时用的也是这个值）。
                RenewedSession(
                    cookie = session.client.cookieHeader(),
                    campusBaseUrl = GraduateClient.BASE_URL,
                    graduateSession = session,
                    // 门户在这次登录里认到的学号，现成的——顺手带回去，别让调用方再登一次才拿到。
                    studentNumber = session.context.studentId.takeIf { it.isNotBlank() }
                )
            }
        }

        else -> Result.failure(IllegalStateException(CAPTCHA_UNSUPPORTED_SOURCE_MESSAGE))
    }

    // ---- 南宁 ----

    /**
     * 南宁是**三步**：新建 CookieJar → 拉登录页播种 JSESSIONID → 取验证码图。
     *
     * 少了中间那步，验证码请求会因为服务端认不出会话而失败。
     */
    private suspend fun requestNanningCaptcha(): Result<Bitmap> {
        val jar = CapturingCookieJar()
        val pageOk = withContext(Dispatchers.IO) {
            runCatching {
                nanningHttpClient(jar).newCall(
                    Request.Builder()
                        .url("${AcademicLoginResult.NANNING_URL}/academic/common/security/affairLogin.jsp")
                        .header("User-Agent", UA).get().build()
                ).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }
        if (!pageOk) {
            return Result.failure(IllegalStateException("无法连接南宁分校教务系统"))
        }
        return fetchNanningCaptcha(jar).onSuccess { nanningCookieJar = jar }
    }

    /** 在给定会话上取图；**不**改变会话归属。 */
    private suspend fun fetchNanningCaptcha(jar: CapturingCookieJar): Result<Bitmap> {
        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                nanningHttpClient(jar).newCall(
                    Request.Builder()
                        .url("${AcademicLoginResult.NANNING_URL}/academic/getCaptcha.do?captchaCheckCode=0&random=${System.nanoTime()}")
                        .header("User-Agent", UA)
                        .header("Referer", "${AcademicLoginResult.NANNING_URL}/academic/common/security/affairLogin.jsp")
                        .get().build()
                ).execute().use { it.body?.readBytesLimited(MAX_IMAGE_RESPONSE_BYTES) }
            }.getOrNull()
        }
        val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            ?: return Result.failure(IllegalStateException("无法获取验证码图片"))
        return Result.success(bitmap)
    }

    // CookieJar-based client: cookies auto-persist across requests like a browser
    private fun nanningHttpClient(cookieJar: CapturingCookieJar): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(cookieJar)
        .build()

    private suspend fun performNanningLogin(
        jar: CapturingCookieJar,
        username: String,
        password: String,
        captcha: String
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val client = nanningHttpClient(jar)
            val base = AcademicLoginResult.NANNING_URL
            val referer = "$base/academic/common/security/affairLogin.jsp"

            // Step 1: Validate captcha (matches validCaptcha() → AJAX)
            val captchaOk = validateNanningCaptcha(client, captcha, base, referer)
            if (!captchaOk) return@runCatching null

            // Step 2: Hash password + build login URL
            val hashedPassword = NanningPasswordHash.hash(password)
            val loginUrl = "$base/academic/j_acegi_security_check" +
                "?j_username=${URLEncoder.encode(username, "UTF-8")}" +
                "&j_password=${URLEncoder.encode(hashedPassword, "UTF-8")}" +
                "&j_captcha=${URLEncoder.encode(captcha, "UTF-8")}"

            // Step 3: Execute login → CookieJar auto-captures new JSESSIONID from Set-Cookie
            client.newCall(Request.Builder()
                .url(loginUrl)
                .header("User-Agent", UA)
                .header("Referer", referer)
                .get().build()
            ).execute().use { response ->
                // CookieJar automatically saves any Set-Cookie from this response!
                val location = response.header("Location") ?: ""
                when {
                    location.contains("affairLogin", ignoreCase = true) ||
                        location.contains("error", ignoreCase = true) -> return@runCatching null
                    location.isNotBlank() -> followNanningRedirects(client, location, base)
                }
            }

            // Step 4: Verify by fetching a protected page
            verifyNanningLogin(client, base)?.let {
                jar.cookieHeader()
            }
        }.getOrNull()
    }

    private fun validateNanningCaptcha(
        client: OkHttpClient, captcha: String, base: String, referer: String
    ): Boolean {
        val encoded = URLEncoder.encode(captcha, "UTF-8")
        return client.newCall(Request.Builder()
            .url("$base/academic/checkCaptcha.do?captchaCode=$encoded")
            .header("User-Agent", UA)
            .header("Referer", referer)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Origin", base)
            .header("Accept", "text/plain, */*; q=0.01")
            .method("POST", ByteArray(0).toRequestBody(null))
            .build()
        ).execute().use { response ->
            response.body?.readStringLimited(MAX_BOOLEAN_RESPONSE_BYTES).orEmpty().trim() == "true"
        }
    }

    /** Follow redirect chain; CookieJar auto-collects cookies from each hop. */
    private fun followNanningRedirects(client: OkHttpClient, startUrl: String, base: String) {
        var url = startUrl
        for (hop in 1..5) {
            val resolved = if (url.startsWith("http")) url else "$base$url"
            client.newCall(Request.Builder()
                .url(resolved)
                .header("User-Agent", UA)
                .get().build()
            ).execute().use { response ->
                val location = response.header("Location") ?: ""
                if (location.isBlank()) return
                url = location
            }
        }
    }

    private fun verifyNanningLogin(client: OkHttpClient, base: String): Boolean {
        return client.newCall(Request.Builder()
            .url("$base/academic/personal/framePage.do")
            .header("User-Agent", UA)
            .post(FormBody.Builder().build())
            .build()
        ).execute().use { response ->
            val location = response.header("Location") ?: ""
            val body = response.body?.readStringLimited(MAX_HTML_RESPONSE_BYTES).orEmpty()
            isAuthenticatedNanningResponse(response.code, location, body)
        }
    }

    // ---- 研究生 ----

    /**
     * 研究生取验证码：建客户端 → 取图。
     *
     * 门户的验证码与会话绑定在 Cookie 里，因此客户端要一直留到用户提交验证码为止。
     * 取图失败时把原因带出来——此前调用方用 `getOrNull()` 直接吞掉异常，
     * 界面只剩一句「无法获取验证码」，看不出是网络、HTTP 状态码还是代码缺陷。
     */
    private suspend fun requestGraduateCaptcha(): Result<Bitmap> {
        val client = GraduateClient()
        return fetchGraduateCaptcha(client).onSuccess { graduateClient = client }
    }

    /** 在给定客户端上取图；**不**改变会话归属。 */
    private suspend fun fetchGraduateCaptcha(client: GraduateClient): Result<Bitmap> {
        val attempt = withContext(Dispatchers.IO) { runCatching { client.captcha() } }
        val bytes = attempt.getOrNull()
            ?: return Result.failure(
                IllegalStateException(
                    attempt.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                        ?.let { "无法获取研究生教务验证码：$it" }
                        ?: "无法获取研究生教务验证码"
                )
            )
        return Result.success(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
    }
}

/**
 * 南宁受保护页面必须包含明确的认证标记，普通 2xx 或返回登录表单都不能算成功。
 *
 * 放在 `service.academic` 而不是界面层：它是纯粹的协议判定，服务层要用，
 * 反向依赖 `ui.pages` 会把分层倒过来。
 */
internal fun isAuthenticatedNanningResponse(
    httpCode: Int,
    location: String,
    body: String
): Boolean {
    val redirectAuthenticated = listOf(
        "framePage",
        "index_new",
        "showTimetable",
        "/personal/",
        "/manager/"
    ).any { location.contains(it, ignoreCase = true) }
    if (redirectAuthenticated) return true
    if (httpCode !in 200..299) return false

    val loginMarkers = listOf("affairLogin", "j_username", "j_password", "验证码", "loginForm")
    if (loginMarkers.any { body.contains(it, ignoreCase = true) }) return false

    return listOf(
        "schoolCalendarStartDate",
        "currentTodayPlan.do",
        "moduleMenu.do",
        "preGotoAffairFrame"
    ).any { body.contains(it, ignoreCase = true) }
}
