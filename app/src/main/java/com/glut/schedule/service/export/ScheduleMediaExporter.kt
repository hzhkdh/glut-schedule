package com.glut.schedule.service.export

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 导出落盘结果。权限缺失单独建模，让界面能先申请权限再重试，而不是报一句笼统的失败。 */
sealed class ScheduleExportSaveResult {
    data class Saved(
        val uri: Uri,
        val displayName: String = "",
        val location: String = "",
        val mimeType: String = "",
        val byteCount: Long = 0L
    ) : ScheduleExportSaveResult()
    data object PermissionRequired : ScheduleExportSaveResult()
    data class Failed(val message: String) : ScheduleExportSaveResult()
}

private const val EXPORT_CACHE_DIRECTORY = "schedule_export"
private const val LEGACY_PERMISSION = Manifest.permission.WRITE_EXTERNAL_STORAGE

private fun legacyPermissionGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(context, LEGACY_PERMISSION) == PackageManager.PERMISSION_GRANTED

/** 导出文件先落应用缓存，再交给 FileProvider 分享；不触碰公共目录，也就不需要权限。 */
internal fun exportCacheFile(context: Context, fileName: String): File {
    val directory = context.cacheDir.resolve(EXPORT_CACHE_DIRECTORY).apply { mkdirs() }
    return directory.resolve(fileName)
}

private fun fileProviderUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

private fun launchShare(context: Context, intent: Intent, title: String) {
    val chooser = Intent.createChooser(intent, title).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}

/** 课表图片：保存到系统相册（API 29+ 走 MediaStore，26–28 需要存储权限）或临时缓存后分享。 */
object ScheduleImageExporter {
    const val ALBUM_DIRECTORY = "桂工课表"

    suspend fun saveToGallery(
        context: Context,
        bitmap: Bitmap,
        fileName: String
    ): ScheduleExportSaveResult = withContext(Dispatchers.IO) {
        if (!legacyPermissionGranted(context)) return@withContext ScheduleExportSaveResult.PermissionRequired
        val software = bitmap.requireSoftwareBitmap()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ScheduleExportSaveResult.Saved(saveWithMediaStore(context, software, fileName))
            } else {
                ScheduleExportSaveResult.Saved(saveToLegacyPictures(context, software, fileName))
            }
        } catch (error: Exception) {
            ScheduleExportSaveResult.Failed(error.message ?: "保存失败")
        } finally {
            if (software !== bitmap) software.recycle()
        }
    }

    suspend fun share(
        context: Context,
        bitmap: Bitmap,
        fileName: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val software = bitmap.requireSoftwareBitmap()
            val file = exportCacheFile(context, fileName)
            try {
                file.outputStream().use { output ->
                    if (!software.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        throw IOException("PNG 编码失败")
                    }
                }
            } finally {
                if (software !== bitmap) software.recycle()
            }
            withContext(Dispatchers.Main) {
                val uri = fileProviderUri(context, file)
                launchShare(
                    context,
                    Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "分享课表"
                )
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveWithMediaStore(context: Context, bitmap: Bitmap, fileName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/$ALBUM_DIRECTORY"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法创建相册文件")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw IOException("PNG 编码失败")
                }
            } ?: throw IOException("无法写入相册文件")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null
            )
            return uri
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun saveToLegacyPictures(context: Context, bitmap: Bitmap, fileName: String): Uri {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            ALBUM_DIRECTORY
        ).apply { mkdirs() }
        if (!directory.isDirectory) throw IOException("无法创建相册目录")
        val target = uniqueTarget(directory, fileName)
        try {
            target.outputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw IOException("PNG 编码失败")
                }
            }
        } catch (error: Exception) {
            target.delete()
            throw error
        }
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf("image/png"), null)
        return Uri.fromFile(target)
    }
}

/** 任意二进制文件（PDF 等）：保存到 Download/桂工课表，或写入缓存后分享。 */
object ScheduleBinaryExporter {
    const val DOWNLOAD_DIRECTORY = "桂工课表"

    suspend fun saveToDownloads(
        context: Context,
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ): ScheduleExportSaveResult = withContext(Dispatchers.IO) {
        if (!legacyPermissionGranted(context)) return@withContext ScheduleExportSaveResult.PermissionRequired
        try {
            require(bytes.isNotEmpty()) { "导出内容为空" }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveWithMediaStoreDownloads(context, fileName, mimeType, bytes)
            } else {
                val uri = saveToLegacyDownloads(context, fileName, mimeType, bytes)
                val file = File(requireNotNull(uri.path))
                check(file.length() > 0) { "文件未完整写入" }
                ScheduleExportSaveResult.Saved(uri, file.name,
                    "Download/桂工课表", mimeType, file.length())
            }
        } catch (error: Exception) {
            ScheduleExportSaveResult.Failed(error.message ?: "保存失败")
        }
    }

    suspend fun share(
        context: Context,
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val file = exportCacheFile(context, fileName)
            file.writeBytes(bytes)
            withContext(Dispatchers.Main) {
                val uri = fileProviderUri(context, file)
                launchShare(
                    context,
                    Intent(Intent.ACTION_SEND).apply {
                        type = mimeType
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "分享课表"
                )
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveWithMediaStoreDownloads(
        context: Context,
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ): ScheduleExportSaveResult.Saved {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_DIRECTORY"
            )
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法创建下载文件")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IOException("无法写入下载文件")
            val published = resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null
            )
            check(published > 0) { "文件未发布到下载目录" }
            return readPublishedExport(context, uri)
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun saveToLegacyDownloads(
        context: Context,
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ): Uri {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            DOWNLOAD_DIRECTORY
        ).apply { mkdirs() }
        if (!directory.isDirectory) throw IOException("无法创建下载目录")
        val target = uniqueTarget(directory, fileName)
        try {
            target.writeBytes(bytes)
        } catch (error: Exception) {
            target.delete()
            throw error
        }
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mimeType), null)
        return Uri.fromFile(target)
    }
}

/** 文本文件（.ics）：转成 UTF-8 字节后复用二进制导出。 */
object ScheduleTextFileExporter {
    suspend fun saveToDownloads(
        context: Context,
        fileName: String,
        mimeType: String,
        content: String
    ): ScheduleExportSaveResult = ScheduleBinaryExporter.saveToDownloads(
        context = context,
        fileName = fileName,
        mimeType = mimeType,
        bytes = content.toByteArray(Charsets.UTF_8)
    )

    suspend fun share(
        context: Context,
        fileName: String,
        mimeType: String,
        content: String
    ): Result<Unit> = ScheduleBinaryExporter.share(
        context = context,
        fileName = fileName,
        mimeType = mimeType,
        bytes = content.toByteArray(Charsets.UTF_8)
    )
}

/** 同名文件不覆盖，按 `名字 (2).png` 递增。 */
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
