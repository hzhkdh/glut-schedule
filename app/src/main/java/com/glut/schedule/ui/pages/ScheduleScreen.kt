package com.glut.schedule.ui.pages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glut.schedule.ui.components.ScheduleGrid
import com.glut.schedule.ui.components.ScheduleHeader
import com.glut.schedule.ui.components.StarryScheduleBackground
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
fun ScheduleScreen(
    viewModel: ScheduleViewModel,
    onImportClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var dragAmount by remember { mutableFloatStateOf(0f) }
    var showSettings by remember { mutableStateOf(false) }
    val elasticOffsetPx = (dragAmount * 0.18f).coerceIn(-42f, 42f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        when {
                            dragAmount > 120f -> viewModel.previousWeek()
                            dragAmount < -120f -> viewModel.nextWeek()
                        }
                        dragAmount = 0f
                    },
                    onHorizontalDrag = { _, dragDelta ->
                        dragAmount = (dragAmount + dragDelta).coerceIn(-320f, 320f)
                    }
                )
            }
    ) {
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
            ScheduleGrid(
                week = uiState.week,
                today = uiState.today,
                periods = uiState.classPeriods,
                blocks = uiState.courseBlocks,
                showWeekend = uiState.showWeekend,
                modifier = Modifier
                    .weight(1f)
                    .offset { IntOffset(elasticOffsetPx.roundToInt(), 0) }
            )
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
