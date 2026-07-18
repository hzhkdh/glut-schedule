package com.glut.schedule.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

class AppUpdater(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun downloadApk(
        url: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): File {
        val apkFile = File(context.externalCacheDir, "update.apk")
        return downloadFile(client, url, apkFile, onProgress).also {
            Log.d(TAG, "APK downloaded: ${it.length()} bytes to ${it.absolutePath}")
        }
    }

    fun installApk(file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    companion object {
        private const val TAG = "AppUpdater"
    }
}

internal suspend fun downloadFile(
    client: OkHttpClient,
    url: String,
    target: File,
    onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
): File = coroutineScope {
    val progressEvents = Channel<Pair<Long, Long>>(Channel.CONFLATED)
    launch {
        for ((downloaded, total) in progressEvents) {
            onProgress(downloaded, total)
        }
    }
    try {
        downloadFileWithProgressEvents(client, url, target) { downloaded, total ->
            progressEvents.trySend(downloaded to total)
        }
    } finally {
        progressEvents.close()
    }
}

private suspend fun downloadFileWithProgressEvents(
    client: OkHttpClient,
    url: String,
    target: File,
    onProgressEvent: (downloadedBytes: Long, totalBytes: Long) -> Unit
): File = suspendCancellableCoroutine { continuation ->
    val request = Request.Builder()
        .url(url)
        .header("User-Agent", "GlutSchedule-Updater")
        .get()
        .build()
    val call = client.newCall(request)
    val partial = File(target.parentFile, "${target.name}.part")
    target.delete()
    partial.delete()
    continuation.invokeOnCancellation {
        call.cancel()
        partial.delete()
        target.delete()
    }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            partial.delete()
            target.delete()
            if (continuation.isActive) continuation.resumeWith(Result.failure(e))
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                response.use {
                    if (!response.isSuccessful) {
                        throw RuntimeException("下载失败: HTTP ${response.code}")
                    }
                    val body = response.body
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        FileOutputStream(partial).use { output ->
                            val buffer = ByteArray(8192)
                            var downloaded = 0L
                            while (true) {
                                val bytesRead = input.read(buffer)
                                if (bytesRead < 0) break
                                output.write(buffer, 0, bytesRead)
                                downloaded += bytesRead
                                if (continuation.isActive) onProgressEvent(downloaded, total)
                            }
                        }
                    }
                }
                if (continuation.isActive) {
                    publishDownloadedFile(partial, target)
                    continuation.resumeWith(Result.success(target))
                } else {
                    partial.delete()
                    target.delete()
                }
            } catch (error: Exception) {
                partial.delete()
                target.delete()
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            }
        }
    })
}

private fun publishDownloadedFile(partial: File, target: File) {
    try {
        Files.move(
            partial.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
