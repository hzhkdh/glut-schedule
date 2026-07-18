package com.glut.schedule.service.campus

import java.io.File
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

enum class CampusImageType {
    ACADEMIC_CALENDAR,
    SHUTTLE_BUS
}

data class CampusImageDocument(
    val imageUrl: String,
    val bytes: ByteArray,
    val fetchedAt: Long,
    val fromCache: Boolean = false
)

interface CampusImageGateway {
    suspend fun fetch(type: CampusImageType, forceRefresh: Boolean = false): CampusImageDocument
}

interface CampusImageCache {
    fun load(type: CampusImageType): CampusImageDocument?
    fun save(type: CampusImageType, document: CampusImageDocument)
}

class CampusImageFileCache(private val directory: File) : CampusImageCache {
    override fun load(type: CampusImageType): CampusImageDocument? = runCatching {
        val file = cacheFile(type)
        if (!file.isFile) return null
        DataInputStream(file.inputStream().buffered()).use { input ->
            if (input.readInt() != CACHE_MAGIC) return null
            val imageUrl = input.readUTF()
            val fetchedAt = input.readLong()
            val length = input.readInt()
            if (length !in 1..MAX_CAMPUS_IMAGE_BYTES) return null
            val bytes = ByteArray(length).also(input::readFully)
            if (!hasSupportedImageSignature(bytes)) return null
            CampusImageDocument(
                imageUrl = imageUrl,
                bytes = bytes,
                fetchedAt = fetchedAt,
                fromCache = true
            )
        }
    }.getOrNull()

    override fun save(type: CampusImageType, document: CampusImageDocument) {
        directory.mkdirs()
        val target = cacheFile(type)
        val temp = File(directory, "${target.name}.tmp")
        try {
            DataOutputStream(temp.outputStream().buffered()).use { output ->
                output.writeInt(CACHE_MAGIC)
                output.writeUTF(document.imageUrl)
                output.writeLong(document.fetchedAt)
                output.writeInt(document.bytes.size)
                output.write(document.bytes)
            }
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            legacyImageFile(type).delete()
            legacyMetadataFile(type).delete()
        } finally {
            temp.delete()
        }
    }

    private fun cacheFile(type: CampusImageType) = File(directory, "${type.name.lowercase()}.cache")
    private fun legacyImageFile(type: CampusImageType) = File(directory, "${type.name.lowercase()}.img")
    private fun legacyMetadataFile(type: CampusImageType) =
        File(directory, "${type.name.lowercase()}.properties")

    companion object {
        private const val CACHE_MAGIC = 0x43494D47
    }
}

