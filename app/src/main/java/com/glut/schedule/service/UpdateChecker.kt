package com.glut.schedule.service

import android.util.Log
import com.glut.schedule.service.network.MAX_JSON_RESPONSE_BYTES
import com.glut.schedule.service.network.readStringLimited
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionCode: Long = 0L,
    val latestVersion: String,
    val downloadUrl: String,
    val apkDownloadUrl: String,
    val releaseNotes: String,
    val isNewer: Boolean,
    val isForceUpdate: Boolean = false
)

class UpdateChecker(
    private val repoOwner: String = "hzhkdh",
    private val repoName: String = "glut-schedule",
    private val cfPagesUrl: String = "https://update.999314.xyz"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun check(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        // Channel 1: Cloudflare Pages (primary, fast CDN in China)
        checkCloudflarePages(currentVersion)?.let { return@withContext it }

        Log.w(TAG, "Cloudflare Pages failed, trying GitHub API...")

        // Channel 2: GitHub Releases API
        checkGitHubApi(currentVersion)?.let { return@withContext it }

        Log.w(TAG, "GitHub API failed, trying GitHub Pages fallback...")

        // Channel 3: GitHub Pages static JSON
        checkGitHubPages(currentVersion)?.let { return@withContext it }

        Log.w(TAG, "All update channels failed")
        null
    }

    private fun checkCloudflarePages(currentVersion: String): UpdateInfo? {
        return runCatching {
            val request = Request.Builder()
                .url("$cfPagesUrl/update.json")
                .header("User-Agent", "GlutSchedule/$currentVersion")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.readStringLimited(MAX_JSON_RESPONSE_BYTES) ?: return null
                parseCfPagesResponse(body, currentVersion)
            }
        }.getOrNull()
    }

    private fun parseCfPagesResponse(json: String, currentVersion: String): UpdateInfo? {
        return parseUpdateMetadata(json, currentVersion)
    }

    private fun checkGitHubApi(currentVersion: String): UpdateInfo? {
        return runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$repoOwner/$repoName/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "GlutSchedule/$currentVersion")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.readStringLimited(MAX_JSON_RESPONSE_BYTES) ?: return null
                parseGitHubApiResponse(body, currentVersion)
            }
        }.getOrNull()
    }

    private fun checkGitHubPages(currentVersion: String): UpdateInfo? {
        return runCatching {
            val request = Request.Builder()
                .url("https://$repoOwner.github.io/$repoName/version.json")
                .header("User-Agent", "GlutSchedule/$currentVersion")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.readStringLimited(MAX_JSON_RESPONSE_BYTES) ?: return null
                parsePagesResponse(body, currentVersion)
            }
        }.getOrNull()
    }

    private fun parseGitHubApiResponse(json: String, currentVersion: String): UpdateInfo? {
        return runCatching {
            val obj = JSONObject(json)
            val tagName = obj.optString("tag_name", "").removePrefix("v")
            val htmlUrl = obj.optString("html_url", "")
            val notes = obj.optString("body", "")
            val assets = obj.optJSONArray("assets")
            val apkAsset = (0 until (assets?.length() ?: 0))
                .asSequence()
                .mapNotNull { index -> assets?.optJSONObject(index) }
                .firstOrNull { asset ->
                    asset.optString("name").endsWith(".apk", ignoreCase = true)
                } ?: return null
            val apkUrl = apkAsset.optString("browser_download_url", "")
            if (!UpdateDownloadPolicy.isAllowedDownloadUrl(apkUrl)) return null
            UpdateInfo(
                versionCode = 0L,
                latestVersion = tagName,
                downloadUrl = htmlUrl,
                apkDownloadUrl = apkUrl,
                releaseNotes = notes,
                isNewer = compareVersions(tagName, currentVersion) > 0
            )
        }.getOrNull()
    }

    private fun parsePagesResponse(json: String, currentVersion: String): UpdateInfo? {
        return parseUpdateMetadata(json, currentVersion)
    }

    companion object {
        private const val TAG = "UpdateChecker"

        /** Returns >0 if a is newer, <0 if b is newer, 0 if equal. */
        fun compareVersions(a: String, b: String): Int {
            val partsA = a.split(".").map { it.toIntOrNull() ?: 0 }
            val partsB = b.split(".").map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(partsA.size, partsB.size)) {
                val va = partsA.getOrElse(i) { 0 }
                val vb = partsB.getOrElse(i) { 0 }
                if (va != vb) return va.compareTo(vb)
            }
            return 0
        }
    }
}

/** 解析静态更新元数据，并继续限制 APK 只能从受信任的 HTTPS 地址下载。 */
internal fun parseUpdateMetadata(json: String, currentVersion: String): UpdateInfo? {
    return runCatching {
        val obj = JSONObject(json)
        val versionCode = obj.optLong("versionCode", 0L)
        val versionName = obj.optString("versionName", obj.optString("version", ""))
            .removePrefix("v")
        val downloadUrl = obj.optString("downloadUrl", "")
        val updateDesc = obj.optString("updateDesc", obj.optString("releaseNotes", ""))
        val forceUpdate = obj.optBoolean("forceUpdate", false)

        if (versionCode <= 0L ||
            versionName.isBlank() ||
            !UpdateDownloadPolicy.isAllowedDownloadUrl(downloadUrl)
        ) return null

        UpdateInfo(
            versionCode = versionCode,
            latestVersion = versionName,
            downloadUrl = downloadUrl,
            apkDownloadUrl = downloadUrl,
            releaseNotes = updateDesc,
            isNewer = UpdateChecker.compareVersions(versionName, currentVersion) > 0,
            isForceUpdate = forceUpdate
        )
    }.getOrNull()
}
