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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
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
import com.glut.schedule.data.model.DEFAULT_SEMESTER_START_MONDAY
import com.glut.schedule.data.model.MAX_ACADEMIC_WEEK
import com.glut.schedule.data.model.MIN_ACADEMIC_WEEK
import com.glut.schedule.data.model.ManualDayCopyRule
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.clampAcademicWeek
import com.glut.schedule.data.model.isActiveInWeek
import com.glut.schedule.data.model.manualCopyBlocksForWeek
import com.glut.schedule.data.model.scheduleWeekForNumber
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glut.schedule.data.model.CourseColorMapper
import com.glut.schedule.data.model.ScheduleRefreshDiff
import com.glut.schedule.data.model.ScheduleRefreshDiffItem
import com.glut.schedule.ui.components.CourseCardManageSheet
import com.glut.schedule.ui.components.ScheduleGrid
import com.glut.schedule.ui.components.isManualCopyBlock
import com.glut.schedule.ui.components.ScheduleHeader
import com.glut.schedule.ui.components.ScheduleBackgroundImage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.LocalDate

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
    /** 长按打开「卡片管理」的那一张卡。冲突组里传上来的是当前显示的那一门。 */
    var managedBlock by remember { mutableStateOf<CourseBlock?>(null) }
    /** 刷新变化明细弹层是否可见。 */
    var showRefreshDetail by remember { mutableStateOf(false) }
    // 刷新后的变化明细。单独一个 Flow，不进 ScheduleUiState（那边的 combine 已经排满）。
    val refreshDiff by viewModel.refreshDiff.collectAsStateWithLifecycle()
    if (showRefreshDetail) {
        refreshDiff?.let { diff ->
            RefreshDiffDetailDialog(diff = diff, onDismiss = { showRefreshDetail = false })
        }
    }
    // 「调」/「补」角标要用它反查：卡片本身没有任何字段能说明自己来自哪种调整。
    val semesterAdjustments by viewModel.semesterAdjustments.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    if (!uiState.isInitialized) {
        // 背景设置尚未恢复时只显示中性占位，避免把临时空值误绘制成默认《花》。
        // DataStore 与 Room 都完成首轮恢复后再创建 Pager，避免错误背景和默认周次短暂闪现。
        Box(modifier = modifier.fillMaxSize())
        return
    }
    val blocksByWeek = remember(
        uiState.courses,
        uiState.maxAcademicWeek,
        uiState.semesterStartMonday,
        uiState.manualDayCopies
    ) {
        courseBlocksByWeek(
            courses = uiState.courses,
            maxWeek = uiState.maxAcademicWeek,
            semesterStartMonday = uiState.semesterStartMonday,
            rules = uiState.manualDayCopies
        )
    }
    // 角标跟随调休规则本身：规则只在当前学期生效，历史学期拿到的就是空列表。
    // Pager 的每个页面都要用，因此在进入 Pager 之前算一次。
    val adjustmentDates = remember(uiState.manualDayCopies) {
        uiState.manualDayCopies.mapTo(mutableSetOf()) { it.targetDate }
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
                    // 走 requestRefresh 而不是直接刷新：藏着卡片时会先弹一次确认。
                    // 没藏着卡片时它内部直接走同一条刷新路径，交互与之前完全一样。
                    viewModel.requestRefresh()
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
            // 空态判定必须用**完整**课表：用户把某周或某课次的卡片全隐藏之后，
            // 展示列表会变空，但课表本身还在，不该整页变成「还没有课表 / 去导入课表」。
            if (uiState.allCourses.isEmpty()) {
                ScheduleEmptyState(onImportClick, Modifier.weight(1f).fillMaxWidth())
            } else HorizontalPager(
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
                    holidayDates = uiState.holidayDates,
                    manualAdjustmentDates = adjustmentDates,
                    adjustments = semesterAdjustments,
                    onCourseLongClick = { block ->
                        // 历史学期是只读快照，隐藏记录只对当前学期生效，这里直接不响应，
                        // 免得用户在一份永不生效的学期上删了卡片还看不到任何反应。
                        if (!uiState.isHistoricalSemester && !block.isManualCopyBlock()) {
                            managedBlock = block
                        }
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
        // 刷新后的变化明细（与小程序同构）：有变化时用卡片说明，可展开看全部。
        val diff = refreshDiff
        if (diff != null) {
            RefreshDiffCard(
                summary = diff.summary,
                itemCount = diff.items.size,
                onOpenDetail = { showRefreshDetail = true },
                onDismiss = viewModel::clearRefreshDiff,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 78.dp)
                    .navigationBarsPadding()
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

    // 删除后的撤销入口。走独立通道而不是复用 uiState.message：那条通道会被 clearMessage()
    // 立刻清掉，而且带不了 action 按钮。
    val hiddenUndo by viewModel.hiddenUndo.collectAsStateWithLifecycle()
    LaunchedEffect(hiddenUndo?.id) {
        val undo = hiddenUndo ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "已隐藏该卡片",
            actionLabel = "撤销",
            duration = SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoHideCards()
        } else {
            viewModel.clearHiddenUndo()
        }
    }

    managedBlock?.let { block ->
        CourseCardManageSheet(
            block = block,
            courses = uiState.allCourses,
            currentWeekNumber = uiState.week.number,
            onDismiss = { managedBlock = null },
            onSelectColor = { color ->
                viewModel.setCourseColorOverride(
                    CourseColorMapper.colorKey(block.course.id, block.course.title),
                    color
                )
                managedBlock = null
            },
            onRestoreColor = {
                viewModel.removeCourseColorOverride(
                    CourseColorMapper.colorKey(block.course.id, block.course.title)
                )
                managedBlock = null
            },
            onDelete = { scope ->
                viewModel.hideCard(block, scope)
                managedBlock = null
            }
        )
    }

    // 刷新确认：只有当前学期确实还藏着卡片时才会出现。
    val refreshConfirm by viewModel.refreshConfirm.collectAsStateWithLifecycle()
    refreshConfirm?.let { confirm ->
        var keepHidden by remember(confirm) { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = viewModel::dismissRefreshConfirm,
            containerColor = Color(0xFFFFFEFB),
            titleContentColor = Color(0xFF141821),
            textContentColor = Color(0xFF667085),
            title = { Text("刷新课表") },
            text = {
                Column {
                    Text("检测到你手动删除了 ${confirm.count} 张卡片。")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { keepHidden = !keepHidden }
                            .padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = keepHidden, onCheckedChange = { keepHidden = it })
                        Text("保留我手动删除的 ${confirm.count} 张卡片")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmRefresh(keepHidden) }) { Text("刷新") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRefreshConfirm) { Text("取消") }
            }
        )
    }
}

