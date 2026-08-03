package com.glut.schedule.ui.pages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glut.schedule.service.background.DownloadedRemoteBackground
import com.glut.schedule.service.background.RemoteBackgroundDeleteResult
import com.glut.schedule.service.background.RemoteBackgroundGateway
import com.glut.schedule.service.background.RemoteBackgroundItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RemoteBackgroundGalleryUiState(
    val items: List<RemoteBackgroundItem> = emptyList(),
    val downloaded: List<DownloadedRemoteBackground> = emptyList(),
    val thumbnailFiles: Map<String, String> = emptyMap(),
    val selectedItem: RemoteBackgroundItem? = null,
    val selectedPreviewFile: String? = null,
    val isLoading: Boolean = false,
    val isPreviewLoading: Boolean = false,
    val downloadingId: String? = null,
    val downloadProgress: Float = 0f,
    val showDownloadedManager: Boolean = false,
    val message: String = ""
)

class RemoteBackgroundGalleryViewModel(
    private val gateway: RemoteBackgroundGateway
) : ViewModel() {
    private val _uiState = MutableStateFlow(RemoteBackgroundGalleryUiState())
    val uiState: StateFlow<RemoteBackgroundGalleryUiState> = _uiState.asStateFlow()
    private var previewRequestId = 0L

    init {
        // 每次进入画廊都尝试获取最新 JSON；仓库层在断网时自动回退到上次成功清单。
        load(forceRefresh = true)
    }

    fun refresh() = load(forceRefresh = true)

    fun openPreview(item: RemoteBackgroundItem) {
        val requestId = ++previewRequestId
        _uiState.update { it.copy(selectedItem = item, selectedPreviewFile = null, isPreviewLoading = true) }
        viewModelScope.launch {
            runCatching { gateway.loadPreview(item, large = true) }
                .onSuccess { file ->
                    // 只允许最后一次点击的结果更新弹窗，防止慢请求把新选择的预览覆盖掉。
                    if (requestId == previewRequestId) {
                        _uiState.update { it.copy(selectedPreviewFile = file.toURI().toString(), isPreviewLoading = false) }
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    if (requestId == previewRequestId) {
                        _uiState.update { it.copy(isPreviewLoading = false, message = "大图预览加载失败，请重试") }
                    }
                }
        }
    }

    fun closePreview() {
        if (_uiState.value.downloadingId != null) return
        previewRequestId++
        _uiState.update { it.copy(selectedItem = null, selectedPreviewFile = null, isPreviewLoading = false) }
    }

    fun downloadAndUse(item: RemoteBackgroundItem, onReady: (DownloadedRemoteBackground) -> Unit) {
        if (_uiState.value.downloadingId != null) return
        _uiState.update { it.copy(downloadingId = item.id, downloadProgress = 0f, message = "") }
        viewModelScope.launch {
            try {
                val asset = gateway.downloadOriginal(item) { progress ->
                    _uiState.update { it.copy(downloadProgress = progress) }
                }
                _uiState.update {
                    it.copy(
                        downloaded = gateway.downloadedAssets(),
                        downloadingId = null,
                        downloadProgress = 1f,
                        selectedItem = null,
                        selectedPreviewFile = null
                    )
                }
                onReady(asset)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _uiState.update { it.copy(downloadingId = null, message = "原图下载或校验失败，请重试") }
            }
        }
    }

    fun setDownloadedManagerVisible(visible: Boolean) {
        _uiState.update { it.copy(showDownloadedManager = visible) }
    }

    fun deleteDownloaded(asset: DownloadedRemoteBackground, activeUri: String) {
        val result = gateway.deleteDownloaded(asset.id, asset.sha256, activeUri)
        _uiState.update { state ->
            when (result) {
                RemoteBackgroundDeleteResult.Deleted -> state.copy(
                    downloaded = gateway.downloadedAssets(),
                    message = "已删除 ${asset.displayName}"
                )
                RemoteBackgroundDeleteResult.InUse -> state.copy(message = "该资源正在使用中，请先切换背景")
                RemoteBackgroundDeleteResult.NotFound -> state.copy(message = "资源不存在或删除失败")
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = "") }
    }

    private fun load(forceRefresh: Boolean) {
        if (_uiState.value.isLoading) return
        // 已下载资源来自持久目录，不依赖在线清单；断网时也必须可以进入管理器删除。
        val localDownloads = runCatching { gateway.downloadedAssets() }.getOrDefault(emptyList())
        _uiState.update { it.copy(downloaded = localDownloads, isLoading = true, message = "") }
        viewModelScope.launch {
            try {
                val catalog = gateway.loadCatalog(forceRefresh)
                _uiState.update {
                    it.copy(
                        items = catalog.items,
                        downloaded = gateway.downloadedAssets(),
                        isLoading = false
                    )
                }
                catalog.items.forEach { item ->
                    runCatching { gateway.loadPreview(item, large = false) }
                        .onSuccess { file ->
                            _uiState.update { state ->
                                state.copy(thumbnailFiles = state.thumbnailFiles + (item.id to file.toURI().toString()))
                            }
                        }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _uiState.update { it.copy(isLoading = false, message = "在线背景暂时无法加载，请点击刷新") }
            }
        }
    }
}

class RemoteBackgroundGalleryViewModelFactory(
    private val gateway: RemoteBackgroundGateway
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        RemoteBackgroundGalleryViewModel(gateway) as T
}
