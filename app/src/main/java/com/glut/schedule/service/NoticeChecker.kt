package com.glut.schedule.service

import com.glut.schedule.data.model.NoticeAttachment
import com.glut.schedule.data.model.NoticeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.TimeUnit

data class NoticeFetchResult(
    val notices: List<NoticeInfo>,
    val rawJson: String
)

class NoticeChecker(
    private val noticesUrl: String = "https://update.999314.xyz/notices.json"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun check(): NoticeFetchResult? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(noticesUrl)
                .header("User-Agent", "GlutSchedule-Notices")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                NoticeFetchResult(
                    notices = parseNotices(body),
                    rawJson = body
                )
            }
        }.getOrNull()
    }

    companion object {
        fun parseNotices(json: String, today: LocalDate = LocalDate.now()): List<NoticeInfo> {
            if (json.isBlank()) return emptyList()

            return runCatching {
                val root = JSONObject(json)
                val items = root.optJSONArray("notices") ?: return emptyList()
                buildList {
                    for (index in 0 until items.length()) {
                        val obj = items.optJSONObject(index) ?: continue
                        val id = obj.optString("id").trim()
                        val title = obj.optString("title").trim()
                        if (id.isBlank() || title.isBlank()) continue

                        val publishedAt = parseDate(obj.optString("publishedAt"))
                        val expiresAt = parseOptionalDate(obj.optString("expiresAt"))
                        if (expiresAt != null && expiresAt.isBefore(today)) continue

                        add(
                            NoticeInfo(
                                id = id,
                                title = title,
                                content = obj.optString("content").trim(),
                                level = obj.optString("level", "info").trim().ifBlank { "info" },
                                publishedAt = publishedAt,
                                expiresAt = expiresAt,
                                url = obj.optString("url").trim(),
                                attachments = parseAttachments(obj)
                            )
                        )
                    }
                }.sortedWith(
                    compareByDescending<NoticeInfo> { it.publishedAt }
                        .thenByDescending { it.id }
                )
            }.getOrDefault(emptyList())
        }

        private fun parseDate(value: String): LocalDate {
            return parseOptionalDate(value) ?: LocalDate.MIN
        }

        private fun parseAttachments(obj: JSONObject): List<NoticeAttachment> {
            val items = obj.optJSONArray("attachments") ?: return emptyList()
            return buildList {
                for (index in 0 until items.length()) {
                    val attachment = items.optJSONObject(index) ?: continue
                    val name = attachment.optString("name").trim()
                    val url = attachment.optString("url").trim()
                    if (name.isBlank() || url.isBlank()) continue

                    add(
                        NoticeAttachment(
                            name = name,
                            url = url,
                            type = attachment.optString("type").trim()
                        )
                    )
                }
            }
        }

        private fun parseOptionalDate(value: String): LocalDate? {
            val trimmed = value.trim()
            if (trimmed.isBlank()) return null
            return runCatching { LocalDate.parse(trimmed) }.getOrNull()
        }
    }
}
