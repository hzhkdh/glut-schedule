package com.glut.schedule

import com.glut.schedule.service.background.RemoteBackgroundAssetStore
import com.glut.schedule.service.background.RemoteBackgroundItem
import com.glut.schedule.service.background.RemoteBackgroundRepository
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteBackgroundRepositoryTest {
    @Test
    fun refreshFailureReturnsLastGoodCatalogAndKeepsJsonDisplayName() = runTest {
        MockWebServer().use { server ->
            val root = Files.createTempDirectory("remote-background-repository").toFile()
            try {
                server.enqueue(jsonResponse(validCatalog()))
                server.enqueue(MockResponse().setResponseCode(503))
                val repository = repository(root, server)

                val first = repository.loadCatalog(forceRefresh = false)
                val fallback = repository.loadCatalog(forceRefresh = true)

                assertEquals("梵高《盛开的杏花》", first.items.single().displayName)
                assertEquals(first, fallback)
                assertEquals(2, server.requestCount)
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun verifiedOriginalDownloadIsRegisteredForOfflineReuse() = runTest {
        MockWebServer().use { server ->
            val root = Files.createTempDirectory("remote-background-download").toFile()
            try {
                val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)
                server.enqueue(
                    MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "image/jpeg")
                        .setBody(Buffer().write(bytes))
                )
                val item = item(bytes)
                val repository = repository(root, server)

                val asset = repository.downloadOriginal(item)

                assertTrue(asset.file.isFile)
                assertEquals(item.sha256, asset.sha256)
                assertEquals(asset.file.canonicalFile, repository.downloadedAssets().single().file.canonicalFile)
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun pagesDevFallbackLoadsCatalogWhileCustomDomainIsUnavailable() = runTest {
        MockWebServer().use { server ->
            val root = Files.createTempDirectory("remote-background-fallback").toFile()
            try {
                server.enqueue(MockResponse().setResponseCode(503))
                server.enqueue(jsonResponse(validCatalog()))
                val repository = RemoteBackgroundRepository(
                    client = OkHttpClient(),
                    catalogUrl = server.url("/primary.json").toString(),
                    fallbackCatalogUrls = listOf("https://schedule-background-host.pages.dev/backgrounds.json"),
                    catalogCacheFile = root.resolve("catalog.json"),
                    previewCacheDirectory = root.resolve("previews"),
                    assetStore = RemoteBackgroundAssetStore(root.resolve("assets")),
                    urlMapper = { server.url(it.toHttpUrl().encodedPath) },
                    imageBoundsValidator = { true }
                )

                val catalog = repository.loadCatalog(forceRefresh = true)

                assertEquals("梵高《盛开的杏花》", catalog.items.single().displayName)
                assertEquals(2, server.requestCount)
            } finally {
                root.deleteRecursively()
            }
        }
    }

    private fun repository(root: java.io.File, server: MockWebServer) = RemoteBackgroundRepository(
        client = OkHttpClient(),
        catalogUrl = server.url("/backgrounds.json").toString(),
        fallbackCatalogUrls = emptyList(),
        catalogCacheFile = root.resolve("catalog.json"),
        previewCacheDirectory = root.resolve("previews"),
        assetStore = RemoteBackgroundAssetStore(root.resolve("assets")),
        urlMapper = { server.url(it.toHttpUrl().encodedPath) },
        imageBoundsValidator = { true }
    )

    private fun item(bytes: ByteArray) = RemoteBackgroundItem(
        id = "van-gogh-almond-blossom",
        displayName = "梵高《盛开的杏花》",
        thumbnailUrl = "https://background.999314.xyz/generated/thumb.webp",
        previewUrl = "https://background.999314.xyz/generated/preview.webp",
        originalUrl = "https://img.999314.xyz/file/original.jpg",
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        byteSize = bytes.size.toLong(),
        width = 2,
        height = 2
    )

    private fun validCatalog() = """
        {"protocol":1,"revision":"revision-1","generatedAt":"2026-08-03T12:00:00.000Z","items":[{
          "id":"van-gogh-almond-blossom","displayName":"梵高《盛开的杏花》",
          "thumbnailUrl":"https://background.999314.xyz/generated/thumb.webp",
          "previewUrl":"https://background.999314.xyz/generated/preview.webp",
          "originalUrl":"https://img.999314.xyz/file/original.jpg",
          "sha256":"${"a".repeat(64)}","byteSize":5058607,"width":3139,"height":2480
        }]}
    """.trimIndent()

    private fun jsonResponse(body: String) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
