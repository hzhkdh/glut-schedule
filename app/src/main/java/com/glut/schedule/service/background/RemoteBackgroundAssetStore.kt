package com.glut.schedule.service.background

import java.io.File
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

data class DownloadedRemoteBackground(
    val id: String,
    val displayName: String,
    val sha256: String,
    val originalUrl: String,
    val byteSize: Long,
    val width: Int,
    val height: Int,
    val file: File
) {
    val uri: String get() = file.toURI().toString()
}

enum class RemoteBackgroundDeleteResult { Deleted, InUse, NotFound }

/**
 * 在线原图使用独立持久目录；它们不会被自定义相册背景的“只保留当前项”清理流程误删。
 */
class RemoteBackgroundAssetStore(private val root: File) {
    fun list(): List<DownloadedRemoteBackground> {
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return emptyList()
        return root.listFiles().orEmpty()
            .filter(File::isDirectory)
            .flatMap { directory -> directory.listFiles().orEmpty().filter { it.extension == "json" } }
            .mapNotNull { metadataFile -> readAsset(metadataFile, canonicalRoot) }
            .sortedBy(DownloadedRemoteBackground::displayName)
    }

    fun commit(item: RemoteBackgroundItem, downloadedFile: File): DownloadedRemoteBackground {
        require(downloadedFile.isFile && downloadedFile.length() > 0L) { "下载文件不存在" }
        val directory = File(root, item.id).apply { mkdirs() }
        val target = File(directory, "${item.sha256}.img")
        if (!target.isFile) moveReplacing(downloadedFile, target) else downloadedFile.delete()
        val asset = DownloadedRemoteBackground(
            id = item.id,
            displayName = item.displayName,
            sha256 = item.sha256,
            originalUrl = item.originalUrl,
            byteSize = item.byteSize,
            width = item.width,
            height = item.height,
            file = target
        )
        writeMetadata(asset)
        return asset
    }

    /** JSON 名称修正只更新元数据，不移动或重新下载已存在的原图。 */
    fun synchronizeCatalog(items: List<RemoteBackgroundItem>) {
        val catalogById = items.associateBy(RemoteBackgroundItem::id)
        list().forEach { asset ->
            val current = catalogById[asset.id] ?: return@forEach
            if (current.displayName != asset.displayName) {
                writeMetadata(asset.copy(displayName = current.displayName))
            }
        }
    }

    fun find(id: String, sha256: String): DownloadedRemoteBackground? =
        list().firstOrNull { it.id == id && it.sha256 == sha256 }

    fun delete(id: String, sha256: String, activeUri: String): RemoteBackgroundDeleteResult {
        val asset = find(id, sha256) ?: return RemoteBackgroundDeleteResult.NotFound
        if (sameFileUri(activeUri, asset.file)) return RemoteBackgroundDeleteResult.InUse
        val metadata = metadataFile(asset)
        val fileDeleted = !asset.file.exists() || asset.file.delete()
        val metadataDeleted = !metadata.exists() || metadata.delete()
        if (fileDeleted && metadataDeleted) asset.file.parentFile?.delete()
        return if (fileDeleted && metadataDeleted) {
            RemoteBackgroundDeleteResult.Deleted
        } else {
            RemoteBackgroundDeleteResult.NotFound
        }
    }

    fun clearAll() {
        root.deleteRecursively()
    }

    private fun writeMetadata(asset: DownloadedRemoteBackground) {
        val target = metadataFile(asset)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        val json = JSONObject()
            .put("id", asset.id)
            .put("displayName", asset.displayName)
            .put("sha256", asset.sha256)
            .put("originalUrl", asset.originalUrl)
            .put("byteSize", asset.byteSize)
            .put("width", asset.width)
            .put("height", asset.height)
            .put("fileName", asset.file.name)
            .toString()
        temporary.writeText(json, Charsets.UTF_8)
        moveReplacing(temporary, target)
    }

    private fun readAsset(metadataFile: File, canonicalRoot: File): DownloadedRemoteBackground? = runCatching {
        val value = JSONObject(metadataFile.readText(Charsets.UTF_8))
        val file = File(metadataFile.parentFile, value.getString("fileName")).canonicalFile
        if (!file.isFile || !file.toPath().startsWith(canonicalRoot.toPath())) return null
        DownloadedRemoteBackground(
            id = value.getString("id"),
            displayName = value.getString("displayName"),
            sha256 = value.getString("sha256"),
            originalUrl = value.getString("originalUrl"),
            byteSize = value.getLong("byteSize"),
            width = value.getInt("width"),
            height = value.getInt("height"),
            file = file
        )
    }.getOrNull()

    private fun metadataFile(asset: DownloadedRemoteBackground) =
        File(asset.file.parentFile, "${asset.sha256}.json")

    private fun sameFileUri(uriText: String, file: File): Boolean = runCatching {
        val uri = URI(uriText)
        uri.scheme == "file" && File(uri).canonicalFile == file.canonicalFile
    }.getOrDefault(false)

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
