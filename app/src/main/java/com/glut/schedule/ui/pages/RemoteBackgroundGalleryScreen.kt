package com.glut.schedule.ui.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glut.schedule.service.background.DownloadedRemoteBackground
import com.glut.schedule.service.background.RemoteBackgroundItem
import com.glut.schedule.ui.components.BuiltInScheduleBackground
import com.glut.schedule.ui.components.ScheduleBackgroundStore

@Composable
fun RemoteBackgroundGalleryScreen(
    viewModel: RemoteBackgroundGalleryViewModel,
    backgroundStore: ScheduleBackgroundStore,
    activeBackgroundUri: String,
    selectedBuiltIn: BuiltInScheduleBackground?,
    onSelectBuiltIn: (BuiltInScheduleBackground) -> Unit,
    onUseDownloaded: (DownloadedRemoteBackground) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val background = Color(0xFFF6F4EF)
    val cardColor = Color(0xFFFFFEFB)
    val textColor = Color(0xFF141821)

    Column(
        modifier = Modifier
            .background(background)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("内置背景", color = textColor, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        BuiltInGalleryCard(BuiltInScheduleBackground.STARRY, selectedBuiltIn, cardColor, textColor, onSelectBuiltIn)
        BuiltInGalleryCard(BuiltInScheduleBackground.FLOWER, selectedBuiltIn, cardColor, textColor, onSelectBuiltIn)

        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("在线画廊", color = textColor, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = { viewModel.setDownloadedManagerVisible(true) }) {
                Text("已下载 ${state.downloaded.size}")
            }
            IconButton(onClick = viewModel::refresh, enabled = !state.isLoading) {
                Icon(Icons.Outlined.Refresh, contentDescription = "刷新在线背景")
            }
        }
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
                cardColor = cardColor,
                textColor = textColor,
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
private fun BuiltInGalleryCard(
    background: BuiltInScheduleBackground,
    selected: BuiltInScheduleBackground?,
    cardColor: Color,
    textColor: Color,
    onSelect: (BuiltInScheduleBackground) -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth(), color = cardColor, shape = RoundedCornerShape(14.dp)) {
        Column(modifier = Modifier.clickable { onSelect(background) }) {
            Image(
                painter = painterResource(background.drawableRes),
                contentDescription = "${background.displayName}背景预览",
                modifier = Modifier.fillMaxWidth().height(180.dp),
                contentScale = ContentScale.Crop
            )
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(background.displayName, color = textColor, fontSize = 16.sp, modifier = Modifier.weight(1f))
                if (selected == background) Icon(Icons.Outlined.Check, contentDescription = "已选择", tint = Color(0xFF2563EB))
            }
        }
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
    modifier: Modifier
) {
    var bitmap by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = uri?.let { backgroundStore.loadPreview(it, maxDimension = 1600) }
    }
    Box(modifier = modifier.background(Color(0xFFE5E7EB)), contentAlignment = Alignment.Center) {
        bitmap?.let {
            Image(bitmap = it, contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        } ?: CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
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
        onDismissRequest = onDismiss,
        title = { Text(item.displayName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RemoteBackgroundImage(previewUri, backgroundStore, Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 420.dp))
                if (isDownloading) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("正在下载原图 ${(progress * 100).toInt()}%", fontSize = 13.sp)
                } else if (isLoading) {
                    Text("正在加载大图预览…", fontSize = 13.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onUse, enabled = !isDownloading) { Text("使用并裁剪") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isDownloading) { Text("取消") } }
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
        title = { Text("已下载背景") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("共 ${assets.size} 项，占用 ${formatBytes(totalBytes)}", fontSize = 13.sp, color = Color(0xFF667085))
                if (assets.isEmpty()) Text("暂无已下载原图")
                assets.forEach { asset ->
                    val inUse = asset.uri == activeUri
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(asset.displayName, fontWeight = FontWeight.Medium)
                            Text(if (inUse) "使用中 · ${formatBytes(asset.byteSize)}" else formatBytes(asset.byteSize), fontSize = 12.sp, color = Color(0xFF667085))
                        }
                        IconButton(onClick = { pendingDelete = asset }, enabled = !inUse) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = if (inUse) "使用中，无法删除" else "删除")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
    pendingDelete?.let { asset ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除已下载原图？") },
            text = { Text("删除 ${asset.displayName} 后，如需再次使用必须重新下载。") },
            confirmButton = {
                TextButton(onClick = { onDelete(asset); pendingDelete = null }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MiB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.1f KiB".format(bytes / 1024.0)
    else -> "$bytes B"
}
