package com.glut.schedule.service.finance

import android.util.Log
import com.glut.schedule.data.model.FinanceModule
import com.glut.schedule.data.model.FinancePayload
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject

data class FinanceCaptcha(val imageDataUrl: String, val cookie: String)

data class FinanceResponse(
    val payload: FinancePayload? = null,
    val cookie: String,
    val page: Int = 1,
    val total: Int = 0,
    val hasMore: Boolean = false
)

sealed class FinanceFailure(message: String) : IOException(message) {
    class SessionExpired : FinanceFailure("财务登录已失效，请重新登录")
    class CaptchaInvalid(message: String) : FinanceFailure(message)
    class CredentialsInvalid(message: String) : FinanceFailure(message)
    class Parse : FinanceFailure("财务系统返回格式异常")
    class Upstream(message: String) : FinanceFailure(message)
    class Network(val stage: FinanceNetworkStage, val kind: String, message: String, cause: IOException) : FinanceFailure(message) {
        init { initCause(cause) }
    }
}

enum class FinanceNetworkStage {
    LOGIN_PAGE,
    AUTH_CONFIG,
    USERNAME_TIPS,
    CAPTCHA_IMAGE,
    LOGIN_SUBMIT,
    MODULE_FETCH,
    TICKET_IMAGE;

    val label: String
        get() = when (this) {
            LOGIN_PAGE -> "打开财务登录页"
            AUTH_CONFIG -> "初始化财务登录"
            USERNAME_TIPS -> "初始化财务账号输入"
            CAPTCHA_IMAGE -> "获取财务验证码"
            LOGIN_SUBMIT -> "提交财务登录"
            MODULE_FETCH -> "查询财务数据"
            TICKET_IMAGE -> "获取电子票据"
        }
}

interface FinanceGateway {
    suspend fun captcha(): FinanceCaptcha
    suspend fun login(username: String, password: String, captcha: String, cookie: String): FinanceResponse
    suspend fun fetch(module: FinanceModule, cookie: String, page: Int = 1, pageSize: Int = 20): FinanceResponse
    suspend fun ticketImage(cookie: String, chargeId: String, receiptNo: String): FinanceResponse
}

object FinanceRequests {
    fun module(module: FinanceModule, page: Int = 1, pageSize: Int = 20): Map<String, String> {
        val safePage = page.coerceAtLeast(1)
        val safeSize = pageSize.coerceIn(1, 50)
        return when (module) {
            FinanceModule.OVERVIEW -> mapOf("method" to "getinfo", "stuid" to "1")
            FinanceModule.PENDING -> mapOf("method" to "getchargeitems", "money" to "0", "stuid" to "1")
            FinanceModule.OTHER_PAYMENTS -> mapOf("method" to "getotheritems", "stuid" to "1", "chargetype" to "othercharge")
            FinanceModule.TRANSACTIONS -> mapOf("method" to "getorderlist", "stuid" to "1", "start" to (safePage - 1).toString(), "pagesize" to safeSize.toString())
            FinanceModule.FEE_PROJECTS -> mapOf("method" to "getstudentitems", "stuid" to "1")
            FinanceModule.PAYMENT_DETAILS -> mapOf("method" to "getdetail", "stuid" to "1", "start" to (safePage - 1).toString(), "pagesize" to safeSize.toString())
            FinanceModule.COURSE_RECORDS -> mapOf("method" to "getxkjl", "stuid" to "1")
            FinanceModule.ELECTRONIC_TICKETS -> mapOf("method" to "getstudenteticketchargedetail", "stuid" to "1")
            FinanceModule.CREDIT_SETTLEMENT -> mapOf("method" to "onlinepaymentcustomquery", "stuid" to "1")
        }
    }
}

