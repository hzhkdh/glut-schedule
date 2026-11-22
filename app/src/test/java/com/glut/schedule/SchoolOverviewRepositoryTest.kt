package com.glut.schedule

import com.glut.schedule.service.campus.SchoolOverviewRepository
import com.glut.schedule.service.campus.SchoolOverviewTopic
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.TimeUnit

class SchoolOverviewRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    private val topic = SchoolOverviewTopic.LEADERS
    private val snapshot = """
        {"topic":"leaders","fetchedAt":1000,
        "sources":[{"label":"学校官网","url":"https://www.glut.edu.cn/xxgk/xrld.htm"}],
        "data":{"people":[{"id":"leader-one","name":"某某","role":"校长",
        "photoUrl":"","duties":"主持学校工作。\n主管校长办公室。联系甲学院、乙学院。"}]}}
    """.trimIndent()

    @Test fun cacheSurvivesRestartAndInvalidOrUnavailableUpstream() = runTest {
        MockWebServer().use { server ->
            val directory = temporary.newFolder()
            val repository = SchoolOverviewRepository(directory, server.url("/"))
            server.enqueue(MockResponse().setBody(snapshot))
            val document = repository.refresh(topic, force = true)
            assertEquals("主持学校工作。\n主管校长办公室。联系甲学院、乙学院。", document.people.single().duties)
            assertEquals("/school-overview/leaders?refresh=1", server.takeRequest().path)

            val restarted = SchoolOverviewRepository(directory, server.url("/"))
            restarted.loadCached(topic)
            assertEquals(document, restarted.observe(topic).value)
            server.enqueue(MockResponse().setBody(snapshot.replace("leader-one", "")))
            server.enqueue(MockResponse().setResponseCode(502))
            repeat(2) {
                val failure = runCatching { restarted.refresh(topic, force = true) }.exceptionOrNull()
                assertTrue(failure is IOException)
                assertEquals(document, restarted.observe(topic).value)
            }
        }
    }

    @Test fun overlappingManualAndScheduledRefreshUseOneRequest() = runTest {
        MockWebServer().use { server ->
            val repository = SchoolOverviewRepository(temporary.newFolder(), server.url("/"))
            server.enqueue(MockResponse().setBody(snapshot).setBodyDelay(150, TimeUnit.MILLISECONDS))
            server.enqueue(MockResponse().setResponseCode(500))
            val manual = async { repository.refresh(topic, force = true) }
            val scheduled = async { repository.refresh(topic, force = true) }
            assertEquals(manual.await(), scheduled.await())
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun sameOriginPhotoIsCachedAndForeignPhotoIsNeverRequested() = runTest {
        MockWebServer().use { server ->
            val repository = SchoolOverviewRepository(temporary.newFolder(), server.url("/"))
            val path = "/school-overview/photo/leaders/" + "a".repeat(32)
            val url = server.url(path).toString()
            val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
            server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(jpeg)))
            assertArrayEquals(jpeg, repository.loadImage(url))
            assertArrayEquals(jpeg, repository.loadImage(url))
            assertNull(repository.loadImage(url + "?other=1"))
            assertNull(repository.loadImage("https://example.org" + path))
            assertEquals(path, server.takeRequest().path)
            assertEquals(1, server.requestCount)
        }
    }
}
