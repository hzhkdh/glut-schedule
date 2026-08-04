package com.glut.schedule

import com.glut.schedule.service.background.DownloadedRemoteBackground
import com.glut.schedule.service.background.RemoteBackgroundCatalog
import com.glut.schedule.service.background.RemoteBackgroundCatalogLoadResult
import com.glut.schedule.service.background.RemoteBackgroundCatalogLoadSource
import com.glut.schedule.service.background.RemoteBackgroundDeleteResult
import com.glut.schedule.service.background.RemoteBackgroundGateway
import com.glut.schedule.service.background.RemoteBackgroundItem
import com.glut.schedule.service.background.RemoteArtworkSaveResult
import com.glut.schedule.service.background.RemoteArtworkSaver
import com.glut.schedule.ui.pages.RemoteBackgroundGalleryViewModel
import com.glut.schedule.ui.pages.RemoteGalleryListPosition
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteBackgroundGalleryViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun initialLoadShowsJsonNameAndDownloadedState() = runTest {
        val gateway = FakeGateway()
        val viewModel = RemoteBackgroundGalleryViewModel(gateway)

        assertEquals("梵高《盛开的杏花》", viewModel.uiState.value.items.single().displayName)
        assertEquals(1, viewModel.uiState.value.downloaded.size)
        assertTrue(viewModel.uiState.value.thumbnailFiles.containsKey(ITEM.id))
    }

    @Test
    fun activeDownloadCannotBeDeleted() = runTest {
        val gateway = FakeGateway(deleteResult = RemoteBackgroundDeleteResult.InUse)
        val viewModel = RemoteBackgroundGalleryViewModel(gateway)

        viewModel.deleteDownloaded(ASSET, ASSET.uri)

        assertTrue(viewModel.uiState.value.message.contains("使用中"))
    }

    @Test
    fun activeDownloadKeepsPreviewOpenUntilTheOriginalIsReady() = runTest {
        val pendingDownload = CompletableDeferred<DownloadedRemoteBackground>()
        val viewModel = RemoteBackgroundGalleryViewModel(FakeGateway(download = pendingDownload))

        viewModel.openPreview(ITEM)
        viewModel.downloadAndUse(ITEM) { }
        viewModel.closePreview()

        assertEquals(ITEM, viewModel.uiState.value.selectedItem)
        assertEquals(ITEM.id, viewModel.uiState.value.downloadingId)

        pendingDownload.complete(ASSET)
        assertNull(viewModel.uiState.value.selectedItem)
        assertNull(viewModel.uiState.value.downloadingId)
    }

    @Test
    fun latePreviewResponseCannotOverwriteNewSelection() = runTest {
        val first = CompletableDeferred<File>()
        val second = CompletableDeferred<File>()
        val anotherItem = ITEM.copy(id = "another", sha256 = "b".repeat(64))
        val gateway = FakeGateway(previews = mutableListOf(first, second))
        val viewModel = RemoteBackgroundGalleryViewModel(gateway)

        viewModel.openPreview(ITEM)
        viewModel.openPreview(anotherItem)
        second.complete(File("second.webp"))
        first.complete(File("first.webp"))

        assertEquals(anotherItem, viewModel.uiState.value.selectedItem)
        assertTrue(viewModel.uiState.value.selectedPreviewFile.orEmpty().endsWith("second.webp"))
    }

    @Test
    fun downloadedAssetsRemainManageableWhenCatalogIsUnavailable() = runTest {
        val viewModel = RemoteBackgroundGalleryViewModel(FakeGateway(catalogError = true))

        assertEquals(listOf(ASSET), viewModel.uiState.value.downloaded)
        assertTrue(viewModel.uiState.value.message.contains("无法加载"))
    }

    @Test
    fun manualRefreshReportsWhenGalleryIsAlreadyUpToDate() = runTest {
        val gateway = FakeGateway(
            loadSources = mutableListOf(
                RemoteBackgroundCatalogLoadSource.NETWORK_UPDATED,
                RemoteBackgroundCatalogLoadSource.NETWORK_UNCHANGED
            )
        )
        val viewModel = RemoteBackgroundGalleryViewModel(gateway)

        viewModel.refresh()

        assertEquals("已是最新", viewModel.uiState.value.message)
    }

    @Test
    fun galleryVisibilityOnlyRefreshesAfterFiveMinutes() = runTest {
        var now = 1_000L
        val gateway = FakeGateway()
        val viewModel = RemoteBackgroundGalleryViewModel(gateway, nowMillis = { now })
        assertEquals(1, gateway.catalogLoadCount)

        now += 4 * 60 * 1_000L
        viewModel.onGalleryVisible()
        assertEquals(1, gateway.catalogLoadCount)

        now += 60 * 1_000L
        viewModel.onGalleryVisible()
        assertEquals(2, gateway.catalogLoadCount)
    }

    @Test
    fun cachedFallbackIsExplainedAfterManualRefresh() = runTest {
        val gateway = FakeGateway(
            loadSources = mutableListOf(
                RemoteBackgroundCatalogLoadSource.NETWORK_UPDATED,
                RemoteBackgroundCatalogLoadSource.CACHE_FALLBACK
            )
        )
        val viewModel = RemoteBackgroundGalleryViewModel(gateway)

        viewModel.refresh()

        assertEquals("刷新失败，正在显示上次内容", viewModel.uiState.value.message)
    }

    @Test
    fun savingArtworkDoesNotCreatePrivateBackgroundCache() = runTest {
        val gateway = FakeGateway(downloaded = mutableListOf())
        val saver = FakeArtworkSaver()
        val viewModel = RemoteBackgroundGalleryViewModel(gateway, artworkSaver = saver)

        viewModel.saveArtwork(ITEM)

        assertEquals("已保存到系统相册", viewModel.uiState.value.message)
        assertTrue(viewModel.uiState.value.downloaded.isEmpty())
        assertEquals(0, gateway.originalDownloadCount)
        assertNull(saver.cachedAsset)
    }

    @Test
    fun clearingCachePreservesTheArtworkCurrentlyUsedAsBackground() = runTest {
        val other = ASSET.copy(id = "other", sha256 = "b".repeat(64), file = File("other.img"))
        val gateway = FakeGateway(downloaded = mutableListOf(ASSET, other))
        val viewModel = RemoteBackgroundGalleryViewModel(gateway)

        viewModel.clearUnusedDownloads(activeUri = ASSET.uri)

        assertEquals(listOf(ASSET), viewModel.uiState.value.downloaded)
        assertEquals("已清理 1 项缓存", viewModel.uiState.value.message)
    }

    @Test
    fun activeAlbumSaveKeepsArtworkOpenUntilWritingFinishes() = runTest {
        val pendingSave = CompletableDeferred<RemoteArtworkSaveResult>()
        val viewModel = RemoteBackgroundGalleryViewModel(
            FakeGateway(),
            artworkSaver = PendingArtworkSaver(pendingSave)
        )

        viewModel.openPreview(ITEM)
        viewModel.saveArtwork(ITEM)
        viewModel.closePreview()

        assertEquals(ITEM, viewModel.uiState.value.selectedItem)
        assertEquals(ITEM.id, viewModel.uiState.value.savingId)

        pendingSave.complete(RemoteArtworkSaveResult("content://gallery/saved"))
        assertNull(viewModel.uiState.value.savingId)
    }

    @Test
    fun galleryScrollPositionSurvivesArtworkRoundTrip() = runTest {
        val viewModel = RemoteBackgroundGalleryViewModel(FakeGateway())

        viewModel.updateListPosition(firstVisibleItemIndex = 7, firstVisibleItemScrollOffset = 96)
        viewModel.openPreview(ITEM)
        viewModel.closePreview()

        assertEquals(RemoteGalleryListPosition(7, 96), viewModel.uiState.value.listPosition)
    }

    private class FakeGateway(
        private val deleteResult: RemoteBackgroundDeleteResult = RemoteBackgroundDeleteResult.Deleted,
        private val previews: MutableList<CompletableDeferred<File>> = mutableListOf(),
        private val catalogError: Boolean = false,
        private val download: CompletableDeferred<DownloadedRemoteBackground>? = null,
        private val loadSources: MutableList<RemoteBackgroundCatalogLoadSource> = mutableListOf(),
        private val downloaded: MutableList<DownloadedRemoteBackground> = mutableListOf(ASSET)
    ) : RemoteBackgroundGateway {
        var catalogLoadCount: Int = 0
        var originalDownloadCount: Int = 0

        override suspend fun loadCatalog(forceRefresh: Boolean): RemoteBackgroundCatalog {
            catalogLoadCount++
            if (catalogError) error("offline")
            return RemoteBackgroundCatalog("r1", "2026-08-03", listOf(ITEM))
        }
        override suspend fun loadCatalogResult(forceRefresh: Boolean): RemoteBackgroundCatalogLoadResult {
            val catalog = loadCatalog(forceRefresh)
            return RemoteBackgroundCatalogLoadResult(
                catalog = catalog,
                source = if (loadSources.isEmpty()) {
                    RemoteBackgroundCatalogLoadSource.NETWORK_UPDATED
                } else {
                    loadSources.removeAt(0)
                }
            )
        }
        override suspend fun loadPreview(item: RemoteBackgroundItem, large: Boolean): File {
            if (!large || previews.isEmpty()) return File("preview.webp")
            return previews.removeAt(0).await()
        }
        override suspend fun downloadOriginal(
            item: RemoteBackgroundItem,
            onProgress: (Float) -> Unit
        ): DownloadedRemoteBackground {
            originalDownloadCount++
            return download?.await() ?: ASSET
        }
        override fun downloadedAssets() = downloaded.toList()
        override fun deleteDownloaded(id: String, sha256: String, activeUri: String): RemoteBackgroundDeleteResult {
            if (deleteResult != RemoteBackgroundDeleteResult.Deleted) return deleteResult
            val asset = downloaded.firstOrNull { it.id == id && it.sha256 == sha256 }
                ?: return RemoteBackgroundDeleteResult.NotFound
            if (asset.uri == activeUri) return RemoteBackgroundDeleteResult.InUse
            downloaded.remove(asset)
            return RemoteBackgroundDeleteResult.Deleted
        }
        override fun clearAllData() = Unit
    }

    private class FakeArtworkSaver : RemoteArtworkSaver {
        var cachedAsset: DownloadedRemoteBackground? = null

        override suspend fun save(
            item: RemoteBackgroundItem,
            cached: DownloadedRemoteBackground?,
            onProgress: (Float) -> Unit
        ): RemoteArtworkSaveResult {
            cachedAsset = cached
            onProgress(1f)
            return RemoteArtworkSaveResult("content://gallery/saved")
        }
    }

    private class PendingArtworkSaver(
        private val result: CompletableDeferred<RemoteArtworkSaveResult>
    ) : RemoteArtworkSaver {
        override suspend fun save(
            item: RemoteBackgroundItem,
            cached: DownloadedRemoteBackground?,
            onProgress: (Float) -> Unit
        ): RemoteArtworkSaveResult = result.await()
    }

    companion object {
        private val ITEM = RemoteBackgroundItem(
            id = "van-gogh-almond-blossom",
            displayName = "梵高《盛开的杏花》",
            thumbnailUrl = "https://background.999314.xyz/generated/thumb.webp",
            previewUrl = "https://background.999314.xyz/generated/preview.webp",
            originalUrl = "https://img.999314.xyz/file/original.jpg",
            sha256 = "a".repeat(64),
            byteSize = 3,
            width = 2,
            height = 2
        )
        private val ASSET = DownloadedRemoteBackground(
            id = ITEM.id,
            displayName = ITEM.displayName,
            sha256 = ITEM.sha256,
            originalUrl = ITEM.originalUrl,
            byteSize = ITEM.byteSize,
            width = ITEM.width,
            height = ITEM.height,
            file = File("asset.img")
        )
    }
}
