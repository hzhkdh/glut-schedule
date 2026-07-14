package com.glut.schedule.service.finance

import com.glut.schedule.data.model.FinanceModule
import com.glut.schedule.data.model.FinancePayload
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
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
    client: OkHttpClient = defaultClient()
) : FinanceGateway {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    override suspend fun captcha(): FinanceCaptcha = withContext(Dispatchers.IO) {
        var result = request(LOGIN_URL)
        var cookie = result.cookie
        cookie = postLoginConfig(cookie, "loginauthtype")
        cookie = postLoginConfig(cookie, "usernameinputtips")
        result = request("$BASE_URL/interface/getVerifyCode?${System.currentTimeMillis()}", cookie, referer = LOGIN_URL, accept = "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
        if (result.bytes.size < 10) throw FinanceFailure.Upstream("财务验证码图片响应为空")
        val mime = result.contentType.substringBefore(';').ifBlank { "image/jpeg" }
        FinanceCaptcha("data:$mime;base64,${Base64.getEncoder().encodeToString(result.bytes)}", result.cookie)
    }

    override suspend fun login(username: String, password: String, captcha: String, cookie: String): FinanceResponse = withContext(Dispatchers.IO) {
        if (username.isBlank() || password.isBlank() || captcha.isBlank()) {
            throw FinanceFailure.CredentialsInvalid("请输入学号、财务密码和验证码")
        }
        val initialized = postLoginConfig(cookie, "loginauthtype")
        val result = request(
            "$BASE_URL/interface/login",
            initialized,
            mapOf(
                "sid" to username.trim(),
                "passWord" to Base64.getEncoder().encodeToString(password.toByteArray()),
                "verifycode" to captcha.trim(),
                "ismobile" to "0"
            ),
            LOGIN_URL
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

    private fun postLoginConfig(cookie: String, method: String): String {
        val result = request(INTERFACE_URL, cookie, mapOf("method" to method), LOGIN_URL)
        FinanceResponses.unwrap(result.body, result.finalUrl)
        return result.cookie
    }

    private fun postInterface(cookie: String, fields: Map<String, String>): ParsedResult {
        val result = request(INTERFACE_URL, cookie, fields, INDEX_URL)
        return ParsedResult(FinanceResponses.unwrap(result.body, result.finalUrl), result.cookie)
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
            val builder = Request.Builder().url(url).header("Accept", accept)
            if (cookie.isNotBlank()) builder.header("Cookie", cookie)
            if (referer.isNotBlank()) builder.header("Referer", referer)
            if (methodFields != null) {
                val form = FormBody.Builder().apply { methodFields!!.forEach { (key, value) -> add(key, value) } }.build()
                builder.header("X-Requested-With", "XMLHttpRequest").post(form)
            }
            client.newCall(builder.build()).execute().use { response ->
                cookie = FinanceCookies.merge(cookie, response.headers.values("Set-Cookie"))
                if (response.code in REDIRECT_CODES) {
                    val location = response.header("Location") ?: throw FinanceFailure.Upstream("财务系统跳转地址为空")
                    val next = response.request.url.resolve(location) ?: throw FinanceFailure.Upstream("财务系统跳转地址无效")
                    if (next.host != "cwjf.glut.edu.cn") throw FinanceFailure.Upstream("财务系统返回了不安全的跳转地址")
                    url = next.toString()
                    if (response.code == 303) methodFields = null
                    return@use
                }
                val body = response.body?.bytes() ?: ByteArray(0)
                return HttpResult(body.toString(Charsets.UTF_8), body, response.header("Content-Type").orEmpty(), cookie, response.request.url.toString())
            }
        }
        throw FinanceFailure.Upstream("财务系统跳转次数过多")
    }

    private data class ParsedResult(val data: Any?, val cookie: String)
    private data class HttpResult(val body: String, val bytes: ByteArray, val contentType: String, val cookie: String, val finalUrl: String)

    companion object {
        private const val BASE_URL = "https://cwjf.glut.edu.cn"
        private const val LOGIN_URL = "$BASE_URL/home/login"
        private const val INDEX_URL = "$BASE_URL/home/index"
        private const val INTERFACE_URL = "$BASE_URL/interface/index"
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .build()
    }
}

private fun Int?.orZero(): Int = this ?: 0
