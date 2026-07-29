package com.glut.schedule.partner

import com.glut.schedule.BuildConfig
import com.glut.schedule.service.network.readStringLimited
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

data class PartnerInvite(
    val code: String,
    val revokeToken: String,
    val expiresAt: String
)

private const val QR_PREFIX = "GLUT-SCHEDULE:V1:"
private val inviteCodePattern = Regex("""[A-Z2-9]{16}""")

fun inviteCodeFromInput(input: String): String {
    val normalized = input.trim().uppercase(Locale.ROOT)
    val code = normalized.removePrefix(QR_PREFIX)
    require(inviteCodePattern.matches(code)) { "请输入有效的16位邀请码" }
    return code
}

fun inviteQrPayload(code: String): String = QR_PREFIX + inviteCodeFromInput(code)

interface PartnerScheduleGateway {
    suspend fun createInvite(snapshot: PartnerScheduleSnapshot): PartnerInvite
    suspend fun fetchInvite(input: String): PartnerScheduleSnapshot
    suspend fun revokeInvite(input: String, revokeToken: String)
}

class PartnerScheduleApiService(
    private val client: OkHttpClient = OkHttpClient(),
    baseUrl: String = BuildConfig.SCHEDULE_SHARE_BASE_URL
) : PartnerScheduleGateway {
    private val apiBaseUrl = baseUrl.trimEnd('/')

    override suspend fun createInvite(snapshot: PartnerScheduleSnapshot): PartnerInvite = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("snapshot", JSONObject(PartnerScheduleSnapshotCodec.encode(snapshot)))
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("$apiBaseUrl/v1/invites")
            .post(body)
            .build()
        executeJson(request, expectedCode = 201) { json ->
            PartnerInvite(
                code = inviteCodeFromInput(json.getString("code")),
                revokeToken = json.getString("revokeToken"),
                expiresAt = json.getString("expiresAt")
            )
        }
    }

    override suspend fun fetchInvite(input: String): PartnerScheduleSnapshot = withContext(Dispatchers.IO) {
        val code = inviteCodeFromInput(input)
        val request = Request.Builder()
            .url("$apiBaseUrl/v1/invites/$code")
            .get()
            .build()
        executeJson(request, expectedCode = 200) { json ->
            PartnerScheduleSnapshotCodec.decode(json.getJSONObject("snapshot").toString())
        }
    }

    override suspend fun revokeInvite(input: String, revokeToken: String) = withContext(Dispatchers.IO) {
        require(revokeToken.isNotBlank()) { "撤销令牌不能为空" }
        val code = inviteCodeFromInput(input)
        val request = Request.Builder()
            .url("$apiBaseUrl/v1/invites/$code")
            .header("Authorization", "Bearer $revokeToken")
            .delete()
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code != 204) {
                throw IOException(apiErrorMessage(response.code))
            }
        }
    }

    private fun <T> executeJson(request: Request, expectedCode: Int, mapper: (JSONObject) -> T): T {
        return client.newCall(request).execute().use { response ->
            if (response.code != expectedCode) throw IOException(apiErrorMessage(response.code))
            val raw = response.body?.readStringLimited(MAX_RESPONSE_BYTES)
                ?: throw IOException("共享服务返回空响应")
            runCatching { mapper(JSONObject(raw)) }
                .getOrElse { throw IOException("共享服务返回格式异常", it) }
        }
    }

    private fun apiErrorMessage(status: Int): String = when (status) {
        400 -> "邀请码或课表数据无效"
        404 -> "邀请码不存在、已过期或已撤销"
        413 -> "课表数据过大"
        429 -> "操作过于频繁，请稍后再试"
        else -> "共享服务暂时不可用（$status）"
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://share-schedule-999314.xyz"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_RESPONSE_BYTES = 512 * 1024
    }
}
