package com.glut.schedule.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val latestVersion: String,
    val downloadUrl: String,
    val releaseNotes: String,
    val isNewer: Boolean
)

class UpdateChecker(
    private val repoOwner: String = "hzhkdh",
    private val repoName: String = "glut-schedule"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun check(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        // Channel 1: GitHub Releases API
        checkGitHubApi(currentVersion)?.let { return@withContext it }

        Log.w(TAG, "GitHub API failed, trying GitHub Pages fallback...")

        // Channel 2: GitHub Pages static JSON
        checkGitHubPages(currentVersion)?.let { return@withContext it }

        Log.w(TAG, "All update channels failed")
        null
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
                val body = response.body?.string() ?: return null
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
                val body = response.body?.string() ?: return null
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
            UpdateInfo(
                latestVersion = tagName,
                downloadUrl = htmlUrl,
                releaseNotes = notes,
                isNewer = compareVersions(tagName, currentVersion) > 0
            )
        }.getOrNull()
    }

    private fun parsePagesResponse(json: String, currentVersion: String): UpdateInfo? {
        return runCatching {
            val obj = JSONObject(json)
            val version = obj.optString("version", "").removePrefix("v")
            val downloadUrl = obj.optString("downloadUrl", "")
            val notes = obj.optString("releaseNotes", "")
            UpdateInfo(
                latestVersion = version,
                downloadUrl = downloadUrl,
                releaseNotes = notes,
                isNewer = compareVersions(version, currentVersion) > 0
            )
        }.getOrNull()
    }

    private fun compareVersions(a: String, b: String): Int {
        val partsA = a.split(".").map { it.toIntOrNull() ?: 0 }
        val partsB = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(partsA.size, partsB.size)) {
            val va = partsA.getOrElse(i) { 0 }
            val vb = partsB.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }

    companion object {
        private const val TAG = "UpdateChecker"
    }
}