object FinanceResponses {
    fun isSafeRedirect(
        url: String,
        allowedHost: String = "cwjf.glut.edu.cn",
        allowedScheme: String = "https",
        allowedPort: Int = HttpUrl.defaultPort(allowedScheme)
    ): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.scheme == allowedScheme && parsed.host == allowedHost && parsed.port == allowedPort
    }

    fun isSessionExpired(value: Any?): Boolean {
        val message = value?.toString().orEmpty().trim()
        val normalized = message.replace(Regex("[\\s_-]+"), "").lowercase()
        return normalized.contains("usernologin") || Regex("登录|会话|失效|过期").containsMatchIn(message)
    }

    fun unwrap(body: String, finalUrl: String): Any? {
        val trimmed = body.trim()
        if (finalUrl.contains("/home/login", ignoreCase = true) || trimmed.isBlank() || trimmed.startsWith("<")) {
            throw FinanceFailure.SessionExpired()
        }
        val json = runCatching { JSONObject(trimmed) }.getOrElse { throw FinanceFailure.Parse() }
        val data = if (json.has("data")) json.opt("data") else json
        if (isSessionExpired(data)) throw FinanceFailure.SessionExpired()
        val success = json.optString("state") == "200" || json.optBoolean("success", false)
        if (!success) {
            val message = listOf(json.optString("data"), json.optString("message"), json.optString("msg"))
                .firstOrNull { it.isNotBlank() } ?: "财务系统请求失败"
            if (isSessionExpired(message)) throw FinanceFailure.SessionExpired()
            throw FinanceFailure.Upstream(message)
        }
        if (data is String) {
            val nested = data.trim()
            if (nested.startsWith("{")) return runCatching { JSONObject(nested) }.getOrElse { data }
            if (nested.startsWith("[")) return runCatching { JSONArray(nested) }.getOrElse { data }
        }
        return data
    }
}

object FinanceDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> = prioritize(Dns.SYSTEM.lookup(hostname))

    fun prioritize(addresses: List<InetAddress>): List<InetAddress> = addresses.sortedBy { if (it is Inet4Address) 0 else 1 }
}

