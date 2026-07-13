package com.glut.schedule.service.fitness

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class FitnessApiResponse(
    val success: Boolean,
    val code: String = "",
    val message: String = "",
    val fitnessCookie: String = "",
    val captchaImage: String = "",
    val currentHtml: String = "",
    val historyHtml: String = "",
    val detailHtml: String = "",
    val standardHtml: String = ""
)

class FitnessApiService(
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: String = DEFAULT_BASE_URL
) {
    suspend fun getCaptcha(cookie: String): FitnessApiResponse =
        post("getFitnessCaptcha", mapOf("fitnessCookie" to cookie))

    suspend fun login(
        username: String,
        password: String,
        captcha: String,
        cookie: String
    ): FitnessApiResponse = post(
        "loginAndFetchFitnessScores",
        mapOf(
            "username" to username.trim(),
            "password" to password,
            "captcha" to captcha.trim(),
            "fitnessCookie" to cookie
        )
    )

    suspend fun refresh(cookie: String): FitnessApiResponse =
        post("refreshFitnessScores", mapOf("fitnessCookie" to cookie))

    suspend fun getHistoryDetail(cookie: String, academicYear: String, term: String): FitnessApiResponse =
        post(
            "getFitnessHistoryDetail",
            mapOf("fitnessCookie" to cookie, "academicYear" to academicYear, "term" to term)
        )

    suspend fun getStandard(cookie: String): FitnessApiResponse =
        post("getFitnessScoringStandard", mapOf("fitnessCookie" to cookie))

    private suspend fun post(action: String, fields: Map<String, String>): FitnessApiResponse =
        withContext(Dispatchers.IO) {
            val bodyJson = JSONObject().apply { fields.forEach { (key, value) -> put(key, value) } }
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/$action")
                .header("Accept", "application/json")
                .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(request).execute().use { response ->
                val responseText = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IOException("服务器错误 ${response.code}")
                val json = runCatching { JSONObject(responseText) }
                    .getOrElse { throw IOException("服务器返回格式错误") }
                FitnessApiResponse(
                    success = json.optBoolean("success", false),
                    code = json.optString("code"),
                    message = json.optString("message"),
                    fitnessCookie = json.optString("fitnessCookie"),
                    captchaImage = json.optString("captchaImage"),
                    currentHtml = json.optString("currentHtml"),
                    historyHtml = json.optString("historyHtml"),
                    detailHtml = json.optString("detailHtml"),
                    standardHtml = json.optString("standardHtml")
                )
            }
        }

    companion object {
        const val DEFAULT_BASE_URL = "https://glut-api.999314.xyz"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .build()
    }
}
