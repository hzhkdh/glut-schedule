package com.glut.schedule

import com.glut.schedule.service.campus.SchoolOverviewRepository
import com.glut.schedule.service.campus.SchoolOverviewTopic
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
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

    @Test fun refreshedSnapshotRechecksSamePhotoUrlAndRetainsItWhenOffline() = runTest {
        MockWebServer().use { server ->
            val directory = temporary.newFolder()
            var clock = 1000L
            val repository = SchoolOverviewRepository(directory, server.url("/"), now = { clock })
            val path = "/school-overview/photo/leaders/" + "a".repeat(32)
            val url = server.url(path).toString()
            val payload = snapshot.replace("\"photoUrl\":\"\"", "\"photoUrl\":\"$url\"")
            val original = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1)
            val replacement = original.copyOf().also { it[it.lastIndex] = 2 }
            fun image(bytes: ByteArray) = MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(bytes))

            server.enqueue(MockResponse().setBody(payload))
            repository.refresh(topic)
            server.enqueue(image(original))
            assertArrayEquals(original, repository.loadImage(url))
            assertArrayEquals(original, repository.loadImage(url))
            assertEquals(2, server.requestCount)

            clock = 2000L
            server.enqueue(MockResponse().setBody(payload.replace("1000", "2000")))
            repository.refresh(topic, force = true)
            server.enqueue(image(replacement))
            assertArrayEquals(replacement, repository.loadImage(url))
            repeat(3) { server.takeRequest() }
            assertEquals("no-cache", server.takeRequest().getHeader("Cache-Control"))

            clock = 3000L
            server.enqueue(MockResponse().setBody(payload.replace("1000", "3000")))
            repository.refresh(topic, force = true)
            server.enqueue(MockResponse().setResponseCode(502))
            assertArrayEquals(replacement, repository.loadImage(url))
        }
    }

    @Test fun freshLegacyHistoryStillRequestsTheNewDiagram() = runTest {
        MockWebServer().use { server ->
            val topic = SchoolOverviewTopic.HISTORY
            val directory = temporary.newFolder()
            val periods = JSONArray(List(4) { index ->
                mapOf("id" to "period-$index", "title" to "时期", "range" to "1956—1978",
                    "events" to listOf(mapOf("date" to "1958年8月", "text" to "更名")))
            })
            val data = JSONObject().put("periods", periods)
            val document = JSONObject(snapshot).put("topic", topic.key).put("data", data)
            directory.resolve("history.json").writeText(document.toString())
            val repository = SchoolOverviewRepository(directory, server.url("/"), now = { 1000 })
            repository.loadCached(topic)
            assertNull(repository.observe(topic).value?.diagramUrl)
            val path = "/school-overview/photo/history/" + "b".repeat(32)
            val diagram = server.url(path).toString()
            data.put("diagramUrl", diagram)
            server.enqueue(MockResponse().setBody(document.toString()))
            assertEquals(diagram, repository.refresh(topic).diagramUrl)
            assertEquals("/school-overview/history", server.takeRequest().path)
        }
    }

    @Test fun songStoresAllReadingSectionsAndRejectsForeignMedia() = runTest {
        MockWebServer().use { server ->
            val topic = SchoolOverviewTopic.SONG
            val directory = temporary.newFolder()
            val repository = SchoolOverviewRepository(directory, server.url("/"))
            val video = server.url("/school-overview/media/song").toString()
            val data = songData(video)
            val document = JSONObject(snapshot).put("topic", topic.key).put("data", data)
            server.enqueue(MockResponse().setBody(document.toString()))
            val song = requireNotNull(repository.refresh(topic).song)
            assertEquals(video, song.videoUrl)
            assertNull(song.originalVideoUrl)
            assertEquals(listOf("第一行", "第二行"), song.lyrics)
            assertEquals("注释", song.notes.single().text)
            assertEquals(3, song.meaning.size)
            assertEquals("来源", song.mottoSections.last().paragraphs.single())
            val restarted = SchoolOverviewRepository(directory, server.url("/"))
            restarted.loadCached(topic)
            data.put("videoUrl", "https://example.org/school-overview/media/song")
            server.enqueue(MockResponse().setBody(document.toString()))
            assertTrue(runCatching { restarted.refresh(topic, force = true) }.exceptionOrNull() is IOException)
            assertEquals(song, restarted.observe(topic).value?.song)
        }
    }

    @Test fun songAcceptsOnlyTheSameOriginOriginalMediaRoute() = runTest {
        MockWebServer().use { server ->
            val topic = SchoolOverviewTopic.SONG
            val directory = temporary.newFolder()
            val repository = SchoolOverviewRepository(directory, server.url("/"))
            val video = server.url("/school-overview/media/song").toString()
            val original = server.url("/school-overview/media/song/original").toString()
            val data = songData(video).put("originalVideoUrl", original)
            val document = JSONObject(snapshot).put("topic", topic.key).put("data", data)
            server.enqueue(MockResponse().setBody(document.toString()))
            val song = requireNotNull(repository.refresh(topic).song)
            assertEquals(video, song.videoUrl)
            assertEquals(original, song.originalVideoUrl)

            val restarted = SchoolOverviewRepository(directory, server.url("/"))
            restarted.loadCached(topic)
            assertEquals(song, restarted.observe(topic).value?.song)

            listOf(
                JSONObject.NULL, 123, "", "not a URL", video, "$original?download=1", "$original#clip",
                " $original", server.url("/school-overview/media/song/original/").toString(),
                server.url("/school-overview/media/song/other/").toString() + "../original",
                "https://example.org/school-overview/media/song/original"
            ).forEach { invalid ->
                // 原画字段无效时，流畅视频和所有阅读内容仍可使用。
                data.put("originalVideoUrl", invalid)
                server.enqueue(MockResponse().setBody(document.toString()))
                assertEquals(song.copy(originalVideoUrl = null), repository.refresh(topic, force = true).song)
            }
        }
    }

    private fun songData(video: String) = JSONObject()
        .put("title", "测试校歌")
        .put("videoUrl", video)
        .put("lyrics", JSONArray(listOf("第一行", "第二行")))
        .put("notes", JSONArray(listOf(mapOf("term" to "词语", "text" to "注释"))))
        .put("meaning", JSONArray(listOf("第一段", "第二段", "第三段")))
        .put("mottoSections", JSONArray(listOf(
            mapOf("title" to "涵义阐释", "paragraphs" to listOf("说明")),
            mapOf("title" to "出处、典故", "paragraphs" to listOf("来源"))
        )))
}