object FinanceCookies {
    fun merge(existing: String, setCookies: List<String>): String {
        val values = linkedMapOf<String, String>()
        existing.split(';').map(String::trim).filter { it.contains('=') }.forEach { pair ->
            val name = pair.substringBefore('=').trim()
            if (name.isNotBlank()) values[name] = pair.substringAfter('=', "").trim()
        }
        setCookies.forEach { header ->
            val pair = header.substringBefore(';').trim()
            val name = pair.substringBefore('=').trim()
            if (name.isNotBlank() && pair.contains('=')) values[name] = pair.substringAfter('=', "").trim()
        }
        return values.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
}

class FinanceApiService(
    private val parser: FinanceParser = FinanceParser(),
    client: OkHttpClient = defaultClient(),
    private val baseUrl: HttpUrl = OFFICIAL_BASE_URL.toHttpUrl()
) : FinanceGateway {
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
    private val loginUrl = baseUrl.resolve("/home/login")!!.toString()
    private val indexUrl = baseUrl.resolve("/home/index")!!.toString()
    private val interfaceUrl = baseUrl.resolve("/interface/index")!!.toString()
    private val origin = OFFICIAL_BASE_URL

    override suspend fun captcha(): FinanceCaptcha = withContext(Dispatchers.IO) {
        var lastFailure: FinanceFailure.Network? = null
        repeat(CAPTCHA_ATTEMPTS) { attempt ->
            try {
                return@withContext captchaOnce()
            } catch (error: FinanceFailure.Network) {
                lastFailure = error
                if (attempt == CAPTCHA_ATTEMPTS - 1) throw error
            }
        }
        throw checkNotNull(lastFailure)
    }

    private fun captchaOnce(): FinanceCaptcha {
        var result = requestAt(FinanceNetworkStage.LOGIN_PAGE, loginUrl)
        var cookie = result.cookie
        cookie = postLoginConfig(cookie, "loginauthtype", FinanceNetworkStage.AUTH_CONFIG)
        cookie = postLoginConfig(cookie, "usernameinputtips", FinanceNetworkStage.USERNAME_TIPS)
        result = requestAt(
            FinanceNetworkStage.CAPTCHA_IMAGE,
            baseUrl.resolve("/interface/getVerifyCode?${System.currentTimeMillis()}")!!.toString(),
            cookie,
            referer = loginUrl,
            accept = "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
        )
        if (result.bytes.size < 10) throw FinanceFailure.Upstream("财务验证码图片响应为空")
        val mime = result.contentType.substringBefore(';').ifBlank { "image/jpeg" }
        return FinanceCaptcha("data:$mime;base64,${Base64.getEncoder().encodeToString(result.bytes)}", result.cookie)
    }

    override suspend fun login(username: String, password: String, captcha: String, cookie: String): FinanceResponse = withContext(Dispatchers.IO) {
        if (username.isBlank() || password.isBlank() || captcha.isBlank()) {
            throw FinanceFailure.CredentialsInvalid("请输入学号、财务密码和验证码")
        }
        val initialized = postLoginConfig(cookie, "loginauthtype", FinanceNetworkStage.AUTH_CONFIG)
        val result = requestAt(
            FinanceNetworkStage.LOGIN_SUBMIT,
            baseUrl.resolve("/interface/login")!!.toString(),
            initialized,
            mapOf(
                "sid" to username.trim(),
                "passWord" to Base64.getEncoder().encodeToString(password.toByteArray()),
                "verifycode" to captcha.trim(),
                "ismobile" to "0"
            ),
            loginUrl
        )
        val json = runCatching { JSONObject(result.body.trim()) }.getOrElse { throw FinanceFailure.Parse() }
        if (json.optString("state") != "200" && !json.optBoolean("success", false)) {
            val message = listOf(json.optString("data"), json.optString("message"), json.optString("msg"))
                .firstOrNull { it.isNotBlank() } ?: "财务账号或密码错误"
            if (message.contains("验证码")) throw FinanceFailure.CaptchaInvalid(message)
            throw FinanceFailure.CredentialsInvalid(message)
        }
        FinanceResponse(cookie = result.cookie)
    }

    override suspend fun fetch(module: FinanceModule, cookie: String, page: Int, pageSize: Int): FinanceResponse = withContext(Dispatchers.IO) {
        if (cookie.isBlank()) throw FinanceFailure.SessionExpired()
        if (module == FinanceModule.OVERVIEW) {
            val personal = postInterface(cookie, FinanceRequests.module(FinanceModule.OVERVIEW))
            val pending = postInterface(personal.cookie, FinanceRequests.module(FinanceModule.PENDING))
            val value = JSONObject().put("personal", personal.data).put("pending", pending.data)
            return@withContext FinanceResponse(parser.parse(module, value), pending.cookie)
        }
        val result = postInterface(cookie, FinanceRequests.module(module, page, pageSize))
        var payload = parser.parse(module, result.data)
        val total = (result.data as? JSONObject)?.optInt("total", -1)?.takeIf { it >= 0 }
            ?: (payload as? FinancePayload.Items)?.values?.size.orZero()
        val hasMore = payload is FinancePayload.Items && page * pageSize < total
        if (payload is FinancePayload.Items) payload = payload.copy(page = page, total = total, hasMore = hasMore)
        FinanceResponse(payload, result.cookie, page, total, hasMore)
    }

    override suspend fun ticketImage(cookie: String, chargeId: String, receiptNo: String): FinanceResponse = withContext(Dispatchers.IO) {
        if (chargeId.isBlank() || receiptNo.isBlank() || chargeId.length > 80 || receiptNo.length > 80) {
            throw FinanceFailure.Upstream("电子票据参数无效")
        }
        val result = postInterface(cookie, mapOf("method" to "getoneeticketimage", "stuid" to "1", "ChargeID" to chargeId, "BillNo" to receiptNo))
        val root = result.data as? JSONObject
        val image = listOf("image", "Image", "data").firstNotNullOfOrNull { key -> root?.optString(key)?.takeIf(String::isNotBlank) }
            ?: result.data?.toString().orEmpty()
        val dataUrl = if (image.startsWith("data:")) image else "data:image/jpeg;base64,$image"
        FinanceResponse(FinancePayload.TicketImage(dataUrl), result.cookie)
    }

    private fun postLoginConfig(cookie: String, method: String, stage: FinanceNetworkStage): String {
        val result = requestAt(stage, interfaceUrl, cookie, mapOf("method" to method), loginUrl)
        FinanceResponses.unwrap(result.body, result.finalUrl)
        return result.cookie
    }

    private fun postInterface(cookie: String, fields: Map<String, String>): ParsedResult {
        val stage = if (fields["method"] == "getoneeticketimage") FinanceNetworkStage.TICKET_IMAGE else FinanceNetworkStage.MODULE_FETCH
        val result = requestAt(stage, interfaceUrl, cookie, fields, indexUrl)
        return ParsedResult(FinanceResponses.unwrap(result.body, result.finalUrl), result.cookie)
    }

    private fun requestAt(
        stage: FinanceNetworkStage,
        url: String,
        cookie: String = "",
        fields: Map<String, String>? = null,
        referer: String = "",
        accept: String = "application/json, text/javascript, */*; q=0.01"
    ): HttpResult = try {
        request(url, cookie, fields, referer, accept)
    } catch (error: CancellationException) {
        throw error
    } catch (error: FinanceFailure) {
        throw error
    } catch (error: IOException) {
        logNetworkFailure(stage, error)
        throw FinanceFailure.Network(stage, error.javaClass.simpleName, "${stage.label}失败：${networkMessage(error)}", error)
    }

    private fun request(
        initialUrl: String,
        initialCookie: String = "",
        fields: Map<String, String>? = null,
        referer: String = "",
        accept: String = "application/json, text/javascript, */*; q=0.01"
    ): HttpResult {
        var url = initialUrl
        var cookie = initialCookie
        var methodFields = fields
        repeat(4) {
            val builder = Request.Builder().url(url)
                .header("Accept", accept)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("User-Agent", "GLUT-Schedule/0.18.0 (Android)")
            if (cookie.isNotBlank()) builder.header("Cookie", cookie)
            if (referer.isNotBlank()) builder.header("Referer", referer)
            if (methodFields != null) {
                val form = FormBody.Builder().apply { methodFields!!.forEach { (key, value) -> add(key, value) } }.build()
                builder.header("X-Requested-With", "XMLHttpRequest").header("Origin", origin).post(form)
            }
            client.newCall(builder.build()).execute().use { response ->
                cookie = FinanceCookies.merge(cookie, response.headers.values("Set-Cookie"))
                if (response.code in REDIRECT_CODES) {
                    val location = response.header("Location") ?: throw FinanceFailure.Upstream("财务系统跳转地址为空")
                    val next = response.request.url.resolve(location) ?: throw FinanceFailure.Upstream("财务系统跳转地址无效")
                    if (!FinanceResponses.isSafeRedirect(next.toString(), baseUrl.host, baseUrl.scheme, baseUrl.port)) throw FinanceFailure.Upstream("财务系统返回了不安全的跳转地址")
                    url = next.toString()
                    if (response.code in REDIRECT_TO_GET_CODES) methodFields = null
                    return@use
                }
                val responseBody = response.body
                val maxBytes = if (accept.startsWith("image/")) MAX_IMAGE_BYTES else MAX_JSON_BYTES
                val contentLength = responseBody?.contentLength() ?: 0L
                if (contentLength > maxBytes) throw FinanceFailure.Upstream("财务系统响应数据过大")
                val body = responseBody?.let { readBounded(it, maxBytes) } ?: ByteArray(0)
                if (body.size > maxBytes) throw FinanceFailure.Upstream("财务系统响应数据过大")
                return HttpResult(body.toString(Charsets.UTF_8), body, response.header("Content-Type").orEmpty(), cookie, response.request.url.toString())
            }
        }
        throw FinanceFailure.Upstream("财务系统跳转次数过多")
    }

    private data class ParsedResult(val data: Any?, val cookie: String)
    private data class HttpResult(val body: String, val bytes: ByteArray, val contentType: String, val cookie: String, val finalUrl: String)

    private fun readBounded(body: ResponseBody, maxBytes: Int): ByteArray {
        val source = body.source()
        val buffer = Buffer()
        while (buffer.size <= maxBytes) {
            val remaining = maxBytes + 1L - buffer.size
            val read = source.read(buffer, minOf(8_192L, remaining))
            if (read == -1L) break
        }
        return buffer.readByteArray()
    }

    private fun networkMessage(error: IOException): String = when (error) {
        is UnknownHostException -> "无法解析财务官网地址，请检查网络后重试"
        is SocketTimeoutException -> "连接财务官网超时，请稍后重试"
        is SSLException -> "设备与财务官网安全连接失败，请检查系统时间或网络"
        is ConnectException -> "无法连接财务官网，请检查网络后重试"
        else -> "财务网络连接异常，请稍后重试"
    }

    private fun logNetworkFailure(stage: FinanceNetworkStage, error: IOException) {
        runCatching { Log.w(TAG, "stage=${stage.name} type=${error.javaClass.simpleName}") }
    }

    companion object {
        private const val OFFICIAL_BASE_URL = "https://cwjf.glut.edu.cn"
        private const val TAG = "FinanceApi"
        private const val CAPTCHA_ATTEMPTS = 2
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val REDIRECT_TO_GET_CODES = setOf(301, 302, 303)
        private const val MAX_JSON_BYTES = 2 * 1024 * 1024
        private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024

        private fun defaultClient() = OkHttpClient.Builder()
            .dns(FinanceDns)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .build()
    }
}

private fun Int?.orZero(): Int = this ?: 0
