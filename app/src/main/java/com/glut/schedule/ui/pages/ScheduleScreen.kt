package com.glut.schedule.ui.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults

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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.glut.schedule.data.model.CourseBlock
import com.glut.schedule.data.model.MAX_ACADEMIC_WEEK
import com.glut.schedule.data.model.MIN_ACADEMIC_WEEK
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.CourseRemark
import com.glut.schedule.data.model.clampAcademicWeek
import com.glut.schedule.data.model.isActiveInWeek
import com.glut.schedule.data.model.scheduleWeekForNumber
import com.glut.schedule.data.model.limitCourseRemarkInput
import com.glut.schedule.ui.components.ScheduleGrid
import com.glut.schedule.ui.components.ScheduleHeader
import com.glut.schedule.ui.components.ScheduleBackgroundImage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun ScheduleScreen(
    viewModel: ScheduleViewModel,
    uiState: ScheduleUiState,
    customBackgroundBitmap: ImageBitmap?,
    onPickBackground: () -> Unit,
    onClearBackground: () -> Unit,
    onImportClick: () -> Unit,
    onExamClick: () -> Unit = {},
    onDrawerOpen: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var showAddActions by remember { mutableStateOf(false) }
    var remarkOverlay by remember { mutableStateOf<CourseRemarkOverlay?>(null) }
    val coroutineScope = rememberCoroutineScope()
    if (!uiState.isInitialized) {
        // 背景设置尚未恢复时只显示中性占位，避免把临时空值误绘制成默认《花》。
        // DataStore 与 Room 都完成首轮恢复后再创建 Pager，避免错误背景和默认周次短暂闪现。
        Box(modifier = modifier.fillMaxSize())
        return
    }
    val blocksByWeek = remember(uiState.courses, uiState.courseRemarks, uiState.maxAcademicWeek) {
        courseBlocksByWeek(
            courses = uiState.courses,
            maxWeek = uiState.maxAcademicWeek,
            remarks = uiState.courseRemarks
        )
    }
    if (com.glut.schedule.ui.components.shouldUseCustomBackground(uiState.customBackgroundUri) &&
        customBackgroundBitmap == null
    ) {
        // Activity 会继续保留系统启动窗口；这里绝不能绘制另一张内置画作，否则冷启动会闪切背景。
        Box(modifier = modifier.fillMaxSize())
        return
    }
    val pagerState = key(uiState.viewedSemester?.id) {
        rememberPagerState(
            initialPage = pagerPageForWeekNumber(uiState.week.number, uiState.maxAcademicWeek),
            pageCount = { uiState.maxAcademicWeek }
        )
    }
    val latestWeekNumber by rememberUpdatedState(uiState.week.number)
    val latestMaxAcademicWeek by rememberUpdatedState(uiState.maxAcademicWeek)
    var animatedPagerScrollInProgress by remember(pagerState) { mutableStateOf(false) }

    LaunchedEffect(uiState.week.number, uiState.maxAcademicWeek) {
        val targetPage = pagerPageForWeekNumber(uiState.week.number, uiState.maxAcademicWeek)
        if (pagerState.currentPage != targetPage && pagerState.settledPage != targetPage) {
            // 状态驱动的跳周必须瞬时对齐；动画经过的中间页会被 settledPage 监听器误当作用户选择。
            pagerState.scrollToPage(targetPage)
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val targetWeekNumber = weekNumberForPagerPage(page, latestMaxAcademicWeek)
                if (!animatedPagerScrollInProgress && targetWeekNumber != latestWeekNumber) {
                    viewModel.setWeekNumber(targetWeekNumber)
                }
            }
    }

    Box(modifier = modifier.fillMaxSize()) {
        ScheduleBackgroundImage(
            customBackgroundUri = uiState.customBackgroundUri,
            customBackgroundBitmap = customBackgroundBitmap,
            dimAmount = uiState.backgroundDimAmount
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
                onWeekTitleClick = {
                    val currentWeekPage = pagerPageForWeekNumber(
                        uiState.currentWeekNumber,
                        uiState.maxAcademicWeek
                    )
                    coroutineScope.launch {
                        if (
                            pagerState.currentPage == currentWeekPage &&
                            pagerState.settledPage == currentWeekPage
                        ) {
                            viewModel.returnToCurrentWeek()
                            return@launch
                        }

                        // 用户主动点击标题时保留快速滚回动画；动画中间页不得反写为新的选中周。
                        animatedPagerScrollInProgress = true
                        try {
                            pagerState.animateScrollToPage(currentWeekPage)
                            viewModel.returnToCurrentWeek()
                        } finally {
                            animatedPagerScrollInProgress = false
                        }
                    }
                },
                onRefreshClick = {
                    showAddActions = false
                    viewModel.refreshSchedule()
                },
                semesters = uiState.semesters,
                isHistorical = uiState.isHistoricalSemester,
                viewedSemesterId = uiState.viewedSemester?.id,
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
                    onCourseRemarkClick = { block ->
                        remarkOverlay = CourseRemarkOverlay.View(
                            CourseRemarkTarget(block, pageWeekNumber)
                        )
                    },
                    onCourseLongClick = { block ->
                        remarkOverlay = CourseRemarkOverlay.Edit(
                            target = CourseRemarkTarget(block, pageWeekNumber),
                            returnToView = false
                        )
                    },
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
                hasCustomBackground = com.glut.schedule.ui.components.shouldUseCustomBackground(uiState.customBackgroundUri),
                onPickBackground = {
                    onPickBackground()
                    showAddActions = false
                },
                onClearBackground = {
                    onClearBackground()
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
    when (val overlay = remarkOverlay) {
        is CourseRemarkOverlay.View -> CourseRemarkViewDialog(
            target = overlay.target,
            onDismiss = { remarkOverlay = null },
            onEdit = {
                remarkOverlay = CourseRemarkOverlay.Edit(
                    target = overlay.target,
                    returnToView = true
                )
            },
            onDelete = { remarkOverlay = CourseRemarkOverlay.ConfirmDelete(overlay.target) }
        )
        is CourseRemarkOverlay.Edit -> CourseRemarkEditDialog(
            target = overlay.target,
            onDismiss = {
                remarkOverlay = if (overlay.returnToView) {
                    CourseRemarkOverlay.View(overlay.target)
                } else {
                    null
                }
            },
            onSave = { text ->
                viewModel.saveCourseRemark(overlay.target.block, overlay.target.weekNumber, text)
                remarkOverlay = null
            }
        )
        is CourseRemarkOverlay.ConfirmDelete -> CourseRemarkDeleteConfirmDialog(
            onDismiss = { remarkOverlay = CourseRemarkOverlay.View(overlay.target) },
            onConfirm = {
                viewModel.deleteCourseRemark(overlay.target.block, overlay.target.weekNumber)
                remarkOverlay = null
            }
        )
        null -> Unit
    }
}

private data class CourseRemarkTarget(val block: CourseBlock, val weekNumber: Int)

private sealed interface CourseRemarkOverlay {
    val target: CourseRemarkTarget

    data class View(override val target: CourseRemarkTarget) : CourseRemarkOverlay
    data class Edit(
        override val target: CourseRemarkTarget,
        val returnToView: Boolean
    ) : CourseRemarkOverlay
    data class ConfirmDelete(override val target: CourseRemarkTarget) : CourseRemarkOverlay
}

@Composable
private fun CourseRemarkViewDialog(
    target: CourseRemarkTarget,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFFFFFBF3),
        textContentColor = Color(0xFF4A4338),
        tonalElevation = 0.dp,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        text = {
            Surface(
                color = Color(0xFFF3EBDD),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = target.block.remark.orEmpty(),
                    color = Color(0xFF2D2923),
                    fontSize = 17.sp,
                    lineHeight = 26.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp)
                )
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onDelete) {
                    Text("删除", color = Color(0xFFB42318))
                }
                TextButton(onClick = onEdit) {
                    Text("编辑", color = Color(0xFF171717))
                }
            }
        }
    )
}

