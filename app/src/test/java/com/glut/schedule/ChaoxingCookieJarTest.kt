package com.glut.schedule

import com.glut.schedule.service.chaoxing.ChaoxingCookieJar
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/** 模拟 WebView 的异步 Cookie 写入尚未完成时发生下一跳请求。 */
class ChaoxingCookieJarTest {
    @Test fun redirectImmediatelyUsesNewCookieEvenWhenWebViewStillReturnsOldValue() {
        val url = "https://mooc1.chaoxing.com/mooc2/work/list".toHttpUrl()
        val jar = ChaoxingCookieJar(
            read = { listOf(Cookie.parse(it, "session=old")!!) },
            write = { _, _ -> /* 模拟异步写入，读取端暂不变化。 */ }
        )
        assertEquals("old", jar.loadForRequest(url).single().value)
        jar.saveFromResponse(url, listOf(Cookie.parse(url, "session=new; Path=/; Secure")!!))
        assertEquals("new", jar.loadForRequest(url).single().value)
        assertEquals("new", jar.loadForRequest(url).single().value)
    }
    @Test fun neverSendsCookiesToUnrelatedHost() {
        val url = "https://mooc1.chaoxing.com/".toHttpUrl()
        val jar = ChaoxingCookieJar(read = { emptyList() }, write = { _, _ -> })
        jar.saveFromResponse(url, listOf(Cookie.parse(url, "session=new; Path=/")!!))
        assertEquals(0, jar.loadForRequest("https://example.org/".toHttpUrl()).size)
    }
}
