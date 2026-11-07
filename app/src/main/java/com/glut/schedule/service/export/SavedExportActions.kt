package com.glut.schedule.service.export

import android.app.DownloadManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 从发布后的记录读取实际文件名；同名保存可能被系统追加序号，不能再猜测原请求名。 */
@RequiresApi(Build.VERSION_CODES.Q)
internal fun readPublishedExport(context: Context, uri: Uri): ScheduleExportSaveResult.Saved {
    val columns = arrayOf(MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.RELATIVE_PATH,
        MediaStore.Downloads.MIME_TYPE, MediaStore.Downloads.IS_PENDING, MediaStore.Downloads.SIZE)
    return context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) throw IOException("无法核对已保存的文件")
        if (cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Downloads.IS_PENDING)) != 0) {
            throw IOException("文件尚未发布")
        }
        val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME))
            ?: throw IOException("无法读取实际文件名")
        val path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH)).orEmpty().trimEnd('/')
        val type = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)).orEmpty()
        val size = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1
        if (size <= 0L) throw IOException("保存后的文件为空或不可读取")
        ScheduleExportSaveResult.Saved(uri, name, path, type, size)
    } ?: throw IOException("无法读取已保存的文件")
}

/** 老版本落盘返回 file URI；通过应用缓存和现有 FileProvider 临时共享，避免暴露 file://。 */
private suspend fun savedContentUri(context: Context, saved: ScheduleExportSaveResult.Saved): Uri =
    withContext(Dispatchers.IO) {
        if (saved.uri.scheme != "file") saved.uri else {
            val source = File(requireNotNull(saved.uri.path))
            val cached = exportCacheFile(context, source.name)
            source.copyTo(cached, overwrite = true)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", cached)
        }
    }

/** 查看和分享复用实际保存的 URI，不重新生成文件，也不自动触发导入日历。 */
suspend fun openSavedExport(context: Context, saved: ScheduleExportSaveResult.Saved, share: Boolean = false): Boolean =
    try {
        val uri = savedContentUri(context, saved)
        withContext(Dispatchers.Main) {
            val intent = Intent(if (share) Intent.ACTION_SEND else Intent.ACTION_VIEW).apply {
                if (share) { type = saved.mimeType; putExtra(Intent.EXTRA_STREAM, uri) }
                else setDataAndType(uri, saved.mimeType)
                clipData = ClipData.newRawUri(saved.displayName, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val launch = if (share) Intent.createChooser(intent, "分享文件") else intent
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }
    } catch (_: Exception) { false }

/** 文件管理器不支持目录 URI 时退回下载列表；保留回执中的路径供用户查找。 */
fun openSavedExportLocation(context: Context, saved: ScheduleExportSaveResult.Saved): Boolean {
    val directory = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",
        "primary:${saved.location.trimEnd('/')}")
    // 优先系统文件应用，避免音乐等注册通配 MIME 的应用挤满“打开方式”。
    for (systemFiles in listOf("com.android.documentsui", "com.google.android.documentsui")) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(directory, "vnd.android.document/directory")
                .setPackage(systemFiles).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: android.content.ActivityNotFoundException) {
            // 不预查包可见性：设备没有该实现时继续兼容其他系统或文件管理器。
        } catch (_: SecurityException) { }
    }
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(directory, "vnd.android.document/directory")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) { false }
    }
}
