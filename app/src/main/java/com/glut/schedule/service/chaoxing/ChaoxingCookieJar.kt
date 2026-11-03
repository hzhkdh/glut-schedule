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
    private val values = mutableListOf<Cookie>()
    private val seededHosts = mutableSetOf<String>()

    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (seededHosts.add(url.host)) {
            read(url).forEach { seed ->
                // 新域名的 WebView 快照不能覆盖 HTTP 链路刚更新的父域会话。
                if (values.none { it.name == seed.name && it.matches(url) }) {
                    // Cookie 请求头不包含 Path，不能让 Cookie.parse 从当前深层 URL
                    // 猜出一个假路径，否则轮换后同名旧票据会和新票据一起发送。
                    values.add(Cookie.Builder().name(seed.name).value(seed.value)
                        .hostOnlyDomain(url.host).path("/").apply {
                            if (seed.secure) secure()
                            if (seed.httpOnly) httpOnly()
                        }.build())
                }
            }
        }
        // 保留服务端删除标记，防止异步 WebView 快照把旧票据重新播种。
        return values.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }.sortedByDescending { it.path.length }
    }

    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { cookie ->
            values.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            values.add(cookie)
        }
        write(url, cookies)
    }
}
