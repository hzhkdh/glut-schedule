package com.glut.schedule.ui.pages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glut.schedule.service.campus.SchoolOverviewRepository
import com.glut.schedule.service.campus.SchoolOverviewTopic
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class SchoolOverviewLoadState { LOADING, READY, FAILED }

internal class SchoolOverviewViewModel(private val repository: SchoolOverviewRepository) : ViewModel() {
    private val _loadStates = MutableStateFlow<Map<SchoolOverviewTopic, SchoolOverviewLoadState>>(emptyMap())
    val loadStates = _loadStates.asStateFlow()
    val documents = SchoolOverviewTopic.entries.associateWith(repository::observe)

    fun open(topic: SchoolOverviewTopic) {
        // 只在首次进入主题时检查有效期，切换标签不会重复请求同一份资料。
        if (topic !in _loadStates.value) load(topic, force = false)
    }

    fun refresh(topic: SchoolOverviewTopic) = load(topic, force = true)

    suspend fun loadImage(url: String): ByteArray? = repository.loadImage(url)

    private fun load(topic: SchoolOverviewTopic, force: Boolean) {
        if (_loadStates.value[topic] == SchoolOverviewLoadState.LOADING) return
        _loadStates.update { it + (topic to SchoolOverviewLoadState.LOADING) }
        viewModelScope.launch {
            val result = try {
                // 缓存先进入资料流，联网失败时原有内容仍留在屏幕上。
                repository.loadCached(topic)
                repository.refresh(topic, force)
                SchoolOverviewLoadState.READY
            } catch (_: IOException) {
                SchoolOverviewLoadState.FAILED
            }
            _loadStates.update { it + (topic to result) }
        }
    }
}

internal class SchoolOverviewViewModelFactory(private val repository: SchoolOverviewRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = SchoolOverviewViewModel(repository) as T
}
