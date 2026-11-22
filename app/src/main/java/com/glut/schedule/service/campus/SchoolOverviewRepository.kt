package com.glut.schedule.service.campus

import com.glut.schedule.service.network.awaitResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

enum class SchoolOverviewTopic(val key: String, val title: String) {
    LEADERS("leaders", "领导班子"),
    HISTORY("history", "历史沿革"),
    SPIRIT("spirit", "桂工精神"),
    SONG("song", "桂工之歌")
}

data class SchoolOverviewSource(val label: String, val url: String)
data class SchoolLeader(val id: String, val name: String, val role: String, val photoUrl: String, val duties: String)
data class SchoolHistoryEvent(val date: String, val text: String)
data class SchoolHistoryPeriod(val id: String, val title: String, val range: String, val events: List<SchoolHistoryEvent>)
data class SchoolSpirit(
    val photoUrl: String,
    val phrases: List<String>,
    val description: String,
    val motto: String,
    val mottoDescription: String
)

data class SchoolSongNote(val term: String, val text: String)
data class SchoolSongMottoSection(val title: String, val paragraphs: List<String>)
data class SchoolSong(
    val title: String,
    val videoUrl: String,
    val lyrics: List<String>,
    val notes: List<SchoolSongNote>,
    val meaning: List<String>,
    val mottoSections: List<SchoolSongMottoSection>
)

data class SchoolOverviewDocument(
    val fetchedAt: Long,
    val sources: List<SchoolOverviewSource>,
    val people: List<SchoolLeader> = emptyList(),
    val periods: List<SchoolHistoryPeriod> = emptyList(),
    val spirit: SchoolSpirit? = null,
    val diagramUrl: String? = null,
    val song: SchoolSong? = null
)

