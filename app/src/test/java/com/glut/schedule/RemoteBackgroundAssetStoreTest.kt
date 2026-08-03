package com.glut.schedule

import com.glut.schedule.service.background.RemoteBackgroundAssetStore
import com.glut.schedule.service.background.RemoteBackgroundDeleteResult
import com.glut.schedule.service.background.RemoteBackgroundItem
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteBackgroundAssetStoreTest {
    @Test
    fun downloadedAssetPersistsNameAndCanBeDeletedOnlyWhenInactive() {
        val root = Files.createTempDirectory("remote-background-assets").toFile()
        try {
            val store = RemoteBackgroundAssetStore(root)
            val source = root.resolve("download.tmp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val asset = store.commit(item(), source)

            assertEquals("梵高《盛开的杏花》", store.list().single().displayName)
            assertTrue(asset.file.isFile)
            assertFalse(source.exists())
            assertEquals(
                RemoteBackgroundDeleteResult.InUse,
                store.delete(asset.id, asset.sha256, asset.file.toURI().toString())
            )
            assertTrue(asset.file.isFile)
            assertEquals(
                RemoteBackgroundDeleteResult.Deleted,
                store.delete(asset.id, asset.sha256, "builtin://flower")
            )
            assertFalse(asset.file.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun catalogRefreshUpdatesDownloadedDisplayNameWithoutReplacingFile() {
        val root = Files.createTempDirectory("remote-background-name-refresh").toFile()
        try {
            val store = RemoteBackgroundAssetStore(root)
            val source = root.resolve("download.tmp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val asset = store.commit(item(), source)

            store.synchronizeCatalog(listOf(item().copy(displayName = "梵高《杏花盛开》")))

            assertEquals("梵高《杏花盛开》", store.list().single().displayName)
            assertEquals(asset.file.canonicalFile, store.list().single().file.canonicalFile)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun item() = RemoteBackgroundItem(
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
}
