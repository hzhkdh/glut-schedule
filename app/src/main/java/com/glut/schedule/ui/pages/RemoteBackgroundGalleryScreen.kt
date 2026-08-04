package com.glut.schedule.ui.pages

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
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
private val ArtworkBackgroundColor = Color(0xFF101116)

@Composable
fun RemoteBackgroundGalleryScreen(
    viewModel: RemoteBackgroundGalleryViewModel,
    backgroundStore: ScheduleBackgroundStore,
    activeBackgroundUri: String,
    onUseDownloaded: (DownloadedRemoteBackground) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val galleryListState = rememberLazyListState(
        initialFirstVisibleItemIndex = state.listPosition.firstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = state.listPosition.firstVisibleItemScrollOffset
    )
    var pendingPermissionItem by remember { mutableStateOf<RemoteBackgroundItem?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val item = pendingPermissionItem
        pendingPermissionItem = null
        if (granted && item != null) {
            viewModel.saveArtwork(item)
        } else if (!granted) {
            viewModel.showMessage("未获得存储权限，无法保存到系统相册")
        }
    }

    fun requestSave(item: RemoteBackgroundItem) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.saveArtwork(item)
        } else {
            pendingPermissionItem = item
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    LaunchedEffect(Unit) { viewModel.onGalleryVisible() }
    LaunchedEffect(state.message) {
        if (state.message.isNotBlank()) {
            snackbarHostState.showSnackbar(state.message)
            viewModel.clearMessage()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(GalleryBackgroundColor)) {
        if (state.selectedItem == null) {
            GalleryList(
                state = state,
                listState = galleryListState,
                backgroundStore = backgroundStore,
                onItemClick = { item ->
                    // 仅在进入详情前保存位置，避免像素级滚动持续更新整个页面状态。
                    viewModel.updateListPosition(
                        galleryListState.firstVisibleItemIndex,
                        galleryListState.firstVisibleItemScrollOffset
                    )
                    viewModel.openPreview(item)
                },
                onRetry = viewModel::refresh
            )
        } else {
            val item = state.selectedItem ?: return@Box
            RemoteArtworkDetail(
                item = item,
                previewUri = state.selectedPreviewFile,
                backgroundStore = backgroundStore,
                isPreviewLoading = state.isPreviewLoading,
                isDownloading = state.downloadingId == item.id,
                downloadProgress = state.downloadProgress,
                isSaving = state.savingId == item.id,
                saveProgress = state.saveProgress,
                onBack = viewModel::closePreview,
                onUse = { viewModel.downloadAndUse(item, onUseDownloaded) },
                onSave = { requestSave(item) }
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
        )
    }

    if (state.showDownloadedManager) {
        RemoteBackgroundCacheSheet(
            assets = state.downloaded,
            activeUri = activeBackgroundUri,
            onDismiss = { viewModel.setDownloadedManagerVisible(false) },
            onDelete = { asset -> viewModel.deleteDownloaded(asset, activeBackgroundUri) },
            onClearUnused = { viewModel.clearUnusedDownloads(activeBackgroundUri) }
        )
    }
}

@Composable
private fun GalleryList(
    state: RemoteBackgroundGalleryUiState,
    listState: LazyListState,
    backgroundStore: ScheduleBackgroundStore,
    onItemClick: (RemoteBackgroundItem) -> Unit,
    onRetry: () -> Unit
) {
    when {
        state.isLoading && state.items.isEmpty() -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(30.dp), color = GalleryPrimaryColor)
            }
        }
        state.catalogUnavailable && state.items.isEmpty() -> {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("画廊暂时无法加载", color = GalleryTextColor, fontWeight = FontWeight.SemiBold)
                Text("请检查网络后重试", color = GallerySecondaryTextColor, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                Button(onClick = onRetry, modifier = Modifier.padding(top = 18.dp)) { Text("重新加载") }
            }
        }
        else -> LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            items(state.items, key = RemoteBackgroundItem::id) { item ->
                val cached = state.downloaded.any { it.id == item.id && it.sha256 == item.sha256 }
                RemoteGalleryCard(
                    item = item,
                    thumbnailUri = state.thumbnailFiles[item.id],
                    cached = cached,
                    backgroundStore = backgroundStore,
                    onClick = { onItemClick(item) }
                )
            }
        }
    }
}

