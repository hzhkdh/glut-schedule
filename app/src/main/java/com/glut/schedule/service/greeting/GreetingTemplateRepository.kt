package com.glut.schedule.service.greeting

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class GreetingTemplateCacheSnapshot(
    val rawJson: String,
    val lastSuccessEpochMillis: Long,
    val lastAttemptEpochMillis: Long
)

interface GreetingTemplateCache {
    suspend fun read(): GreetingTemplateCacheSnapshot
    suspend fun recordAttempt(nowEpochMillis: Long)
    suspend fun saveSuccess(rawJson: String, nowEpochMillis: Long)
}

interface GreetingTemplateRemote {
    suspend fun fetch(): String?
}

class GreetingTemplateRepository(
    private val cache: GreetingTemplateCache,
    private val remote: GreetingTemplateRemote
) {
    private val builtIn = builtInGreetingTemplates()
    private val refreshMutex = Mutex()
    private val _templates = MutableStateFlow(builtIn)
    val templates: StateFlow<GreetingTemplateSet> = _templates

    suspend fun initializeAndRefresh(nowEpochMillis: Long = System.currentTimeMillis()): Boolean =
        refreshMutex.withLock {
            val snapshot = cache.read()
            val cachedDocument = GreetingTemplateParser.parse(snapshot.rawJson)
                ?.takeIf { it.templates.hasAnyTemplate() }
            _templates.value = cachedDocument?.templates?.let(builtIn::overlay) ?: builtIn

            val successIsFresh = cachedDocument != null &&
                snapshot.lastSuccessEpochMillis > 0L &&
                nowEpochMillis - snapshot.lastSuccessEpochMillis < SUCCESS_TTL_MILLIS
            val attemptIsRecent = snapshot.lastAttemptEpochMillis > 0L &&
                nowEpochMillis - snapshot.lastAttemptEpochMillis < FAILURE_RETRY_MILLIS
            if (successIsFresh || attemptIsRecent) return false

            cache.recordAttempt(nowEpochMillis)
            val rawJson = remote.fetch() ?: return false
            val remoteDocument = GreetingTemplateParser.parse(rawJson)
                ?.takeIf { it.templates.hasAnyTemplate() }
                ?: return false
            cache.saveSuccess(rawJson, nowEpochMillis)
            _templates.value = builtIn.overlay(remoteDocument.templates)
            true
        }

    companion object {
        const val SUCCESS_TTL_MILLIS = 24L * 60L * 60L * 1000L
        const val FAILURE_RETRY_MILLIS = 60L * 60L * 1000L
    }
}

class HttpGreetingTemplateRemote(
    private val url: String = "https://update.999314.xyz/greetings.json",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) : GreetingTemplateRemote {
    override suspend fun fetch(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "GlutSchedule-Greetings")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                val declaredLength = body.contentLength()
                if (declaredLength > GreetingTemplateParser.MAX_JSON_BYTES) return@withContext null
                body.string().takeIf {
                    it.toByteArray(Charsets.UTF_8).size <= GreetingTemplateParser.MAX_JSON_BYTES
                }
            }
        }.getOrNull()
    }
}
