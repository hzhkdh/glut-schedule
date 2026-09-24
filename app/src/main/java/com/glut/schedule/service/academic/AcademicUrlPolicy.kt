package com.glut.schedule.service.academic

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 教务会话 URL 白名单。
 *
 * 桂林教务已将 HTTP 请求重定向到 HTTPS。携带 Cookie 的桂林请求必须在发送前
 * 规范化为 HTTPS，避免 POST 经 301 后被改写成 GET；南宁和旧 OA 入口维持现状。
 */
object AcademicUrlPolicy {
    private data class AllowedEndpoint(
        val scheme: String,
        val host: String,
        val port: Int,
        val pathPrefix: String
    )

    private val allowedEndpoints = listOf(
        AllowedEndpoint("https", "jw.glut.edu.cn", 443, "/academic/"),
        AllowedEndpoint("http", "jw.glutnn.cn", 80, "/academic/"),
        AllowedEndpoint("http", "ca.glut.edu.cn", 8888, "/zfca/")
    )

    /** 只升级精确匹配的桂林教务主机，避免相似域名借规范化绕过白名单。 */
    fun normalizeCampusBaseUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        val parsed = trimmed.toHttpUrlOrNull() ?: return trimmed
        return normalizeGuilinUrl(parsed).toString().trimEnd('/')
    }

    fun isAllowedSessionUrl(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return isAllowedSessionUrl(parsed)
    }

    fun resolveAllowedRedirect(currentUrl: String, location: String): String? {
        if (location.isBlank()) return null
        val current = currentUrl.toHttpUrlOrNull() ?: return null
        val resolved = current.resolve(location) ?: return null
        val normalized = normalizeGuilinUrl(resolved)
        return normalized.takeIf(::isAllowedSessionUrl)?.toString()
    }

    private fun isAllowedSessionUrl(url: HttpUrl): Boolean {
        return allowedEndpoints.any { endpoint ->
            url.scheme == endpoint.scheme &&
                url.host.equals(endpoint.host, ignoreCase = true) &&
                url.port == endpoint.port &&
                url.encodedPath.startsWith(endpoint.pathPrefix)
        }
    }

    private fun normalizeGuilinUrl(url: HttpUrl): HttpUrl {
        if (url.scheme != "http" || !url.host.equals("jw.glut.edu.cn", ignoreCase = true) || url.port != 80) {
            return url
        }
        return url.newBuilder()
            .scheme("https")
            .port(443)
            .build()
    }
}