@Composable
private fun RemoteGalleryCard(
    item: RemoteBackgroundItem,
    thumbnailUri: String?,
    cached: Boolean,
    backgroundStore: ScheduleBackgroundStore,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = GallerySurfaceColor,
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 1.dp
    ) {
        Column(modifier = Modifier.clickable(onClick = onClick)) {
            RemoteBackgroundImage(
                uri = thumbnailUri,
                backgroundStore = backgroundStore,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f),
                contentScale = ContentScale.Crop
            )
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.displayName,
                    color = GalleryTextColor,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (cached) {
                    Icon(
                        Icons.Outlined.Storage,
                        contentDescription = "已缓存原图",
                        tint = Color(0xFF15803D),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RemoteArtworkDetail(
    item: RemoteBackgroundItem,
    previewUri: String?,
    backgroundStore: ScheduleBackgroundStore,
    isPreviewLoading: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float,
    isSaving: Boolean,
    saveProgress: Float,
    onBack: () -> Unit,
    onUse: () -> Unit,
    onSave: () -> Unit
) {
    val bitmap = rememberRemoteBitmap(previewUri, backgroundStore)
    val scrollState = rememberScrollState()
    var showFullscreenViewer by remember(item.id) { mutableStateOf(false) }
    val darkTheme = isSystemInDarkTheme()
    val pageColor = if (darkTheme) Color(0xFF171819) else Color(0xFFF7F2E8)
    val imageBackdrop = if (darkTheme) Color(0xFF0F1011) else Color(0xFFE7DFD1)
    val primaryText = if (darkTheme) Color(0xFFF1EFEA) else Color(0xFF181817)
    val secondaryText = if (darkTheme) Color(0xFFAAA8A2) else Color(0xFF6D685F)
    val dividerColor = if (darkTheme) Color(0xFF343536) else Color(0xFFD9D2C5)
    val canLeave = canLeaveRemoteArtwork(isDownloading, isSaving)
    val buttonsEnabled = !isDownloading && !isSaving
    var actionBarHeightPx by remember(item.id) { mutableStateOf(0) }
    val actionBarHeight = with(LocalDensity.current) { actionBarHeightPx.toDp() }

    if (showFullscreenViewer) {
        RemoteArtworkFullscreenViewer(
            item = item,
            bitmap = bitmap,
            isPreviewLoading = isPreviewLoading,
            onBack = { showFullscreenViewer = false }
        )
        return
    }

    // 详情页整体滚动，避免长简介被固定图片压缩；底部操作栏保持可见。
    BackHandler(enabled = true) {
        if (canLeave) onBack()
    }
    Box(modifier = Modifier.fillMaxSize().background(pageColor)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(bottom = actionBarHeight + 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .height(64.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, enabled = canLeave, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "返回画廊",
                        tint = primaryText
                    )
                }
                Text(
                    text = "作品",
                    color = primaryText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(remoteArtworkImageHeight(item))
                    .background(imageBackdrop)
                    .clickable(enabled = bitmap != null) { showFullscreenViewer = true },
                contentAlignment = Alignment.Center
            ) {
                when {
                    bitmap != null -> Image(
                        bitmap = bitmap,
                        contentDescription = "查看${item.displayName}大图",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                    isPreviewLoading -> CircularProgressIndicator(color = secondaryText)
                    else -> Text("大图预览加载失败", color = secondaryText)
                }
                if (bitmap != null) {
                    Icon(
                        Icons.Outlined.Fullscreen,
                        contentDescription = "进入全屏缩放",
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(14.dp)
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.62f))
                            .padding(9.dp)
                    )
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val artwork = item.artwork
                Text(
                    text = artwork?.let { "《${it.titleZh}》" } ?: item.displayName,
                    color = primaryText,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Bold
                )
                artwork?.let { metadata ->
                    Text(
                        text = metadata.titleEn,
                        color = secondaryText,
                        fontSize = 16.sp,
                        fontStyle = FontStyle.Italic
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "${metadata.artistZh}（${metadata.artistEn}）",
                        color = primaryText,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${metadata.nationality} · ${metadata.year}",
                        color = secondaryText,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "${metadata.medium} · 现藏于${metadata.collection}",
                        color = secondaryText,
                        fontSize = 14.sp
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 16.dp),
                        color = dividerColor
                    )
                    Text("作品简介", color = secondaryText, fontSize = 13.sp)
                    Text(
                        text = metadata.description,
                        color = primaryText,
                        fontSize = 16.sp,
                        lineHeight = 25.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { actionBarHeightPx = it.height },
            color = pageColor.copy(alpha = 0.97f),
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                if (isDownloading || isSaving) {
                    val progress = if (isDownloading) downloadProgress else saveProgress
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = if (darkTheme) Color.White else Color(0xFF171717),
                        trackColor = secondaryText.copy(alpha = 0.25f)
                    )
                    Text(
                        if (isDownloading) "正在准备原图 ${(progress * 100).toInt()}%" else "正在保存到相册 ${(progress * 100).toInt()}%",
                        color = secondaryText,
                        fontSize = 12.sp
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = onSave,
                        enabled = buttonsEnabled,
                        modifier = Modifier.weight(1f).height(50.dp),
                        border = BorderStroke(
                            1.dp,
                            if (buttonsEnabled) secondaryText else secondaryText.copy(alpha = 0.38f)
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = primaryText,
                            disabledContentColor = secondaryText.copy(alpha = 0.62f)
                        )
                    ) { Text("保存到相册") }
                    Button(
                        onClick = onUse,
                        enabled = buttonsEnabled,
                        modifier = Modifier.weight(1f).height(50.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (darkTheme) Color(0xFFF1EFEA) else Color(0xFF191919),
                            contentColor = if (darkTheme) Color(0xFF171819) else Color.White,
                            disabledContainerColor = secondaryText.copy(alpha = 0.18f),
                            disabledContentColor = secondaryText.copy(alpha = 0.72f)
                        )
                    ) { Text("设为背景") }
                }
            }
        }
    }
}

@Composable
private fun RemoteArtworkFullscreenViewer(
    item: RemoteBackgroundItem,
    bitmap: ImageBitmap?,
    isPreviewLoading: Boolean,
    onBack: () -> Unit
) {
    var scale by remember(item.id) { mutableFloatStateOf(1f) }
    var offset by remember(item.id) { mutableStateOf(Offset.Zero) }
    var viewport by remember(item.id) { mutableStateOf(IntSize.Zero) }
    var showControls by remember(item.id) { mutableStateOf(true) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 5f)
        scale = newScale
        offset = constrainArtworkOffset(offset + panChange, newScale, viewport)
    }

    BackHandler(onBack = onBack)
    Box(modifier = Modifier.fillMaxSize().background(ArtworkBackgroundColor)) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap,
                contentDescription = item.displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged {
                        viewport = it
                        offset = constrainArtworkOffset(offset, scale, viewport)
                    }
                    .pointerInput(item.id) {
                        detectTapGestures(
                            onTap = { showControls = !showControls },
                            onDoubleTap = {
                                scale = if (scale > 1f) 1f else 2.5f
                                offset = Offset.Zero
                            }
                        )
                    }
                    .transformable(transformState)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
            )
            isPreviewLoading -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White
            )
            else -> Text("大图预览加载失败", color = Color.White, modifier = Modifier.align(Alignment.Center))
        }

        AnimatedVisibility(visible = showControls, modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(16.dp)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.52f))
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回作品详情", tint = Color.White)
                }
            }
        }
    }
}

