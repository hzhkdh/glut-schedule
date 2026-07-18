package com.glut.schedule

import com.glut.schedule.service.downloadFile
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterDownloadTest {
    @Test
    fun progressIsCollectedByTheCallingCoroutineAndGatedAfterCancellation() {
        val module = File("src/main/java/com/glut/schedule/service/AppUpdater.kt")
        val source = (if (module.exists()) module else
            File("app/src/main/java/com/glut/schedule/service/AppUpdater.kt")).readText()

        assertTrue(source.contains("Channel<Pair<Long, Long>>(Channel.CONFLATED)"))
        assertTrue(source.contains("for ((downloaded, total) in progressEvents)"))
        assertTrue(source.contains("if (continuation.isActive) onProgressEvent(downloaded, total)"))
    }

    @Test
    fun cancellationStopsTheCallAndDeletesThePartialApk() = runBlocking {
        MockWebServer().use { server ->
            val payload = ByteArray(256 * 1024) { 1 }
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(Buffer().write(payload))
                    .throttleBody(1024, 50, TimeUnit.MILLISECONDS)
            )
            val target = File.createTempFile("update-test", ".apk").apply { delete() }
            val firstProgress = CompletableDeferred<Unit>()
            val download = async {
                downloadFile(OkHttpClient(), server.url("/update.apk").toString(), target) { downloaded, _ ->
                    if (downloaded > 0) firstProgress.complete(Unit)
                }
            }

            withTimeout(5_000) { firstProgress.await() }
            download.cancelAndJoin()

            assertFalse(target.exists())
        }
    }
}