class CampusImageService(
    private val client: OkHttpClient = OkHttpClient(),
    private val cache: CampusImageCache,
    private val pageUrls: Map<CampusImageType, HttpUrl> = DEFAULT_PAGE_URLS,
    private val allowedImageHosts: Set<String> = DEFAULT_IMAGE_HOSTS,
    private val now: () -> Long = System::currentTimeMillis
) : CampusImageGateway {
    private val networkClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun fetch(type: CampusImageType, forceRefresh: Boolean): CampusImageDocument =
        withContext(Dispatchers.IO) {
            try {
                fetchFromNetwork(type, forceRefresh).also { document ->
                    runCatching { cache.save(type, document) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                cache.load(type)?.copy(fromCache = true) ?: throw error.asIoException()
            }
        }

    private fun fetchFromNetwork(type: CampusImageType, forceRefresh: Boolean): CampusImageDocument {
        val pageUrl = pageUrls[type] ?: throw IOException("校园信息页面未配置")
        var finalPageUrl = pageUrl
        val html = executeFollowingTrustedRedirects(
            initialUrl = pageUrl,
            forceRefresh = forceRefresh,
            allowedHosts = setOf(pageUrl.host),
            addressLabel = "校园信息页面地址"
        ).use { response ->
            if (!response.isSuccessful) throw IOException("校园信息页面请求失败：${response.code}")
            finalPageUrl = response.request.url
            val charset = response.body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
            String(readLimitedBody(response, MAX_HTML_BYTES, "校园信息页面"), charset)
        }
        val rawSource = IMAGE_SOURCE.find(html)?.groupValues?.get(1)
            ?.replace("&amp;", "&")
            ?: throw IOException("校园信息页面中未找到图片地址")
        val imageUrl = finalPageUrl.resolve(rawSource)
            ?: throw IOException("校园信息图片地址无效")
        if (imageUrl.scheme != pageUrl.scheme || imageUrl.host !in allowedImageHosts) {
            throw IOException("校园信息图片地址不受信任")
        }
        var finalImageUrl = imageUrl
        val bytes = executeFollowingTrustedRedirects(
            initialUrl = imageUrl,
            forceRefresh = forceRefresh,
            allowedHosts = allowedImageHosts,
            addressLabel = "校园信息图片地址"
        ).use { response ->
            if (!response.isSuccessful) throw IOException("校园信息图片请求失败：${response.code}")
            finalImageUrl = response.request.url
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            val bodyBytes = readLimitedBody(response, MAX_CAMPUS_IMAGE_BYTES, "校园信息图片")
            if (!contentType.startsWith("image/") || !hasSupportedImageSignature(bodyBytes)) {
                throw IOException("校园信息图片格式无效")
            }
            bodyBytes
        }
        return CampusImageDocument(finalImageUrl.toString(), bytes, now())
    }

    private fun executeFollowingTrustedRedirects(
        initialUrl: HttpUrl,
        forceRefresh: Boolean,
        allowedHosts: Set<String>,
        addressLabel: String
    ): Response {
        var url = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val response = networkClient.newCall(request(url, forceRefresh)).execute()
            if (!response.isRedirect) return response
            val location = response.header("Location")
            val redirectedUrl = location?.let(response.request.url::resolve)
            response.close()
            if (redirectedUrl == null ||
                redirectedUrl.scheme != initialUrl.scheme ||
                redirectedUrl.host !in allowedHosts
            ) {
                throw IOException("$addressLabel 不受信任")
            }
            if (redirectCount == MAX_REDIRECTS) throw IOException("$addressLabel 重定向次数过多")
            url = redirectedUrl
        }
        throw IOException("$addressLabel 重定向次数过多")
    }

    private fun readLimitedBody(response: Response, limit: Int, label: String): ByteArray {
        val length = response.body.contentLength()
        if (length > limit) throw IOException("$label 内容过大")
        return response.body.byteStream().use { input -> readLimited(input, limit, label) }
    }

    private fun readLimited(input: InputStream, limit: Int, label: String): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 16 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw IOException("$label 内容过大")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun request(url: HttpUrl, forceRefresh: Boolean): Request = Request.Builder()
        .url(url)
        .apply { if (forceRefresh) header("Cache-Control", "no-cache") }
        .build()

    private fun Exception.asIoException(): IOException =
        this as? IOException ?: IOException(message ?: "校园信息加载失败", this)

    companion object {
        private val IMAGE_SOURCE = Regex(
            """<img\b[^>]*\bsrc\s*=\s*[\"']([^\"']+)[\"']""",
            RegexOption.IGNORE_CASE
        )
        private val DEFAULT_PAGE_URLS = mapOf(
            CampusImageType.ACADEMIC_CALENDAR to
                "https://xxfw.glut.edu.cn/GlutInfoService/jiaoxue-jxrl.html".toHttpUrl(),
            CampusImageType.SHUTTLE_BUS to
                "https://xxfw.glut.edu.cn/GlutInfoService/bus-line.html".toHttpUrl()
        )
        private val DEFAULT_IMAGE_HOSTS = setOf("xxfw.glut.edu.cn", "jwc.glut.edu.cn")
        private const val MAX_REDIRECTS = 4
        private const val MAX_HTML_BYTES = 512 * 1024
    }
}

private const val MAX_CAMPUS_IMAGE_BYTES = 10 * 1024 * 1024

private fun hasSupportedImageSignature(bytes: ByteArray): Boolean {
    val png = bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    )
    val jpeg = bytes.size >= 3 && bytes[0] == 0xFF.toByte() &&
        bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
    val gif = bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII).let {
        it == "GIF87a" || it == "GIF89a"
    }
    val webp = bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
        String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
    return png || jpeg || gif || webp
}
