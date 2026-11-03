package com.glut.schedule.service.chaoxing

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * WebView 仅用于初始化登录态和持久化；HTTP 重定向必须立即使用刚收到的 Cookie，
 * 不能依赖 WebView 写入完成的时机。此对象只在单次客户端生命周期内保存会话。
 */
class ChaoxingCookieJar(
    private val read: (HttpUrl) -> List<Cookie>,
    private val write: (HttpUrl, List<Cookie>) -> Unit
) : CookieJar {
    // WebView 请求头丢失了 Domain/Path，人工播种的作用域只是一种回退，不能冒充服务端属性。
    private data class Entry(val cookie: Cookie, val seededAt: HttpUrl? = null)
    private val values = mutableListOf<Entry>()
    private val seededHosts = mutableSetOf<String>()

    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (seededHosts.add(url.host)) {
            read(url).forEach { seed ->
                // 新域名的 WebView 快照不能覆盖 HTTP 链路刚更新的父域会话。
                if (values.none { it.cookie.name == seed.name && it.cookie.matches(url) }) {
                    // Cookie 请求头不包含 Path，不能让 Cookie.parse 从当前深层 URL
                    // 猜出一个假路径，否则轮换后同名旧票据会和新票据一起发送。
                    val normalized = Cookie.Builder().name(seed.name).value(seed.value)
                        .hostOnlyDomain(url.host).path("/").apply {
                            if (seed.secure) secure()
                            if (seed.httpOnly) httpOnly()
                        }.build()
                    values.add(Entry(normalized, seededAt = url))
                }
            }
        }
        // 保留服务端删除标记，防止异步 WebView 快照把旧票据重新播种。
        val matching = values.filter { it.cookie.matches(url) }
        // 较窄路径的新票据未必覆盖最初的播种地址；在当前请求中仍应压过同名人工回退值。
        // 删除标记也参与覆盖判定，避免 WebView 的旧快照复活已删除的票据。
        val authoritativeNames = matching.filter { it.seededAt == null }.map { it.cookie.name }.toSet()
        return matching.filter { entry ->
            entry.cookie.expiresAt > System.currentTimeMillis() &&
                (entry.seededAt == null || entry.cookie.name !in authoritativeNames)
        }.map { it.cookie }.sortedByDescending { it.path.length }
    }

    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { cookie ->
            values.removeAll { entry ->
                val old = entry.cookie
                (old.name == cookie.name && old.domain == cookie.domain && old.path == cookie.path) ||
                    (old.name == cookie.name && entry.seededAt?.let(cookie::matches) == true)
            }
            values.add(Entry(cookie))
        }
        write(url, cookies)
    }
}
