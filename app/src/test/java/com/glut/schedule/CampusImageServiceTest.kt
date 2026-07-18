package com.glut.schedule

import com.glut.schedule.service.campus.CampusImageCache
import com.glut.schedule.service.campus.CampusImageDocument
import com.glut.schedule.service.campus.CampusImageService
import com.glut.schedule.service.campus.CampusImageType
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CampusImageServiceTest {
    @Test
    fun calendarUsesTheFirstOfficialImageWithoutReconstructingItsContents() = runTest {
        MockWebServer().use { server ->
            server.enqueue(html("""
                <div class="xiangxi"><img src="/calendar.png"></div>
                <div class="xiangxi"><img src="/class-time.png"></div>
            """.trimIndent()))
            server.enqueue(image(PNG_BYTES))
            val service = service(server)

            val document = service.fetch(CampusImageType.ACADEMIC_CALENDAR)

            assertEquals(server.url("/calendar.png").toString(), document.imageUrl)
            assertArrayEquals(PNG_BYTES, document.bytes)
            assertFalse(document.fromCache)
            assertEquals("/calendar-page", server.takeRequest().path)
            assertEquals("/calendar.png", server.takeRequest().path)
        }
    }

    @Test
    fun shuttleResolvesTheOfficialRelativeRouteImage() = runTest {
        MockWebServer().use { server ->
            server.enqueue(html("<main><img src=\"./img/xcsj.png\"></main>"))
            server.enqueue(image(PNG_BYTES))

            val document = service(server).fetch(CampusImageType.SHUTTLE_BUS, forceRefresh = true)

            assertEquals(server.url("/img/xcsj.png").toString(), document.imageUrl)
            assertEquals("no-cache", server.takeRequest().getHeader("Cache-Control"))
            assertEquals("no-cache", server.takeRequest().getHeader("Cache-Control"))
        }
    }

    @Test
    fun imageUrlOutsideTheAllowedHttpsHostsIsRejectedBeforeDownload() = runTest {
        MockWebServer().use { server ->
            server.enqueue(html("<img src=\"https://example.com/calendar.png\">"))

            val error = runCatching {
                service(server).fetch(CampusImageType.ACADEMIC_CALENDAR)
            }.exceptionOrNull()

            assertTrue(error is IOException)
            assertTrue(error?.message.orEmpty().contains("图片地址"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun nonImageResponseIsRejected() = runTest {
        MockWebServer().use { server ->
            server.enqueue(html("<img src=\"/calendar.png\">"))
            server.enqueue(html("<html>not an image</html>"))

            val error = runCatching {
                service(server).fetch(CampusImageType.ACADEMIC_CALENDAR)
            }.exceptionOrNull()

            assertTrue(error is IOException)
            assertTrue(error?.message.orEmpty().contains("图片格式"))
        }
    }

    @Test
    fun networkFailureReturnsTheLastSuccessfulCache() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            val cached = CampusImageDocument(
                imageUrl = "https://xxfw.glut.edu.cn/cached.png",
                bytes = PNG_BYTES,
                fetchedAt = 1234L,
                fromCache = false
            )
            val cache = MemoryCache(cached)

            val document = service(server, cache).fetch(CampusImageType.SHUTTLE_BUS)

            assertTrue(document.fromCache)
            assertEquals(1234L, document.fetchedAt)
            assertArrayEquals(PNG_BYTES, document.bytes)
        }
    }

    @Test
    fun networkFailureWithoutCacheKeepsTheOriginalError() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))

            assertThrows(IOException::class.java) {
                kotlinx.coroutines.runBlocking {
                    service(server).fetch(CampusImageType.SHUTTLE_BUS)
                }
            }
        }
    }

    @Test
    fun relativeImageUsesTheFinalOfficialPageAfterRedirect() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/actual/page.html"))
            server.enqueue(html("<img src=\"image.png\">"))
            server.enqueue(image(PNG_BYTES))

            val document = service(server).fetch(CampusImageType.ACADEMIC_CALENDAR)

            assertEquals(server.url("/actual/image.png").toString(), document.imageUrl)
        }
    }

    @Test
    fun redirectOutsideTheAllowedHostIsRejectedBeforeFollowingIt() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.com/page"))

            val error = runCatching {
                service(server).fetch(CampusImageType.ACADEMIC_CALENDAR)
            }.exceptionOrNull()

            assertTrue(error is IOException)
            assertTrue(error?.message.orEmpty().contains("地址"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun imageRedirectOutsideTheAllowedHostIsRejected() = runTest {
        MockWebServer().use { server ->
            server.enqueue(html("<img src=\"/calendar.png\">"))
            server.enqueue(
                MockResponse().setResponseCode(302).setHeader("Location", "https://example.com/image.png")
            )

            val error = runCatching {
                service(server).fetch(CampusImageType.ACADEMIC_CALENDAR)
            }.exceptionOrNull()

            assertTrue(error is IOException)
            assertTrue(error?.message.orEmpty().contains("图片地址"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun oversizedResponseIsRejectedBeforeReadingItsBody() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/html")
                    .setBody("x".repeat(600_000))
            )

            val error = runCatching {
                service(server).fetch(CampusImageType.ACADEMIC_CALENDAR)
            }.exceptionOrNull()

            assertTrue(error is IOException)
            assertTrue(error?.message.orEmpty().contains("过大"))
        }
    }

    @Test
    fun cacheWriteFailureDoesNotDiscardDownloadedImage() = runTest {
        MockWebServer().use { server ->
            server.enqueue(html("<img src=\"/calendar.png\">"))
            server.enqueue(image(PNG_BYTES))
            val cache = object : CampusImageCache {
                override fun load(type: CampusImageType): CampusImageDocument? = null
                override fun save(type: CampusImageType, document: CampusImageDocument) {
                    throw IOException("disk full")
                }
            }

            val document = service(server, cache).fetch(CampusImageType.ACADEMIC_CALENDAR)

            assertArrayEquals(PNG_BYTES, document.bytes)
            assertFalse(document.fromCache)
        }
    }

    private fun service(server: MockWebServer, cache: CampusImageCache = MemoryCache()) = CampusImageService(
        client = OkHttpClient(),
        cache = cache,
        pageUrls = mapOf(
            CampusImageType.ACADEMIC_CALENDAR to server.url("/calendar-page"),
            CampusImageType.SHUTTLE_BUS to server.url("/shuttle-page")
        ),
        allowedImageHosts = setOf(server.hostName),
        now = { 5678L }
    )

    private class MemoryCache(initial: CampusImageDocument? = null) : CampusImageCache {
        private var document = initial
        override fun load(type: CampusImageType): CampusImageDocument? = document
        override fun save(type: CampusImageType, document: CampusImageDocument) {
            this.document = document
        }
    }

    companion object {
        private val PNG_BYTES = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D
        )

        private fun html(body: String) = MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/html; charset=UTF-8")
            .setBody(body)

        private fun image(bytes: ByteArray) = MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "image/png")
            .setBody(Buffer().write(bytes))
    }
}
