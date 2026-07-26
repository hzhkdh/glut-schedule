package com.glut.schedule.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class AppUpdater(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addNetworkInterceptor { chain ->
            val requestUrl = chain.request().url.toString()
            if (!UpdateDownloadPolicy.isAllowedDownloadUrl(requestUrl)) {
                throw IOException("更新下载跳转到了未授权地址")
            }
            chain.proceed(chain.request())
        }
        .build()

    suspend fun downloadApk(
        info: UpdateInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): File {
        val cacheDirectory = context.externalCacheDir ?: context.cacheDir
        val apkFile = File(cacheDirectory, "update.apk")
        return downloadFile(
            client = client,
            url = info.apkDownloadUrl,
            target = apkFile,
            onProgress = onProgress
        ).also {
            Log.d(TAG, "APK downloaded: ${it.length()} bytes")
        }
    }

    fun installApk(file: File) {
        validateApkIdentity(file)
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

    private fun validateApkIdentity(file: File) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        @Suppress("DEPRECATION")
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IOException("下载文件不是有效的 APK")
        @Suppress("DEPRECATION")
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        if (!isTrustedApkIdentity(
                currentPackageName = context.packageName,
                currentVersionCode = installed.versionCodeCompat(),
                currentSignerDigests = signerDigests(installed),
                archivePackageName = archive.packageName,
                archiveVersionCode = archive.versionCodeCompat(),
                archiveSignerDigests = signerDigests(archive)
            )
        ) {
            throw IOException("更新包的包名、版本或签名校验失败")
        }
    }

    companion object {
        private const val TAG = "AppUpdater"
        const val MAX_APK_BYTES = 100L * 1024L * 1024L
    }
}

private fun PackageInfo.versionCodeCompat(): Long {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        longVersionCode
    } else {
        @Suppress("DEPRECATION")
        versionCode.toLong()
    }
}

private fun signerDigests(packageInfo: PackageInfo): Set<String> {
    @Suppress("DEPRECATION")
    val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.signingInfo?.apkContentsSigners.orEmpty()
    } else {
        packageInfo.signatures.orEmpty()
    }
    return signatures.mapTo(linkedSetOf()) { signature ->
        MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

internal suspend fun downloadFile(
    client: OkHttpClient,
    url: String,
    target: File,
    maxBytes: Long = AppUpdater.MAX_APK_BYTES,
    urlValidator: (String) -> Boolean = UpdateDownloadPolicy::isAllowedDownloadUrl,
    onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
): File = coroutineScope {
    require(urlValidator(url)) { "更新下载地址不受信任" }
    val progressEvents = Channel<Pair<Long, Long>>(Channel.CONFLATED)
    launch {
        for ((downloaded, total) in progressEvents) {
            onProgress(downloaded, total)
        }
    }
    try {
        downloadFileWithProgressEvents(
            client = client,
            url = url,
            target = target,
            maxBytes = maxBytes
        ) { downloaded, total ->
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
    maxBytes: Long,
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
                    if (total > maxBytes) {
                        throw IOException("更新包超过大小限制")
                    }
                    body.byteStream().use { input ->
                        FileOutputStream(partial).use { output ->
                            val buffer = ByteArray(8192)
                            var downloaded = 0L
                            while (true) {
                                val bytesRead = input.read(buffer)
                                if (bytesRead < 0) break
                                if (downloaded + bytesRead > maxBytes) {
                                    throw IOException("更新包超过大小限制")
                                }
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
