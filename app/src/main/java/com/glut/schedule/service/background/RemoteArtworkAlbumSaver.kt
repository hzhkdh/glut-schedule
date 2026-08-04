package com.glut.schedule.service.background

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class RemoteArtworkSaveResult(val uri: String)

interface RemoteArtworkSaver {
    suspend fun save(
        item: RemoteBackgroundItem,
        cached: DownloadedRemoteBackground?,
        onProgress: (Float) -> Unit = {}
    ): RemoteArtworkSaveResult
}

object UnsupportedRemoteArtworkSaver : RemoteArtworkSaver {
    override suspend fun save(
        item: RemoteBackgroundItem,
        cached: DownloadedRemoteBackground?,
        onProgress: (Float) -> Unit
    ): RemoteArtworkSaveResult = error("当前环境不支持保存到系统相册")
}

/**
 * 将佳作原图写入系统相册。下载只使用临时文件，不会顺带创建 App 的背景原图缓存。
 */
class AndroidRemoteArtworkSaver(
    private val context: Context,
    client: OkHttpClient = OkHttpClient()
) : RemoteArtworkSaver {
    private val networkClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun save(
        item: RemoteBackgroundItem,
        cached: DownloadedRemoteBackground?,
        onProgress: (Float) -> Unit
    ): RemoteArtworkSaveResult = withContext(Dispatchers.IO) {
        val temporaryDirectory = context.cacheDir.resolve("remote_artwork_exports").apply { mkdirs() }
        val temporary = temporaryDirectory.resolve("${item.id}_${System.nanoTime()}.tmp")
        val source = cached?.file?.takeIf(File::isFile) ?: temporary
        try {
            if (source == temporary) downloadVerifiedOriginal(item, temporary, onProgress)
            val mimeType = imageMimeType(source) ?: throw IOException("佳作原图格式无效")
            val fileName = artworkFileName(item.displayName, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveWithMediaStore(source, fileName, mimeType)
            } else {
                saveToLegacyPictures(source, fileName, mimeType)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun downloadVerifiedOriginal(
        item: RemoteBackgroundItem,
        target: File,
        onProgress: (Float) -> Unit
    ) {
        val request = Request.Builder().url(item.originalUrl).build()
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        networkClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("佳作原图请求失败：${response.code}")
            if (artworkMimeType(response.header("Content-Type")) == null) {
                throw IOException("佳作原图格式无效")
            }
            val declared = response.body.contentLength()
            if (declared > MAX_ORIGINAL_BYTES) throw IOException("佳作原图内容过大")
            response.body.byteStream().use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_ORIGINAL_BYTES) throw IOException("佳作原图内容过大")
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        onProgress((total.toFloat() / item.byteSize.coerceAtLeast(1L)).coerceIn(0f, 1f))
                    }
                }
            }
        }
        if (total != item.byteSize || digest.digest().toHexString() != item.sha256) {
            throw IOException("佳作原图校验失败")
        }
    }

    private fun saveWithMediaStore(source: File, fileName: String, mimeType: String): RemoteArtworkSaveResult {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ScheduleApp")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法创建系统相册文件")
        try {
            resolver.openOutputStream(uri)?.use { output -> source.inputStream().use { it.copyTo(output) } }
                ?: throw IOException("无法写入系统相册文件")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return RemoteArtworkSaveResult(uri.toString())
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun saveToLegacyPictures(source: File, fileName: String, mimeType: String): RemoteArtworkSaveResult {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "ScheduleApp"
        ).apply { mkdirs() }
        if (!directory.isDirectory) throw IOException("无法创建系统相册目录")
        val target = uniqueTarget(directory, fileName)
        try {
            source.copyTo(target)
        } catch (error: Exception) {
            // 旧系统直接写公共目录，失败时主动删除半成品，避免相册出现损坏图片。
            target.delete()
            throw error
        }
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mimeType), null)
        return RemoteArtworkSaveResult(target.toURI().toString())
    }

    private fun uniqueTarget(directory: File, fileName: String): File {
        val direct = directory.resolve(fileName)
        if (!direct.exists()) return direct
        val base = direct.nameWithoutExtension
        val extension = direct.extension
        var index = 2
        while (true) {
            val candidate = directory.resolve("$base ($index).$extension")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    companion object {
        private const val MAX_ORIGINAL_BYTES = 25L * 1024 * 1024
    }
}

internal fun artworkMimeType(raw: String?): String? =
    raw?.substringBefore(';')?.trim()?.lowercase()?.takeIf {
        it == "image/jpeg" || it == "image/png" || it == "image/webp"
    }

internal fun artworkFileName(displayName: String, mimeType: String): String {
    // 移除 Windows/Android 文件系统中的路径与保留字符，避免作品名被解释为目录。
    val safeName = displayName.replace(Regex("[\\\\/:*?\"<>|]"), "").trim().trim('.')
        .ifBlank { "ScheduleApp佳作" }
    val extension = when (mimeType) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg"
    }
    return "$safeName.$extension"
}

private fun imageMimeType(file: File): String? {
    val header = file.inputStream().use { input -> ByteArray(12).also { input.read(it) } }
    return when {
        header.size >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte() -> "image/jpeg"
        header.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> "image/png"
        String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> null
    }
}

private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