/**
 * 刷新后的变化摘要卡片。
 *
 * 与小程序 `refresh-feedback-card` 同构：标题「课表已更新」+ 摘要「新增 X 项 · 移除 Y 项 ·
 * 调整 Z 项」+「查看全部 N 项」+ 关闭。只在**确实有变化**时出现，没变化时仍走 Snackbar 短提示。
 */
@Composable
private fun RefreshDiffCard(
    summary: String,
    itemCount: Int,
    onOpenDetail: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xFFFFFEFB),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0xFFE4E0D7)),
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(26.dp).clip(CircleShape).background(Color(0xFF3F7DF6)),
                contentAlignment = Alignment.Center
            ) {
                Text("✓", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text("课表已更新", color = Color(0xFF141821), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(summary, color = Color(0xFF667085), fontSize = 13.sp)
                if (itemCount > 0) {
                    Text(
                        text = "查看全部 $itemCount 项",
                        color = Color(0xFF3F7DF6),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clickable(onClick = onOpenDetail)
                    )
                }
            }
            Text(
                text = "×",
                color = Color(0xFF98A2B3),
                fontSize = 22.sp,
                modifier = Modifier.clickable(onClick = onDismiss).padding(horizontal = 4.dp)
            )
        }
    }
}

/** 变化明细：逐条列出「新增/移除/调整 + 课程名 + 原/现」。 */
@Composable
private fun RefreshDiffDetailDialog(
    diff: ScheduleRefreshDiff,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFFFFFEFB),
        titleContentColor = Color(0xFF141821),
        textContentColor = Color(0xFF667085),
        title = { Text("本次课表变更") },
        text = {
            // 条目可能很多，用限高的纵向滚动框，别让弹窗被撑长。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                diff.items.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider(color = Color(0xFFF0EDE7))
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = item.kind.badgeBackground(), shape = RoundedCornerShape(6.dp)) {
                                Text(
                                    text = item.kind.label,
                                    color = item.kind.badgeForeground(),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Text(
                                text = item.title,
                                color = Color(0xFF141821),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                        if (item.beforeText.isNotBlank()) {
                            Text("原：${item.beforeText}", color = Color(0xFF667085), fontSize = 12.sp)
                        }
                        if (item.afterText.isNotBlank()) {
                            Text("现：${item.afterText}", color = Color(0xFF344054), fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    )
}

private fun ScheduleRefreshDiffItem.Kind.badgeBackground(): Color = when (this) {
    ScheduleRefreshDiffItem.Kind.ADDED -> Color(0xFFEAF8F0)
    ScheduleRefreshDiffItem.Kind.REMOVED -> Color(0xFFFFF0EE)
    ScheduleRefreshDiffItem.Kind.CHANGED -> Color(0xFFEEF4FF)
}

private fun ScheduleRefreshDiffItem.Kind.badgeForeground(): Color = when (this) {
    ScheduleRefreshDiffItem.Kind.ADDED -> Color(0xFF16794A)
    ScheduleRefreshDiffItem.Kind.REMOVED -> Color(0xFFC24135)
    ScheduleRefreshDiffItem.Kind.CHANGED -> Color(0xFF3F7DF6)
}

@Composable
private fun ScheduleEmptyState(onImportClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(horizontal = 28.dp, vertical = 72.dp), contentAlignment = Alignment.Center) {
        Surface(color = Color(0xF2FFFFFB), shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp)) {
            Column(
                modifier = Modifier.padding(horizontal = 30.dp, vertical = 34.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("还没有课表", color = Color(0xFF141821), fontSize = 25.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text("登录教务系统，导入你的课程与考试", color = Color(0xFF5F6673), fontSize = 15.sp, modifier = Modifier.padding(top = 12.dp))
                Surface(
                    modifier = Modifier.padding(top = 28.dp).clickable(onClick = onImportClick),
                    color = Color(0xFF3F7DF6), shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                ) { Text("去导入课表", color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 14.dp)) }
            }
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
    maxWeek: Int = MAX_ACADEMIC_WEEK,
    semesterStartMonday: LocalDate = DEFAULT_SEMESTER_START_MONDAY,
    rules: List<ManualDayCopyRule> = emptyList()
): Map<Int, List<CourseBlock>> {
    val clampedMaxWeek = clampAcademicWeek(maxWeek)
    return (MIN_ACADEMIC_WEEK..clampedMaxWeek).associateWith { weekNumber ->
        courses.flatMap { course ->
            course.occurrences
                .filter { occurrence -> occurrence.isActiveInWeek(weekNumber) }
                .map { occurrence ->
                    CourseBlock(course = course, occurrence = occurrence)
                }
        } + manualCopyBlocksForWeek(
            courses = courses,
            rules = rules,
            weekNumber = weekNumber,
            weekMonday = scheduleWeekForNumber(weekNumber, semesterStartMonday, clampedMaxWeek).monday
        )
    }
}

