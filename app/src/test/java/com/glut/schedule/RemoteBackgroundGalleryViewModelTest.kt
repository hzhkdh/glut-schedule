package com.glut.schedule

import com.glut.schedule.service.background.DownloadedRemoteBackground
import com.glut.schedule.service.background.RemoteBackgroundCatalog
import com.glut.schedule.service.background.RemoteBackgroundDeleteResult
import com.glut.schedule.service.background.RemoteBackgroundGateway
import com.glut.schedule.service.background.RemoteBackgroundItem
import com.glut.schedule.ui.pages.RemoteBackgroundGalleryViewModel
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

    private class FakeGateway(
        private val deleteResult: RemoteBackgroundDeleteResult = RemoteBackgroundDeleteResult.Deleted,
        private val previews: MutableList<CompletableDeferred<File>> = mutableListOf(),
        private val catalogError: Boolean = false,
        private val download: CompletableDeferred<DownloadedRemoteBackground>? = null
    ) : RemoteBackgroundGateway {
        override suspend fun loadCatalog(forceRefresh: Boolean): RemoteBackgroundCatalog {
            if (catalogError) error("offline")
            return RemoteBackgroundCatalog("r1", "2026-08-03", listOf(ITEM))
        }
        override suspend fun loadPreview(item: RemoteBackgroundItem, large: Boolean): File {
            if (!large || previews.isEmpty()) return File("preview.webp")
            return previews.removeAt(0).await()
        }
        override suspend fun downloadOriginal(
            item: RemoteBackgroundItem,
            onProgress: (Float) -> Unit
        ) = download?.await() ?: ASSET
        override fun downloadedAssets() = listOf(ASSET)
        override fun deleteDownloaded(id: String, sha256: String, activeUri: String) = deleteResult
        override fun clearAllData() = Unit
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
