package com.glut.schedule.ui.pages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glut.schedule.data.model.CourseTimeDimension
import com.glut.schedule.data.model.CourseTimeSemesterSource
import com.glut.schedule.data.model.CourseTimeStatsCalculator
import com.glut.schedule.data.model.CourseTimeStatsCoverage
import com.glut.schedule.data.model.CourseTimeStatsResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class CourseTimeSemesterOption(
    val id: String,
    val label: String,
    val isCurrent: Boolean,
    val statisticsReady: Boolean
)

data class CourseTimeStatsUiState(
    val semesterOptions: List<CourseTimeSemesterOption> = emptyList(),
    val selectedScopeId: String = "",
    val isAllSemesters: Boolean = false,
    val dimension: CourseTimeDimension = CourseTimeDimension.COURSE,
    val stats: CourseTimeStatsResult = CourseTimeStatsResult(
        totalMinutes = 0,
        items = emptyList(),
        distribution = emptyList(),
        coverage = CourseTimeStatsCoverage(0, 0, emptyList())
    )
)

class CourseTimeStatsViewModel(
    sourceFlow: Flow<List<CourseTimeSemesterSource>>
) : ViewModel() {
    private val selectedScope = MutableStateFlow<String?>(null)
    private val selectedDimension = MutableStateFlow(CourseTimeDimension.COURSE)

    val uiState: StateFlow<CourseTimeStatsUiState> = combine(
        sourceFlow,
        selectedScope,
        selectedDimension
    ) { allSources, requestedScope, dimension ->
        val downloaded = allSources.filter { it.isDownloaded }
        val options = downloaded.map { source ->
            val readiness = CourseTimeStatsCalculator.calculate(listOf(source), dimension)
            CourseTimeSemesterOption(
                id = source.semesterId,
                label = source.semesterLabel,
                isCurrent = source.isCurrent,
                statisticsReady = readiness.coverage.eligibleSemesters == 1
            )
        }
        val selectedId = when {
            requestedScope == ALL_SCOPE_ID -> ALL_SCOPE_ID
            requestedScope != null && options.any { it.id == requestedScope } -> requestedScope
            else -> options.firstOrNull { it.isCurrent }?.id ?: options.firstOrNull()?.id.orEmpty()
        }
        val selectedSources = if (selectedId == ALL_SCOPE_ID) {
            downloaded
        } else {
            downloaded.filter { it.semesterId == selectedId }
        }
        CourseTimeStatsUiState(
            semesterOptions = options,
            selectedScopeId = selectedId,
            isAllSemesters = selectedId == ALL_SCOPE_ID,
            dimension = dimension,
            stats = CourseTimeStatsCalculator.calculate(selectedSources, dimension)
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CourseTimeStatsUiState()
    )

    fun selectScope(scopeId: String) {
        selectedScope.value = scopeId
    }

    fun selectDimension(dimension: CourseTimeDimension) {
        selectedDimension.value = dimension
    }

    companion object {
        const val ALL_SCOPE_ID = "__all_semesters__"
    }
}

class CourseTimeStatsViewModelFactory(
    private val sourceFlow: Flow<List<CourseTimeSemesterSource>>
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return CourseTimeStatsViewModel(sourceFlow) as T
    }
}