class SchoolOverviewRepository(
    private val directory: File,
    private val baseUrl: HttpUrl = "https://glut-api.999314.xyz/".toHttpUrl(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .callTimeout(25, TimeUnit.SECONDS)
        .build(),
    private val now: () -> Long = System::currentTimeMillis
) {
    private class TopicCache {
        val lock = Mutex()
        val document = MutableStateFlow<SchoolOverviewDocument?>(null)
        var hydrated = false
        val lastRequestVersion = AtomicLong()
    }

    private val caches = SchoolOverviewTopic.entries.associateWith { TopicCache() }
    private val imageCacheLock = Mutex()

    fun observe(topic: SchoolOverviewTopic): StateFlow<SchoolOverviewDocument?> =
        caches.getValue(topic).document.asStateFlow()

    suspend fun loadCached(topic: SchoolOverviewTopic) {
        val cache = caches.getValue(topic)
        cache.lock.withLock { hydrate(topic, cache) }
    }

    suspend fun refresh(topic: SchoolOverviewTopic, force: Boolean = false): SchoolOverviewDocument {
        val cache = caches.getValue(topic)
        // 手动与定时刷新同时到达时，等待正在执行的同栏目请求，复用其成功结果。
        val version = cache.lastRequestVersion.get()
        return cache.lock.withLock {
            val current = cache.document.value
            if (current != null && cache.lastRequestVersion.get() != version) {
                return@withLock current
            }
            hydrate(topic, cache)
            val existing = cache.document.value
            // 旧校史快照仍可阅读，首次进入时即请求补齐沿革图。
            val needsDiagram = topic == SchoolOverviewTopic.HISTORY && existing?.diagramUrl == null
            if (!force && !needsDiagram && existing != null && now() - existing.fetchedAt in 0 until FRESH_FOR_MS) {
                return@withLock existing
            }
            val url = baseUrl.newBuilder().addPathSegments("school-overview/${topic.key}").apply {
                if (force) addQueryParameter("refresh", "1")
            }.build()
            val raw = client.newCall(Request.Builder().url(url).build()).awaitResponse { response ->
                if (!response.isSuccessful) throw IOException("学校概况请求失败")
                val source = response.body.source()
                if (source.request(MAX_BYTES + 1)) throw IOException("学校概况响应过大")
                source.readUtf8()
            }
            val document = decode(topic, raw)
            if (existing != null && document.fetchedAt < existing.fetchedAt) return@withLock existing
            withContext(Dispatchers.IO) { replaceFile(cacheFile(topic), raw.toByteArray()) }
            cache.document.value = document
            cache.lastRequestVersion.incrementAndGet()
            document
        }
    }

    private suspend fun hydrate(topic: SchoolOverviewTopic, cache: TopicCache) {
        if (cache.hydrated) return
        cache.document.value = withContext(Dispatchers.IO) {
            val file = cacheFile(topic)
            try {
                file.takeIf { it.isFile && it.length() <= MAX_BYTES }?.let { decode(topic, it.readText()) }
            } catch (_: IOException) {
                null
            }
        }
        cache.hydrated = true
    }

    private fun cacheFile(topic: SchoolOverviewTopic) = directory.resolve("${topic.key}.json")

    suspend fun loadImage(url: String): ByteArray? {
        val parsed = validatedPhotoUrl(url) ?: return null
        val imageDirectory = directory.resolve("photos")
        val file = imageDirectory.resolve("${parsed.pathSegments[2]}-${parsed.pathSegments[3]}.image")
        return try {
            val cached = withContext(Dispatchers.IO) {
                file.takeIf { it.isFile && it.length() in 1..MAX_IMAGE_BYTES }?.readBytes()
                    ?.takeIf(::hasSupportedImageSignature)
            }
            if (cached != null) return cached
            val bytes = client.newCall(Request.Builder().url(parsed).build()).awaitResponse { response ->
                val body = response.body
                if (!response.isSuccessful || body.contentType()?.type != "image") throw IOException("学校概况图片加载失败")
                val source = body.source()
                if (source.request(MAX_IMAGE_BYTES + 1)) throw IOException("学校概况图片过大")
                source.readByteArray().takeIf(::hasSupportedImageSignature) ?: throw IOException("学校概况图片格式有误")
            }
            imageCacheLock.withLock {
                withContext(Dispatchers.IO) {
                    replaceFile(file, bytes)
                    // 限制旧照片版本的磁盘占用，当前图片与网络请求本身不受清理结果影响。
                    imageDirectory.listFiles { entry -> entry.extension == "image" }
                        ?.sortedByDescending(File::lastModified)?.drop(MAX_CACHED_IMAGES)?.forEach(File::delete)
                }
            }
            bytes
        } catch (_: IOException) {
            null
        }
    }

    private fun replaceFile(file: File, bytes: ByteArray) {
        val parent = requireNotNull(file.parentFile).toPath()
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, file.name, ".tmp")
        try {
            Files.write(temporary, bytes)
            // 校验通过才原子替换，网络或写盘中断时仍能读取上一份完整快照。
            Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun decode(topic: SchoolOverviewTopic, raw: String): SchoolOverviewDocument = try {
        val json = JSONObject(raw)
        check(json.getString("topic") == topic.key)
        val fetchedAt = json.getLong("fetchedAt").also { check(it > 0) }
        val sources = json.getJSONArray("sources").objects {
            val url = requiredText("url")
            val parsed = url.toHttpUrlOrNull()
            check(parsed?.isHttps == true && parsed.host == "www.glut.edu.cn")
            SchoolOverviewSource(requiredText("label"), url)
        }
        val data = json.getJSONObject("data")
        when (topic) {
            SchoolOverviewTopic.LEADERS -> SchoolOverviewDocument(fetchedAt, sources, people = data.getJSONArray("people").objects {
                SchoolLeader(requiredText("id"), requiredText("name"), requiredText("role"), photoUrl(topic), requiredText("duties"))
            }.also { people -> check(people.distinctBy { it.id }.size == people.size) })
            SchoolOverviewTopic.HISTORY -> SchoolOverviewDocument(fetchedAt, sources, periods = data.getJSONArray("periods").objects {
                SchoolHistoryPeriod(requiredText("id"), requiredText("title"), requiredText("range"), getJSONArray("events").objects {
                    SchoolHistoryEvent(getString("date"), requiredText("text"))
                })
            }.also { periods -> check(periods.size == 4 && periods.distinctBy { it.id }.size == periods.size) },
                diagramUrl = data.optString("diagramUrl", "").takeIf { it.isNotBlank() }?.also {
                    check(validatedPhotoUrl(it)?.pathSegments?.get(2) == topic.key)
                })
            SchoolOverviewTopic.SPIRIT -> SchoolOverviewDocument(fetchedAt, sources, spirit = with(data) {
                val phrases = getJSONArray("phrases").let { array -> List(array.length()) { array.getString(it) } }
                check(phrases.size == 4 && phrases.all(String::isNotBlank))
                SchoolSpirit(photoUrl(topic), phrases, requiredText("description"), requiredText("motto"), requiredText("mottoDescription"))
            })
            SchoolOverviewTopic.SONG -> SchoolOverviewDocument(fetchedAt, sources, song = with(data) {
                val mediaUrl = requiredText("videoUrl")
                check(sameOriginMediaUrl(mediaUrl)?.encodedPath == "/school-overview/media/song")
                SchoolSong(
                    title = requiredText("title"),
                    videoUrl = mediaUrl,
                    lyrics = getJSONArray("lyrics").lines(),
                    notes = getJSONArray("notes").objects {
                        SchoolSongNote(requiredText("term"), requiredText("text"))
                    },
                    meaning = getJSONArray("meaning").lines(),
                    mottoSections = getJSONArray("mottoSections").objects {
                        SchoolSongMottoSection(requiredText("title"), getJSONArray("paragraphs").lines())
                    }
                )
            })
        }
    } catch (failure: JSONException) {
        throw IOException("学校概况内容格式有误", failure)
    } catch (failure: IllegalStateException) {
        throw IOException("学校概况内容校验失败", failure)
    }

    private fun JSONObject.photoUrl(topic: SchoolOverviewTopic): String = getString("photoUrl").also { photo ->
        if (photo.isNotEmpty()) {
            check(validatedPhotoUrl(photo)?.pathSegments?.get(2) == topic.key)
        }
    }

    private fun validatedPhotoUrl(url: String): HttpUrl? =
        sameOriginMediaUrl(url)?.takeIf { PHOTO_PATH.matches(it.encodedPath) }

    // 图片和视频均使用 Worker 的固定同源路径，缓存中的外部地址不会交给播放器。
    private fun sameOriginMediaUrl(url: String): HttpUrl? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        return parsed.takeIf {
            it.scheme == baseUrl.scheme && it.host == baseUrl.host && it.port == baseUrl.port &&
                it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null
        }
    }

    private fun JSONObject.requiredText(key: String): String = getString(key).also { check(it.isNotBlank()) }

    private fun JSONArray.lines(): List<String> = List(length()) { getString(it) }
        .also { entries -> check(entries.isNotEmpty() && entries.all(String::isNotBlank)) }

    private fun <T> JSONArray.objects(transform: JSONObject.() -> T): List<T> {
        check(length() > 0)
        return List(length()) { getJSONObject(it).transform() }
    }

    private companion object {
        const val MAX_BYTES = 1024L * 1024
        const val MAX_IMAGE_BYTES = 10L * 1024 * 1024
        const val MAX_CACHED_IMAGES = 32
        const val FRESH_FOR_MS = 30L * 60 * 1000
        val PHOTO_PATH = Regex("/school-overview/photo/(leaders|history|spirit)/[0-9a-f]{32}")
    }
}
