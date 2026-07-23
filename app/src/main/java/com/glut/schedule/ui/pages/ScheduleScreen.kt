package com.glut.schedule.ui.pages

import android.content.Intent
import android.net.Uri

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.glut.schedule.data.model.CourseBlock
import com.glut.schedule.data.model.MAX_ACADEMIC_WEEK
import com.glut.schedule.data.model.MIN_ACADEMIC_WEEK
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.clampAcademicWeek
import com.glut.schedule.data.model.isActiveInWeek
import com.glut.schedule.data.model.scheduleWeekForNumber
import com.glut.schedule.ui.components.ScheduleGrid
import com.glut.schedule.ui.components.ScheduleHeader
import com.glut.schedule.ui.components.BackgroundSwitchResult
import com.glut.schedule.ui.components.ScheduleBackgroundStore
import com.glut.schedule.ui.components.StarryScheduleBackground
import com.glut.schedule.ui.components.shouldCommitCustomBackgroundUri
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun ScheduleScreen(
    viewModel: ScheduleViewModel,
    uiState: ScheduleUiState,
    backgroundStore: ScheduleBackgroundStore,
    customBackgroundBitmap: ImageBitmap?,
    onImportClick: () -> Unit,
    onExamClick: () -> Unit = {},
    onDrawerOpen: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var showAddActions by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val backgroundPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val uriText = uri.toString()
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        coroutineScope.launch {
            val metrics = context.resources.displayMetrics
            val loaded = backgroundStore.preload(
                uri = uriText,
                targetWidth = metrics.widthPixels,
                targetHeight = metrics.heightPixels
            )
            when (shouldCommitCustomBackgroundUri(uriText, preloadSucceeded = loaded)) {
                BackgroundSwitchResult.Commit -> {
                    viewModel.setCustomBackgroundUri(uriText)
                    showAddActions = false
                }
                BackgroundSwitchResult.KeepCurrent -> {
                    snackbarHostState.showSnackbar("背景加载失败，已保留当前背景")
                    showAddActions = false
                }
                BackgroundSwitchResult.Clear -> {
                    viewModel.clearCustomBackground()
                    showAddActions = false
                }
            }
        }
    }
    val blocksByWeek = remember(uiState.courses, uiState.maxAcademicWeek) {
        courseBlocksByWeek(uiState.courses, uiState.maxAcademicWeek)
    }
    val pagerState = key(uiState.viewedSemester?.id) {
        rememberPagerState(
            initialPage = pagerPageForWeekNumber(uiState.week.number, uiState.maxAcademicWeek),
            pageCount = { uiState.maxAcademicWeek }
        )
    }
    val latestWeekNumber by rememberUpdatedState(uiState.week.number)
    val latestMaxAcademicWeek by rememberUpdatedState(uiState.maxAcademicWeek)

    LaunchedEffect(uiState.week.number, uiState.maxAcademicWeek) {
        val targetPage = pagerPageForWeekNumber(uiState.week.number, uiState.maxAcademicWeek)
        if (pagerState.currentPage != targetPage && pagerState.settledPage != targetPage) {
            pagerState.animateScrollToPage(targetPage)
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val targetWeekNumber = weekNumberForPagerPage(page, latestMaxAcademicWeek)
                if (targetWeekNumber != latestWeekNumber) {
                    viewModel.setWeekNumber(targetWeekNumber)
                }
            }
    }

    Box(modifier = modifier.fillMaxSize()) {
        StarryScheduleBackground(
            customBackgroundUri = uiState.customBackgroundUri,
            customBackgroundBitmap = customBackgroundBitmap
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .navigationBarsPadding()
        ) {
            ScheduleHeader(
                week = uiState.week,
                today = uiState.today,
                currentWeekNumber = uiState.currentWeekNumber,
                onWeekTitleClick = viewModel::returnToCurrentWeek,
                onRefreshClick = {
                    showAddActions = false
                    viewModel.refreshSchedule()
                },
                semesters = uiState.semesters,
                isHistorical = uiState.isHistoricalSemester,
                onSemesterSelected = viewModel::selectSemester,
                onManageSemesters = onImportClick,
                onReturnToCurrentClick = viewModel::returnToCurrentSemester,
                onDrawerOpen = onDrawerOpen,
                isRefreshing = uiState.isRefreshing
            )
            HorizontalPager(
                state = pagerState,
                key = { page -> page },
                flingBehavior = PagerDefaults.flingBehavior(
                    state = pagerState,
                    pagerSnapDistance = PagerSnapDistance.atMost(1)
                ),
                beyondViewportPageCount = 1,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { page ->
                val pageWeekNumber = weekNumberForPagerPage(page, uiState.maxAcademicWeek)
                val pageWeek = scheduleWeekForNumber(
                    pageWeekNumber,
                    uiState.semesterStartMonday,
                    uiState.maxAcademicWeek
                )
                val pageBlocks = blocksByWeek[pageWeekNumber].orEmpty()
                ScheduleGrid(
                    week = pageWeek,
                    today = uiState.today,
                    periods = uiState.classPeriods,
                    blocks = pageBlocks,
                    showWeekend = uiState.showWeekend,
                    showNoon = uiState.showNoon,
                    showCalendarDates = uiState.hasAuthoritativeCalendar,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        if (showAddActions) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showAddActions = false }
            )
            ScheduleAddActionsPanel(
                hasCustomBackground = uiState.customBackgroundUri.isNotBlank(),
                onPickBackground = { backgroundPicker.launch(arrayOf("image/*")) },
                onClearBackground = {
                    viewModel.clearCustomBackground()
                    showAddActions = false
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 52.dp, end = 52.dp)
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 18.dp)
                .navigationBarsPadding()
        )
    }
    LaunchedEffect(uiState.message) {
        val message = uiState.message
        if (message.isNotBlank()) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
        }
    }
}

@Composable
private fun ScheduleAddActionsPanel(
    hasCustomBackground: Boolean,
    onPickBackground: () -> Unit,
    onClearBackground: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.76f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TextButton(onClick = onPickBackground) {
                Text(text = "自定义背景", color = Color.White, fontSize = 13.sp)
            }
            TextButton(onClick = onClearBackground, enabled = hasCustomBackground) {
                Text(text = "恢复默认背景", color = Color.White, fontSize = 13.sp)
            }
        }
    }
}

fun weekNumberForPagerPage(page: Int, maxWeek: Int = MAX_ACADEMIC_WEEK): Int {
    return clampAcademicWeek(page + 1, maxWeek)
}

fun pagerPageForWeekNumber(weekNumber: Int, maxWeek: Int = MAX_ACADEMIC_WEEK): Int {
    return clampAcademicWeek(weekNumber, maxWeek) - MIN_ACADEMIC_WEEK
}

fun courseBlocksByWeek(
    courses: List<ScheduleCourse>,
    maxWeek: Int = MAX_ACADEMIC_WEEK
): Map<Int, List<CourseBlock>> {
    val clampedMaxWeek = clampAcademicWeek(maxWeek)
    return (MIN_ACADEMIC_WEEK..clampedMaxWeek).associateWith { weekNumber ->
        courses.flatMap { course ->
            course.occurrences
                .filter { occurrence -> occurrence.isActiveInWeek(weekNumber) }
                .map { occurrence -> CourseBlock(course, occurrence) }
        }
    }
}

