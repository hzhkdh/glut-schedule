package com.glut.schedule.ui.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glut.schedule.service.background.DownloadedRemoteBackground
import com.glut.schedule.service.background.RemoteBackgroundItem
import com.glut.schedule.ui.components.ScheduleBackgroundStore

private val GalleryBackgroundColor = Color(0xFFF6F4EF)
private val GallerySurfaceColor = Color(0xFFFFFEFB)
private val GalleryTextColor = Color(0xFF141821)
private val GallerySecondaryTextColor = Color(0xFF667085)
private val GalleryPrimaryColor = Color(0xFF2563EB)
private val GalleryDangerColor = Color(0xFFDC2626)
private val GalleryDialogShape = RoundedCornerShape(20.dp)

@Composable
fun RemoteBackgroundGalleryScreen(
    viewModel: RemoteBackgroundGalleryViewModel,
    backgroundStore: ScheduleBackgroundStore,
    activeBackgroundUri: String,
    onUseDownloaded: (DownloadedRemoteBackground) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .background(GalleryBackgroundColor)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (state.isLoading && state.items.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
        }
        if (state.message.isNotBlank()) {
            Text(state.message, color = Color(0xFF9A3412), fontSize = 13.sp)
        }
        state.items.forEach { item ->
            val downloaded = state.downloaded.any { it.id == item.id && it.sha256 == item.sha256 }
            RemoteGalleryCard(
                item = item,
                thumbnailUri = state.thumbnailFiles[item.id],
                downloaded = downloaded,
                backgroundStore = backgroundStore,
                cardColor = GallerySurfaceColor,
                textColor = GalleryTextColor,
                onClick = { viewModel.openPreview(item) }
            )
        }
    }

    state.selectedItem?.let { item ->
        RemoteBackgroundPreviewDialog(
            item = item,
            previewUri = state.selectedPreviewFile,
            backgroundStore = backgroundStore,
            isLoading = state.isPreviewLoading,
            isDownloading = state.downloadingId == item.id,
            progress = state.downloadProgress,
            onDismiss = viewModel::closePreview,
            onUse = { viewModel.downloadAndUse(item, onUseDownloaded) }
        )
    }

    if (state.showDownloadedManager) {
        DownloadedBackgroundManagerDialog(
            assets = state.downloaded,
            activeUri = activeBackgroundUri,
            onDismiss = { viewModel.setDownloadedManagerVisible(false) },
            onDelete = { asset -> viewModel.deleteDownloaded(asset, activeBackgroundUri) }
        )
    }
}

