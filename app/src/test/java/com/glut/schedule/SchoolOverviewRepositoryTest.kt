package com.glut.schedule

import com.glut.schedule.service.campus.SchoolOverviewRepository
import com.glut.schedule.service.campus.SchoolOverviewTopic
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import com.glut.schedule.service.network.awaitResponse
import okhttp3.OkHttpClient
import okhttp3.Request
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

    @Test fun openingCachedSchoolTopicDoesNotRefreshUntilRequested() = runTest {
        MockWebServer().use { server ->
            val directory = temporary.newFolder()
            directory.resolve("leaders.json").writeText(snapshot)
            val repository = repository(directory, server, now = { 86_400_000L })
            // 过期快照也可以直接阅读，只有明确点击刷新才替换。
            repository.loadInitial(topic)
            assertEquals(1000L, repository.observe(topic).value?.fetchedAt)
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setBody(snapshot.replace("1000", "2000")))
            assertEquals(2000L, repository.refresh(topic, force = true).fetchedAt)
            assertEquals("/snapshot/leaders?refresh=1", server.takeRequest().path)
        }
    }

    @Test fun cacheSurvivesRestartAndInvalidOrUnavailableUpstream() = runTest {
        MockWebServer().use { server ->
            val directory = temporary.newFolder()
            val repository = repository(directory, server)
            server.enqueue(MockResponse().setBody(snapshot))
            val document = repository.refresh(topic, force = true)
            assertEquals("主持学校工作。\n主管校长办公室。联系甲学院、乙学院。", document.people.single().duties)
            assertEquals("/snapshot/leaders?refresh=1", server.takeRequest().path)

            val restarted = repository(directory, server)
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
            val repository = repository(temporary.newFolder(), server)
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
            val repository = repository(temporary.newFolder(), server)
            val path = "/__local/A/BB/CC/official.jpg"
            val url = "https://www.glut.edu.cn$path"
            val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
            server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(jpeg)))
            assertArrayEquals(jpeg, repository.loadImage(topic, url))
            assertArrayEquals(jpeg, repository.loadImage(topic, url))
            assertNull(repository.loadImage(topic, url + "?other=1"))
            assertNull(repository.loadImage(topic, "https://example.org" + path))
            assertEquals(path, server.takeRequest().path)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun refreshedSnapshotRechecksSamePhotoUrlAndRetainsItWhenOffline() = runTest {
        MockWebServer().use { server ->
            val directory = temporary.newFolder()
            var clock = 1000L
            val repository = repository(directory, server, now = { clock })
            val path = "/__local/A/BB/CC/official.jpg"
            val url = "https://www.glut.edu.cn$path"
            val payload = snapshot.replace("\"photoUrl\":\"\"", "\"photoUrl\":\"$url\"")
            val original = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1)
            val replacement = original.copyOf().also { it[it.lastIndex] = 2 }
            fun image(bytes: ByteArray) = MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(bytes))

            server.enqueue(MockResponse().setBody(payload))
            repository.refresh(topic)
            server.enqueue(image(original))
            assertArrayEquals(original, repository.loadImage(topic, url))
            assertArrayEquals(original, repository.loadImage(topic, url))
            assertEquals(2, server.requestCount)

            clock = 2000L
            server.enqueue(MockResponse().setBody(payload.replace("1000", "2000")))
            repository.refresh(topic, force = true)
            server.enqueue(image(replacement))
            assertArrayEquals(replacement, repository.loadImage(topic, url))
            repeat(3) { server.takeRequest() }
            assertEquals("no-cache", server.takeRequest().getHeader("Cache-Control"))

            clock = 3000L
            server.enqueue(MockResponse().setBody(payload.replace("1000", "3000")))
            repository.refresh(topic, force = true)
            server.enqueue(MockResponse().setResponseCode(502))
            assertArrayEquals(replacement, repository.loadImage(topic, url))
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
            val repository = repository(directory, server, now = { 1000 })
            repository.loadCached(topic)
            assertNull(repository.observe(topic).value?.diagramUrl)
            val path = "/__local/A/BB/CC/diagram.jpg"
            val diagram = "https://www.glut.edu.cn$path"
            data.put("diagramUrl", diagram)
            server.enqueue(MockResponse().setBody(document.toString()))
            assertEquals(diagram, repository.refresh(topic).diagramUrl)
            assertEquals("/snapshot/history", server.takeRequest().path)
        }
    }

    @Test fun songStoresAllReadingSectionsAndUsesOfficialVideoEvenFromLegacyCache() = runTest {
        MockWebServer().use { server ->
            val topic = SchoolOverviewTopic.SONG
            val directory = temporary.newFolder()
            val repository = repository(directory, server)
            val video = server.url("/school-overview/media/song").toString()
            val data = songData(video)
            val document = JSONObject(snapshot).put("topic", topic.key).put("data", data)
            server.enqueue(MockResponse().setBody(document.toString()))
            val song = requireNotNull(repository.refresh(topic).song)
            assertEquals("https://www.glut.edu.cn/20250407xiaoge.mp4", song.videoUrl)
            assertEquals(listOf("第一行", "第二行"), song.lyrics)
            assertEquals("注释", song.notes.single().text)
            assertEquals(3, song.meaning.size)
            assertEquals("来源", song.mottoSections.last().paragraphs.single())
            val restarted = repository(directory, server)
            restarted.loadCached(topic)
            data.put("videoUrl", "https://example.org/school-overview/media/song")
            server.enqueue(MockResponse().setBody(document.toString()))
            assertEquals(song, restarted.refresh(topic, force = true).song)
            assertEquals(song, restarted.observe(topic).value?.song)
        }
    }

    @Test fun legacyWorkerImageOnlyUsesDiskAndSharesTheOfficialImageCache() = runTest {
        MockWebServer().use { server ->
            val directory = temporary.newFolder()
            val repository = repository(directory, server)
            val id = "b386f4814947f25bbe3689f9c718b352"
            val url = "https://glut-api.999314.xyz/school-overview/photo/leaders/$id"
            assertNull(repository.loadImage(topic, url))
            val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
            val photos = directory.resolve("photos").apply { mkdirs() }
            photos.resolve("leaders-$id.image").writeBytes(jpeg)
            assertArrayEquals(jpeg, repository.loadImage(topic, url))
            assertArrayEquals(jpeg, repository.loadImage(topic, "https://www.glut.edu.cn/__local/A/BB/CC/official.jpg"))
            assertEquals(0, server.requestCount)
        }
    }

    private fun repository(directory: java.io.File, server: MockWebServer, now: () -> Long = System::currentTimeMillis): SchoolOverviewRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            // 桩只承接官网图片，意外请求代理地址会使测试失败。
            if (request.url.host == "www.glut.edu.cn") {
                chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath)).build())
            } else {
                check(request.url.host == server.url("/").host)
                chain.proceed(request)
            }
        }.build()
        return SchoolOverviewRepository(directory, client, now) { topic, force ->
            val path = "/snapshot/" + topic.key + if (force) "?refresh=1" else ""
            client.newCall(Request.Builder().url(server.url(path)).build()).awaitResponse { response ->
                if (!response.isSuccessful) throw IOException("测试上游不可用")
                response.body.string()
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
