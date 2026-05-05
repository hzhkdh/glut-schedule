package com.glut.schedule.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.glut.schedule.ui.components.StarryScheduleBackground
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

@Composable
fun ScheduleScreen(
    viewModel: ScheduleViewModel,
    onImportClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }
    val blocksByWeek = remember(uiState.courses) { courseBlocksByWeek(uiState.courses) }
    val pagerState = rememberPagerState(
        initialPage = pagerPageForWeekNumber(uiState.week.number),
        pageCount = { MAX_ACADEMIC_WEEK }
    )
    val latestWeekNumber by rememberUpdatedState(uiState.week.number)

    LaunchedEffect(uiState.week.number) {
        val targetPage = pagerPageForWeekNumber(uiState.week.number)
        if (pagerState.currentPage != targetPage && pagerState.settledPage != targetPage) {
            pagerState.animateScrollToPage(targetPage)
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val targetWeekNumber = weekNumberForPagerPage(page)
                if (targetWeekNumber != latestWeekNumber) {
                    viewModel.setWeekNumber(targetWeekNumber)
                }
            }
    }

    Box(modifier = modifier.fillMaxSize()) {
        StarryScheduleBackground()
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
                onImportClick = onImportClick,
                onAddClick = {
                    scope.launch { snackbarHostState.showSnackbar("后续阶段接入") }
                },
                onTodayClick = viewModel::returnToCurrentWeek,
                onMoreClick = {
                    showSettings = !showSettings
                }
            )
            HorizontalPager(
                state = pagerState,
                key = { page -> page },
                flingBehavior = PagerDefaults.flingBehavior(
                    state = pagerState,
                    pagerSnapDistance = PagerSnapDistance.atMost(1)
                ),
                beyondViewportPageCount = 0,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { page ->
                val pageWeekNumber = weekNumberForPagerPage(page)
                val pageWeek = scheduleWeekForNumber(pageWeekNumber, uiState.semesterStartMonday)
                ScheduleGrid(
                    week = pageWeek,
                    today = uiState.today,
                    periods = uiState.classPeriods,
                    blocks = blocksByWeek[pageWeekNumber].orEmpty(),
                    showWeekend = uiState.showWeekend,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        if (showSettings) {
            ScheduleSettingsPanel(
                showWeekend = uiState.showWeekend,
                semesterStartMonday = uiState.semesterStartMonday,
                onShowWeekendChange = viewModel::setShowWeekend,
                onSemesterStartBack = { viewModel.moveSemesterStartByWeeks(-1) },
                onSemesterStartForward = { viewModel.moveSemesterStartByWeeks(1) },
                onSemesterStartThisWeek = viewModel::setSemesterStartToThisWeekMonday,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 52.dp, end = 12.dp)
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
}

fun weekNumberForPagerPage(page: Int): Int {
    return clampAcademicWeek(page + 1)
}

fun pagerPageForWeekNumber(weekNumber: Int): Int {
    return clampAcademicWeek(weekNumber) - MIN_ACADEMIC_WEEK
}

fun courseBlocksByWeek(courses: List<ScheduleCourse>): Map<Int, List<CourseBlock>> {
    return (MIN_ACADEMIC_WEEK..MAX_ACADEMIC_WEEK).associateWith { weekNumber ->
        courses.flatMap { course ->
            course.occurrences
                .filter { occurrence -> occurrence.isActiveInWeek(weekNumber) }
                .map { occurrence -> CourseBlock(course, occurrence) }
        }
    }
}

@Composable
private fun ScheduleSettingsPanel(
    showWeekend: Boolean,
    semesterStartMonday: java.time.LocalDate,
    onShowWeekendChange: (Boolean) -> Unit,
    onSemesterStartBack: () -> Unit,
    onSemesterStartForward: () -> Unit,
    onSemesterStartThisWeek: () -> Unit,
    modifier: Modifier = Modifier
) {
    val formatter = DateTimeFormatter.ofPattern("yyyy/M/d")
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.72f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "显示周末", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(checked = showWeekend, onCheckedChange = onShowWeekendChange)
            }
            Text(
                text = "学期起点 ${semesterStartMonday.format(formatter)}",
                color = Color.White,
                fontSize = 13.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onSemesterStartBack) {
                    Text(text = "前移1周", color = Color.White, fontSize = 12.sp)
                }
                TextButton(onClick = onSemesterStartThisWeek) {
                    Text(text = "本周周一", color = Color.White, fontSize = 12.sp)
                }
                TextButton(onClick = onSemesterStartForward) {
                    Text(text = "后移1周", color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}