@Composable
private fun RemoteGalleryCard(
    item: RemoteBackgroundItem,
    thumbnailUri: String?,
    downloaded: Boolean,
    backgroundStore: ScheduleBackgroundStore,
    cardColor: Color,
    textColor: Color,
    onClick: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth(), color = cardColor, shape = RoundedCornerShape(14.dp)) {
        Column(modifier = Modifier.clickable(onClick = onClick)) {
            RemoteBackgroundImage(thumbnailUri, backgroundStore, Modifier.fillMaxWidth().height(180.dp))
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(item.displayName, color = textColor, fontSize = 16.sp, modifier = Modifier.weight(1f))
                if (downloaded) {
                    Icon(Icons.Outlined.DownloadDone, contentDescription = "已下载", tint = Color(0xFF15803D), modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun RemoteBackgroundImage(
    uri: String?,
    backgroundStore: ScheduleBackgroundStore,
    modifier: Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    var bitmap by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = uri?.let { backgroundStore.loadPreview(it, maxDimension = 1600) }
    }
    Box(modifier = modifier.background(Color(0xFFE5E7EB)), contentAlignment = Alignment.Center) {
        bitmap?.let {
            Image(bitmap = it, contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = contentScale)
        } ?: CircularProgressIndicator(modifier = Modifier.size(24.dp), color = GalleryPrimaryColor, strokeWidth = 2.dp)
    }
}

@Composable
private fun RemoteBackgroundPreviewDialog(
    item: RemoteBackgroundItem,
    previewUri: String?,
    backgroundStore: ScheduleBackgroundStore,
    isLoading: Boolean,
    isDownloading: Boolean,
    progress: Float,
    onDismiss: () -> Unit,
    onUse: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {
            if (canDismissRemoteBackgroundPreview(isDownloading)) onDismiss()
        },
        shape = GalleryDialogShape,
        containerColor = GallerySurfaceColor,
        titleContentColor = GalleryTextColor,
        textContentColor = GalleryTextColor,
        title = { Text(item.displayName, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RemoteBackgroundImage(
                    uri = previewUri,
                    backgroundStore = backgroundStore,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp, max = 420.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit
                )
                if (isDownloading) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = GalleryPrimaryColor,
                        trackColor = Color(0xFFDCE6FA)
                    )
                    Text("正在下载原图 ${(progress * 100).toInt()}%", color = GallerySecondaryTextColor, fontSize = 13.sp)
                } else if (isLoading) {
                    Text("正在加载大图预览…", color = GallerySecondaryTextColor, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onUse,
                enabled = !isDownloading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = GalleryPrimaryColor,
                    contentColor = Color.White
                )
            ) { Text("使用并裁剪") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isDownloading,
                colors = ButtonDefaults.textButtonColors(contentColor = GalleryPrimaryColor)
            ) { Text("取消") }
        }
    )
}

@Composable
private fun DownloadedBackgroundManagerDialog(
    assets: List<DownloadedRemoteBackground>,
    activeUri: String,
    onDismiss: () -> Unit,
    onDelete: (DownloadedRemoteBackground) -> Unit
) {
    var pendingDelete by remember { mutableStateOf<DownloadedRemoteBackground?>(null) }
    val totalBytes = assets.sumOf(DownloadedRemoteBackground::byteSize)
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = GalleryDialogShape,
        containerColor = GallerySurfaceColor,
        titleContentColor = GalleryTextColor,
        textContentColor = GalleryTextColor,
        title = { Text("已下载背景", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("共 ${assets.size} 项，占用 ${formatBytes(totalBytes)}", fontSize = 13.sp, color = GallerySecondaryTextColor)
                if (assets.isEmpty()) Text("暂无已下载原图")
                assets.forEach { asset ->
                    val inUse = asset.uri == activeUri
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(asset.displayName, fontWeight = FontWeight.Medium)
                            Text(if (inUse) "使用中 · ${formatBytes(asset.byteSize)}" else formatBytes(asset.byteSize), fontSize = 12.sp, color = GallerySecondaryTextColor)
                        }
                        IconButton(onClick = { pendingDelete = asset }, enabled = !inUse) {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = if (inUse) "使用中，无法删除" else "删除 ${asset.displayName}",
                                tint = if (inUse) GallerySecondaryTextColor.copy(alpha = 0.38f) else GalleryDangerColor
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = GalleryPrimaryColor)
            ) { Text("完成") }
        }
    )
    pendingDelete?.let { asset ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            shape = GalleryDialogShape,
            containerColor = GallerySurfaceColor,
            titleContentColor = GalleryTextColor,
            textContentColor = GalleryTextColor,
            title = { Text("删除已下载原图？", fontWeight = FontWeight.SemiBold) },
            text = { Text("删除 ${asset.displayName} 后，如需再次使用必须重新下载。") },
            confirmButton = {
                TextButton(
                    onClick = { onDelete(asset); pendingDelete = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = GalleryDangerColor)
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingDelete = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = GalleryPrimaryColor)
                ) { Text("取消") }
            }
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MiB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.1f KiB".format(bytes / 1024.0)
    else -> "$bytes B"
}

internal fun remoteBackgroundDownloadBadgeText(downloadedCount: Int): String? =
    downloadedCount.takeIf { it > 0 }?.toString()

internal fun canDismissRemoteBackgroundPreview(isDownloading: Boolean): Boolean = !isDownloading