private fun remoteArtworkImageHeight(item: RemoteBackgroundItem) = when {
    item.width >= item.height * 1.35f -> 280.dp
    item.width >= item.height -> 330.dp
    else -> 410.dp
}

@Composable
private fun RemoteBackgroundImage(
    uri: String?,
    backgroundStore: ScheduleBackgroundStore,
    modifier: Modifier,
    contentScale: ContentScale
) {
    val bitmap = rememberRemoteBitmap(uri, backgroundStore)
    Box(modifier = modifier.background(Color(0xFFE5E7EB)), contentAlignment = Alignment.Center) {
        bitmap?.let {
            Image(bitmap = it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = contentScale)
        } ?: CircularProgressIndicator(modifier = Modifier.size(24.dp), color = GalleryPrimaryColor, strokeWidth = 2.dp)
    }
}

@Composable
private fun rememberRemoteBitmap(uri: String?, backgroundStore: ScheduleBackgroundStore): ImageBitmap? {
    var bitmap by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = uri?.let { backgroundStore.loadPreview(it, maxDimension = 2400) }
    }
    return bitmap
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteBackgroundCacheSheet(
    assets: List<DownloadedRemoteBackground>,
    activeUri: String,
    onDismiss: () -> Unit,
    onDelete: (DownloadedRemoteBackground) -> Unit,
    onClearUnused: () -> Unit
) {
    var pendingDelete by remember { mutableStateOf<DownloadedRemoteBackground?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val totalBytes = assets.sumOf(DownloadedRemoteBackground::byteSize)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = GallerySurfaceColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("缓存管理", color = GalleryTextColor, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                    Text("共 ${assets.size} 项，占用 ${formatBytes(totalBytes)}", color = GallerySecondaryTextColor, fontSize = 13.sp)
                }
                if (assets.any { it.uri != activeUri }) {
                    TextButton(onClick = onClearUnused) { Text("清理全部") }
                }
            }
            if (assets.isEmpty()) {
                Text("暂无原图缓存", color = GallerySecondaryTextColor)
            }
            assets.forEachIndexed { index, asset ->
                val inUse = asset.uri == activeUri
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(asset.displayName, color = GalleryTextColor, fontWeight = FontWeight.Medium)
                        Text(
                            if (inUse) "使用中 · ${formatBytes(asset.byteSize)}" else formatBytes(asset.byteSize),
                            color = GallerySecondaryTextColor,
                            fontSize = 12.sp
                        )
                    }
                    IconButton(onClick = { pendingDelete = asset }, enabled = !inUse) {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            contentDescription = if (inUse) "使用中，无法删除" else "删除 ${asset.displayName}",
                            tint = if (inUse) GallerySecondaryTextColor.copy(alpha = 0.38f) else GalleryDangerColor
                        )
                    }
                }
                if (index < assets.lastIndex) HorizontalDivider(color = Color(0xFFE7E3DA))
            }
        }
    }
    pendingDelete?.let { asset ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = GallerySurfaceColor,
            titleContentColor = GalleryTextColor,
            textContentColor = GalleryTextColor,
            title = { Text("删除原图缓存？", fontWeight = FontWeight.SemiBold) },
            text = { Text("删除 ${asset.displayName} 后，如需再次设为背景必须重新下载。") },
            confirmButton = {
                TextButton(onClick = { onDelete(asset); pendingDelete = null }) {
                    Text("删除", color = GalleryDangerColor)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MiB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.1f KiB".format(bytes / 1024.0)
    else -> "$bytes B"
}

internal fun remoteBackgroundCacheBadgeText(downloadedCount: Int): String? =
    downloadedCount.takeIf { it > 0 }?.toString()

internal fun canLeaveRemoteArtwork(isDownloading: Boolean, isSaving: Boolean): Boolean =
    !isDownloading && !isSaving

internal fun constrainArtworkOffset(offset: Offset, scale: Float, viewport: IntSize): Offset {
    if (scale <= 1f) return Offset.Zero
    val maxX = viewport.width * (scale - 1f) / 2f
    val maxY = viewport.height * (scale - 1f) / 2f
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}