@Composable
private fun CourseRemarkEditDialog(
    target: CourseRemarkTarget,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember(target) { mutableStateOf(target.block.remark.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFFFFFBF3),
        titleContentColor = Color(0xFF171717),
        textContentColor = Color(0xFF5F5A52),
        tonalElevation = 0.dp,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        title = {
            Text(
                text = "第${target.weekNumber}周 · ${target.block.course.title}",
                fontSize = 20.sp,
                lineHeight = 28.sp,
                maxLines = 2
            )
        },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { input -> text = input.limitCourseRemarkInput() },
                placeholder = { Text("例如：带实验报告") },
                supportingText = {
                    Text(
                        text = "${text.codePointCount(0, text.length)}/80",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End
                    )
                },
                minLines = 2,
                maxLines = 3,
                singleLine = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "课程备注" },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color(0xFF171717),
                    unfocusedTextColor = Color(0xFF171717),
                    cursorColor = Color(0xFF171717),
                    focusedBorderColor = Color(0xFF171717),
                    unfocusedBorderColor = Color(0xFFC8C1B3),
                    focusedPlaceholderColor = Color(0xFF8A8378),
                    unfocusedPlaceholderColor = Color(0xFF8A8378),
                    focusedSupportingTextColor = Color(0xFF777066),
                    unfocusedSupportingTextColor = Color(0xFF777066)
                )
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) {
                Text("保存", color = Color(0xFF171717))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = Color(0xFF6F6A60))
            }
        }
    )
}

@Composable
private fun CourseRemarkDeleteConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFFFFFBF3),
        titleContentColor = Color(0xFF171717),
        textContentColor = Color(0xFF6F6A60),
        tonalElevation = 0.dp,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        title = { Text("删除这条备注？") },
        text = { Text("删除后无法恢复。") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = Color(0xFFB42318))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = Color(0xFF6F6A60))
            }
        }
    )
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
    maxWeek: Int = MAX_ACADEMIC_WEEK,
    remarks: List<CourseRemark> = emptyList()
): Map<Int, List<CourseBlock>> {
    val clampedMaxWeek = clampAcademicWeek(maxWeek)
    return (MIN_ACADEMIC_WEEK..clampedMaxWeek).associateWith { weekNumber ->
        courses.flatMap { course ->
            course.occurrences
                .filter { occurrence -> occurrence.isActiveInWeek(weekNumber) }
                .map { occurrence ->
                    CourseBlock(
                        course = course,
                        occurrence = occurrence,
                        remark = remarks.firstOrNull {
                            it.courseId == course.id && it.occurrenceId == occurrence.id &&
                                it.weekNumber == weekNumber
                        }?.text
                    )
                }
        }
    }
}

