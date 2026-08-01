package com.glut.schedule

import com.glut.schedule.data.model.ClassPeriod
import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.CourseTimeDimension
import com.glut.schedule.data.model.CourseTimeSemesterSource
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.ui.pages.CourseTimeStatsViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseTimeStatsViewModelTest {
    @Test
    fun defaultsToCurrentDownloadedSemesterAndSupportsAllScope() = runTest {
        val current = source("current", "2026·春", isCurrent = true, title = "当前课程")
        val history = source("history", "2025·秋", isCurrent = false, title = "历史课程")
        val notDownloaded = source(
            "remote",
            "2025·春",
            isCurrent = false,
            title = "未下载课程",
            isDownloaded = false
        )
        val viewModel = CourseTimeStatsViewModel(
            sourceFlow = MutableStateFlow(listOf(current, history, notDownloaded))
        )

        val initial = viewModel.uiState.first { it.semesterOptions.isNotEmpty() }

        assertEquals("current", initial.selectedScopeId)
        assertEquals(listOf("current", "history"), initial.semesterOptions.map { it.id })
        assertEquals(45, initial.stats.totalMinutes)
        assertFalse(initial.isAllSemesters)

        viewModel.selectScope(CourseTimeStatsViewModel.ALL_SCOPE_ID)
        val all = viewModel.uiState.first { it.isAllSemesters }

        assertEquals(90, all.stats.totalMinutes)
        assertEquals(2, all.stats.coverage.eligibleSemesters)
        assertEquals(2, all.stats.coverage.downloadedSemesters)
    }

    @Test
    fun switchesDimensionWithoutChangingSelectedScope() = runTest {
        val viewModel = CourseTimeStatsViewModel(
            sourceFlow = MutableStateFlow(
                listOf(source("current", "2026·春", true, "课程", teacher = "张三、李四"))
            )
        )
        viewModel.uiState.first { it.semesterOptions.isNotEmpty() }

        viewModel.selectDimension(CourseTimeDimension.TEACHER)
        val teacherState = viewModel.uiState.first {
            it.dimension == CourseTimeDimension.TEACHER
        }

        assertEquals("current", teacherState.selectedScopeId)
        assertEquals(90, teacherState.stats.totalMinutes)
        assertEquals(listOf("张三", "李四"), teacherState.stats.items.map { it.label })
        assertTrue(teacherState.stats.items.all { it.share == 0.5 })
    }

    private fun source(
        id: String,
        label: String,
        isCurrent: Boolean,
        title: String,
        teacher: String = "教师",
        isDownloaded: Boolean = true
    ) = CourseTimeSemesterSource(
        semesterId = id,
        semesterLabel = label,
        isCurrent = isCurrent,
        isDownloaded = isDownloaded,
        portalMaxWeek = 20,
        courses = listOf(
            ScheduleCourse(
                id = "$id-course",
                title = title,
                room = "A101",
                teacher = teacher,
                colorHex = "#3B82F6",
                occurrences = listOf(
                    CourseOccurrence(
                        id = "$id-occurrence",
                        courseId = "$id-course",
                        dayOfWeek = 1,
                        startSection = 1,
                        endSection = 1,
                        weekText = "1周",
                        note = "A101"
                    )
                )
            )
        ),
        classPeriods = listOf(ClassPeriod(1, "08:00", "08:45"))
    )
}
