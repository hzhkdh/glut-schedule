package com.glut.schedule

import com.glut.schedule.service.chaoxing.ChaoxingCookieJar
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/** 模拟 WebView 的异步 Cookie 写入尚未完成时发生下一跳请求。 */
class ChaoxingCookieJarTest {
    // 直接构造服务端已解析的 Cookie，单测不依赖 Android 的公共后缀资源加载器。
    private fun parentCookie(value: String, path: String = "/", deleted: Boolean = false): Cookie =
        Cookie.Builder().name("session").value(value).domain("chaoxing.com").path(path)
            .secure().expiresAt(if (deleted) 1L else Long.MAX_VALUE).build()

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
    @Test fun parentDomainRotationReplacesSyntheticHostCookie() {
        val url = "https://mooc1.chaoxing.com/mooc2/work/list".toHttpUrl()
        val jar = ChaoxingCookieJar(read = { listOf(Cookie.parse(it, "session=old")!!) }, write = { _, _ -> })
        assertEquals("old", jar.loadForRequest(url).single().value)
        jar.saveFromResponse(url, listOf(parentCookie("new")))
        assertEquals("new", jar.loadForRequest(url).single().value)
        assertEquals("new", jar.loadForRequest("https://mooc2-ans.chaoxing.com/course".toHttpUrl()).single().value)
    }
    @Test fun parentDomainDeletionDoesNotResurrectSeededCookie() {
        val url = "https://mooc1.chaoxing.com/".toHttpUrl()
        val jar = ChaoxingCookieJar(read = { listOf(Cookie.parse(it, "session=old")!!) }, write = { _, _ -> })
        jar.loadForRequest(url)
        jar.saveFromResponse(url, listOf(parentCookie("", deleted = true)))
        assertEquals(0, jar.loadForRequest(url).size)
        assertEquals(0, jar.loadForRequest("https://mooc2-ans.chaoxing.com/".toHttpUrl()).size)
    }
    @Test fun authoritativePathCookieTakesPrecedenceOverInferredRootSeed() {
        val root = "https://mooc1.chaoxing.com/".toHttpUrl()
        val jar = ChaoxingCookieJar(read = { listOf(Cookie.parse(it, "session=old")!!) }, write = { _, _ -> })
        jar.loadForRequest(root)
        val work = root.resolve("/work/list")!!
        jar.saveFromResponse(work, listOf(parentCookie("new", path = "/work")))
        assertEquals("new", jar.loadForRequest(work).single().value)
        assertEquals("old", jar.loadForRequest(root).single().value)
    }
    @Test fun realServerCookiesWithDifferentDomainsAreNotTreatedAsSynthetic() {
        val url = "https://mooc1.chaoxing.com/".toHttpUrl()
        val jar = ChaoxingCookieJar(read = { emptyList() }, write = { _, _ -> })
        jar.saveFromResponse(url, listOf(Cookie.parse(url, "session=host; Path=/")!!))
        jar.saveFromResponse(url, listOf(parentCookie("parent")))
        assertEquals(setOf("host", "parent"), jar.loadForRequest(url).map { it.value }.toSet())
    }
}
