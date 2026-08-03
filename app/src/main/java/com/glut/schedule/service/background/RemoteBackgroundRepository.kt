package com.glut.schedule.service.background

import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

interface RemoteBackgroundGateway {
    suspend fun loadCatalog(forceRefresh: Boolean = false): RemoteBackgroundCatalog
    suspend fun loadPreview(item: RemoteBackgroundItem, large: Boolean): File
    suspend fun downloadOriginal(
        item: RemoteBackgroundItem,
        onProgress: (Float) -> Unit = {}
    ): DownloadedRemoteBackground
    fun downloadedAssets(): List<DownloadedRemoteBackground>
    fun deleteDownloaded(id: String, sha256: String, activeUri: String): RemoteBackgroundDeleteResult
    fun clearAllData()
}

class RemoteBackgroundRepository(
    client: OkHttpClient = OkHttpClient(),
    private val catalogUrl: String = DEFAULT_CATALOG_URL,
    private val fallbackCatalogUrls: List<String> = listOf(DEFAULT_FALLBACK_CATALOG_URL),
    private val catalogCacheFile: File,
    private val previewCacheDirectory: File,
    private val assetStore: RemoteBackgroundAssetStore,
    private val downloadCacheDirectory: File = previewCacheDirectory.parentFile?.resolve("remote_background_downloads")
        ?: File("remote_background_downloads"),
    private val urlMapper: (String) -> HttpUrl = { it.toHttpUrl() },
    private val imageBoundsValidator: (File) -> Boolean = ::hasDecodableImageBounds
) : RemoteBackgroundGateway {
    private val networkClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun loadCatalog(forceRefresh: Boolean): RemoteBackgroundCatalog = withContext(Dispatchers.IO) {
        val cached = loadCachedCatalog()
        if (!forceRefresh && cached != null) return@withContext cached
        var lastError: Exception? = null
        for (url in (listOf(catalogUrl) + fallbackCatalogUrls).distinct()) {
            try {
                val request = Request.Builder().url(urlMapper(url))
                    .apply { if (forceRefresh) header("Cache-Control", "no-cache") }
                    .build()
                val raw = networkClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("在线背景清单请求失败：${response.code}")
                    val contentType = response.header("Content-Type").orEmpty().lowercase()
                    if (!contentType.contains("json")) throw IOException("在线背景清单格式无效")
                    String(readLimited(response.body.byteStream(), MAX_CATALOG_BYTES, "在线背景清单"), Charsets.UTF_8)
                }
                val catalog = RemoteBackgroundCatalogParser.parse(raw)
                saveCatalog(raw)
                assetStore.synchronizeCatalog(catalog.items)
                return@withContext catalog
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastError = error
            }
        }
        cached ?: throw (lastError as? IOException ?: IOException(lastError?.message, lastError))
    }

    override suspend fun loadPreview(item: RemoteBackgroundItem, large: Boolean): File = withContext(Dispatchers.IO) {
        val url = if (large) item.previewUrl else item.thumbnailUrl
        previewCacheDirectory.mkdirs()
        val token = sha256(url.toByteArray()).take(12)
        val target = File(previewCacheDirectory, "${item.id}_${if (large) "preview" else "thumb"}_$token.webp")
        if (target.isFile && target.length() > 0L) return@withContext target
        val temporary = File(previewCacheDirectory, "${target.name}.tmp")
        try {
            val request = Request.Builder().url(urlMapper(url)).build()
            networkClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("背景预览请求失败：${response.code}")
                if (!response.header("Content-Type").orEmpty().lowercase().startsWith("image/")) {
                    throw IOException("背景预览格式无效")
                }
                val bytes = readLimited(response.body.byteStream(), MAX_PREVIEW_BYTES, "背景预览")
                if (!hasSupportedImageSignature(bytes)) throw IOException("背景预览格式无效")
                temporary.writeBytes(bytes)
            }
            moveReplacing(temporary, target)
            trimPreviewCache()
            target
        } finally {
            temporary.delete()
        }
    }

    override suspend fun downloadOriginal(
        item: RemoteBackgroundItem,
        onProgress: (Float) -> Unit
    ): DownloadedRemoteBackground = withContext(Dispatchers.IO) {
        assetStore.find(item.id, item.sha256)?.let { return@withContext it }
        downloadCacheDirectory.mkdirs()
        val temporary = File(downloadCacheDirectory, "${item.id}_${System.nanoTime()}.tmp")
        try {
            val request = Request.Builder().url(urlMapper(item.originalUrl)).build()
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            networkClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("背景原图请求失败：${response.code}")
                if (!response.header("Content-Type").orEmpty().lowercase().startsWith("image/")) {
                    throw IOException("背景原图格式无效")
                }
                val declared = response.body.contentLength()
                if (declared > MAX_ORIGINAL_BYTES) throw IOException("背景原图内容过大")
                response.body.byteStream().use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_ORIGINAL_BYTES) throw IOException("背景原图内容过大")
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            onProgress((total.toFloat() / item.byteSize.coerceAtLeast(1L)).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            if (total != item.byteSize || digest.digest().toHex() != item.sha256) {
                throw IOException("背景原图校验失败")
            }
            if (!imageBoundsValidator(temporary)) throw IOException("背景原图无法解码")
            assetStore.commit(item, temporary)
        } finally {
            temporary.delete()
        }
    }

    override fun downloadedAssets(): List<DownloadedRemoteBackground> = assetStore.list()

    override fun deleteDownloaded(
        id: String,
        sha256: String,
        activeUri: String
    ): RemoteBackgroundDeleteResult {
        val result = assetStore.delete(id, sha256, activeUri)
        if (result == RemoteBackgroundDeleteResult.Deleted) {
            previewCacheDirectory.listFiles().orEmpty()
                .filter { it.isFile && it.name.startsWith("${id}_") }
                .forEach(File::delete)
        }
        return result
    }

    override fun clearAllData() {
        assetStore.clearAll()
        catalogCacheFile.delete()
        previewCacheDirectory.deleteRecursively()
        downloadCacheDirectory.deleteRecursively()
    }

    private fun loadCachedCatalog(): RemoteBackgroundCatalog? = runCatching {
        if (!catalogCacheFile.isFile) return null
        RemoteBackgroundCatalogParser.parse(catalogCacheFile.readText(Charsets.UTF_8))
    }.getOrNull()

    private fun saveCatalog(raw: String) {
        catalogCacheFile.parentFile?.mkdirs()
        val temporary = File(catalogCacheFile.parentFile, "${catalogCacheFile.name}.tmp")
        try {
            temporary.writeText(raw, Charsets.UTF_8)
            moveReplacing(temporary, catalogCacheFile)
        } finally {
            temporary.delete()
        }
    }

    private fun trimPreviewCache() {
        val files = previewCacheDirectory.listFiles().orEmpty().filter(File::isFile).sortedBy(File::lastModified)
        var total = files.sumOf(File::length)
        for (file in files) {
            if (total <= MAX_PREVIEW_CACHE_BYTES) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    private fun readLimited(input: InputStream, limit: Int, label: String): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 16 * 1024))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
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

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val DEFAULT_CATALOG_URL = "https://background.999314.xyz/backgrounds.json"
        const val DEFAULT_FALLBACK_CATALOG_URL = "https://schedule-background-host.pages.dev/backgrounds.json"
        private const val MAX_CATALOG_BYTES = 512 * 1024
        private const val MAX_PREVIEW_BYTES = 5 * 1024 * 1024
        private const val MAX_ORIGINAL_BYTES = 25L * 1024 * 1024
        private const val MAX_PREVIEW_CACHE_BYTES = 64L * 1024 * 1024
    }
}

private fun hasDecodableImageBounds(file: File): Boolean {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, options)
    return options.outWidth > 0 && options.outHeight > 0 &&
        options.outWidth.toLong() * options.outHeight <= 50_000_000L
}

private fun hasSupportedImageSignature(bytes: ByteArray): Boolean {
    val jpeg = bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
    val png = bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    )
    val webp = bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
        String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
    return jpeg || png || webp
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
