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
    val document: CampusImageDocument? = null,
    val isLoading: Boolean = false,
    val message: String = ""
)

class CampusImageViewModel(
    private val gateway: CampusImageGateway,
    private val type: CampusImageType
) : ViewModel() {
    private val _uiState = MutableStateFlow(CampusImageUiState())
    val uiState: StateFlow<CampusImageUiState> = _uiState.asStateFlow()

    init {
        load(forceRefresh = false)
    }

    fun refresh() = load(forceRefresh = true)

    private fun load(forceRefresh: Boolean) {
        if (_uiState.value.isLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, message = "") }
            runCatching { gateway.fetch(type, forceRefresh) }
                .onSuccess { document ->
                    _uiState.value = CampusImageUiState(
                        document = document,
                        message = if (document.fromCache) "网络不可用，已显示上次缓存" else ""
                    )
                }
                .onFailure {
                    _uiState.update {
                        it.copy(isLoading = false, message = "校园信息暂时无法加载，请点击重试")
                    }
                }
        }
    }
}

class CampusImageViewModelFactory(
    private val gateway: CampusImageGateway,
    private val type: CampusImageType
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        CampusImageViewModel(gateway, type) as T
}
