package com.glut.schedule.service

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 更新包下载来源白名单。
 *
 * 网络拦截器会对每一次重定向后的真实请求重复校验，避免可信下载地址跳转到外域。
 */
object UpdateDownloadPolicy {
    private val allowedHosts = setOf(
        "update.999314.xyz",
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com"
    )

    fun isAllowedDownloadUrl(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.scheme == "https" &&
            parsed.host.lowercase() in allowedHosts &&
            parsed.encodedPath.endsWith(".apk", ignoreCase = true)
    }
}

/**
 * 安装包必须属于当前应用、版本更高且签名集合完全一致。
 */
internal fun isTrustedApkIdentity(
    currentPackageName: String,
    currentVersionCode: Long,
    currentSignerDigests: Set<String>,
    archivePackageName: String,
    archiveVersionCode: Long,
    archiveSignerDigests: Set<String>
): Boolean {
    return archivePackageName == currentPackageName &&
        archiveVersionCode > currentVersionCode &&
        currentSignerDigests.isNotEmpty() &&
        archiveSignerDigests == currentSignerDigests
}
