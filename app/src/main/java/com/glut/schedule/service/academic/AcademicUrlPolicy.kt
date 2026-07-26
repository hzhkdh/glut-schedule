package com.glut.schedule.service.academic

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 教务会话 URL 白名单。
 *
 * 校方当前只提供 HTTP，因此这里不能解决传输加密问题；本策略的职责是确保携带
 * Cookie 的请求只能发往已确认的校方主机、端口和路径，避免重定向或菜单数据把
 * 会话带到外部站点。
 */
object AcademicUrlPolicy {
    private data class AllowedEndpoint(
        val host: String,
        val port: Int,
        val pathPrefix: String
    )

    private val allowedEndpoints = listOf(
        AllowedEndpoint("jw.glut.edu.cn", 80, "/academic/"),
        AllowedEndpoint("jw.glutnn.cn", 80, "/academic/"),
        AllowedEndpoint("ca.glut.edu.cn", 8888, "/zfca/")
    )

    fun isAllowedSessionUrl(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return isAllowedSessionUrl(parsed)
    }

    fun resolveAllowedRedirect(currentUrl: String, location: String): String? {
        if (location.isBlank()) return null
        val current = currentUrl.toHttpUrlOrNull() ?: return null
        val resolved = current.resolve(location) ?: return null
        return resolved.takeIf(::isAllowedSessionUrl)?.toString()
    }

    private fun isAllowedSessionUrl(url: HttpUrl): Boolean {
        if (url.scheme != "http") return false
        return allowedEndpoints.any { endpoint ->
            url.host.equals(endpoint.host, ignoreCase = true) &&
                url.port == endpoint.port &&
                url.encodedPath.startsWith(endpoint.pathPrefix)
        }
    }
}
