package com.glut.schedule.ui.pages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glut.schedule.service.campus.CampusImageDocument
import com.glut.schedule.service.campus.CampusImageGateway
import com.glut.schedule.service.campus.CampusImageType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CampusImageUiState(
    val selectedType: CampusImageType = CampusImageType.CAMPUS_MAP,
    val tabs: Map<CampusImageType, CampusImageTabState> = emptyMap()
) {
    private val current: CampusImageTabState
        get() = tabs[selectedType] ?: CampusImageTabState()

    val document: CampusImageDocument?
        get() = current.document
    val isLoading: Boolean
        get() = current.isLoading
    val message: String
        get() = current.message
}

data class CampusImageTabState(
    val document: CampusImageDocument? = null,
    val isLoading: Boolean = false,
    val message: String = ""
)

class CampusImageViewModel(
    private val gateway: CampusImageGateway,
    initialType: CampusImageType = CampusImageType.CAMPUS_MAP
) : ViewModel() {
    private val _uiState = MutableStateFlow(CampusImageUiState(selectedType = initialType))
    val uiState: StateFlow<CampusImageUiState> = _uiState.asStateFlow()

    init {
        // 内置资源（如校园地图）无需网络加载，跳过 fetch
        if (initialType.isRemote) load(initialType, forceRefresh = false)
    }

    fun selectType(type: CampusImageType) {
        _uiState.update { it.copy(selectedType = type) }
        if (!type.isRemote) return
        val tab = _uiState.value.tabs[type]
        if (tab?.document == null && tab?.isLoading != true) {
            load(type, forceRefresh = false)
        }
    }

    fun refreshCurrent() {
        val type = _uiState.value.selectedType
        if (type.isRemote) load(type, forceRefresh = true)
    }

    fun refresh() = refreshCurrent()

    private fun load(type: CampusImageType, forceRefresh: Boolean) {
        if (_uiState.value.tabs[type]?.isLoading == true) return
        viewModelScope.launch {
            updateTab(type) { it.copy(isLoading = true, message = "") }
            runCatching { gateway.fetch(type, forceRefresh) }
                .onSuccess { document ->
                    updateTab(type) {
                        CampusImageTabState(
                            document = document,
                            message = if (forceRefresh && document.fromCache) "刷新失败，已显示上次缓存" else ""
                        )
                    }
                }
                .onFailure {
                    updateTab(type) { tab ->
                        tab.copy(isLoading = false, message = "校园信息暂时无法加载，请点击重试")
                    }
                }
        }
    }

    private fun updateTab(
        type: CampusImageType,
        transform: (CampusImageTabState) -> CampusImageTabState
    ) {
        _uiState.update { state ->
            val current = state.tabs[type] ?: CampusImageTabState()
            state.copy(tabs = state.tabs + (type to transform(current)))
        }
    }
}

class CampusImageViewModelFactory(
    private val gateway: CampusImageGateway,
    private val initialType: CampusImageType = CampusImageType.CAMPUS_MAP
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        CampusImageViewModel(gateway, initialType) as T
}
