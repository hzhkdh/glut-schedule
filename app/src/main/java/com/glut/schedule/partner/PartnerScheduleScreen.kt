package com.glut.schedule.partner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PeopleAlt
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glut.schedule.data.model.periodLabel
import com.glut.schedule.data.model.scheduleWeekForNumber
import com.glut.schedule.data.model.visibleDayCount
import com.glut.schedule.data.settings.PartnerScheduleViewMode
import com.glut.schedule.ui.components.ScheduleCalendarHeader
import com.glut.schedule.ui.components.StarryScheduleBackground
import com.glut.schedule.ui.components.courseCardRoomLineHeight
import com.glut.schedule.ui.components.courseCardRoomTextSize
import com.glut.schedule.ui.components.periodColumnTextStyle
import com.glut.schedule.ui.components.courseCardTeacherLineHeight
import com.glut.schedule.ui.components.courseCardTeacherTextSize
import com.glut.schedule.ui.components.courseCardTitleLineHeight
import com.glut.schedule.ui.components.courseCardTitleMaxLines
import com.glut.schedule.ui.components.courseCardTitleTextSize
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun PartnerScheduleScreen(
    viewModel: PartnerScheduleViewModel,
    customBackgroundUri: String,
    customBackgroundBitmap: ImageBitmap?,
    onDrawerOpen: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showManage by remember { mutableStateOf(false) }
    var detailGroup by remember { mutableStateOf<PartnerDisplayGroup?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.message) {
        if (state.message.isNotBlank()) {
            snackbarHostState.showSnackbar(state.message)
            viewModel.clearMessage()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        StarryScheduleBackground(
            customBackgroundUri = customBackgroundUri,
            customBackgroundBitmap = customBackgroundBitmap
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            PartnerHeader(
                weekNumber = state.week,
                today = state.today,
                viewMode = state.viewMode,
                showViewMode = state.activePartnerSnapshot != null,
                onDrawerOpen = onDrawerOpen,
                onWeekTitleClick = viewModel::returnToCurrentWeek,
                onViewModeChange = viewModel::setViewMode,
                onManage = { showManage = true }
            )
            if (state.activePartnerSnapshot == null) {
                PartnerEmptyState(
                    isBusy = state.isBusy,
                    hasStaleSnapshot = state.hasStalePartnerSnapshot,
                    onManage = { showManage = true }
                )
            } else {
                PartnerScheduleContent(
                    state = state,
                    onWeekSelected = viewModel::setWeek,
                    onGroupClick = { detailGroup = it }
                )
            }
        }
        if (!showManage) {
            PartnerFeedbackHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    if (showManage) {
        PartnerManageSheet(
            state = state,
            onDismiss = { showManage = false },
            onColorChange = viewModel::setMyColor,
            onShowWeekendChange = viewModel::setShowWeekend,
            onShowNoonChange = viewModel::setShowNoon,
            onGenerate = viewModel::generateInvite,
            onImport = viewModel::importInvite,
            onRevoke = viewModel::revokeInvite,
            onDeletePartner = viewModel::deletePartnerSnapshot,
            snackbarHostState = snackbarHostState,
            onFeedback = { message ->
                scope.launch { snackbarHostState.showSnackbar(message) }
            }
        )
    }
    detailGroup?.let { group ->
        PartnerCourseDetailSheet(group = group, onDismiss = { detailGroup = null })
    }
}

@Composable
private fun PartnerHeader(
    weekNumber: Int,
    today: java.time.LocalDate,
    viewMode: PartnerScheduleViewMode,
    showViewMode: Boolean,
    onDrawerOpen: () -> Unit,
    onWeekTitleClick: () -> Unit,
    onViewModeChange: (PartnerScheduleViewMode) -> Unit,
    onManage: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onDrawerOpen, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Outlined.Menu,
                contentDescription = "打开菜单",
                modifier = Modifier.size(22.dp),
                tint = PartnerScheduleVisualStyle.pagePrimaryText
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .widthIn(min = 0.dp)
                .clickable(onClick = onWeekTitleClick),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = partnerHeaderPrimaryText(weekNumber),
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                color = PartnerScheduleVisualStyle.pagePrimaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = today.format(DateTimeFormatter.ofPattern("yyyy/M/d")),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = PartnerScheduleVisualStyle.pagePrimaryText
                )
            }
        }
        if (showViewMode) {
            PartnerViewModeButton(
                mode = viewMode,
                onToggle = {
                    onViewModeChange(
                        if (viewMode == PartnerScheduleViewMode.PARTNER) {
                            PartnerScheduleViewMode.COMBINED
                        } else {
                            PartnerScheduleViewMode.PARTNER
                        }
                    )
                }
            )
        }
        IconButton(onClick = onManage, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.Share, contentDescription = "分享与导入", tint = Color(0xFFD94F78))
        }
    }
}

@Composable
private fun PartnerViewModeButton(
    mode: PartnerScheduleViewMode,
    onToggle: () -> Unit
) {
    val isPartnerOnly = partnerViewModeIcon(mode) == PartnerViewModeIcon.SINGLE_PERSON
    IconButton(onClick = onToggle, modifier = Modifier.size(48.dp)) {
        Icon(
            imageVector = if (isPartnerOnly) Icons.Outlined.PersonOutline else Icons.Outlined.PeopleAlt,
            contentDescription = if (isPartnerOnly) {
                "当前显示TA的课表，点击切换到一起"
            } else {
                "当前显示一起的课表，点击切换到TA的课表"
            },
            tint = Color(0xFFD94F78)
        )
    }
}

@Composable
private fun PartnerEmptyState(
    isBusy: Boolean,
    hasStaleSnapshot: Boolean,
    onManage: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = CircleShape,
            color = PartnerScheduleVisualStyle.courseCard(PartnerIdentityColor.PINK).surface,
            modifier = Modifier.size(88.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.Group,
                    contentDescription = null,
                    tint = Color(0xFFD94F78),
                    modifier = Modifier.size(42.dp)
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            if (hasStaleSnapshot) "TA的课表属于其他学期" else "还没有TA的课表",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = PartnerScheduleVisualStyle.pagePrimaryText
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (hasStaleSnapshot) {
                "旧课表已暂停显示，请导入TA当前学期的邀请码。"
            } else {
                "生成自己的邀请码，或输入TA的邀请码。"
            },
            color = PartnerScheduleVisualStyle.pageSecondaryText,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onManage, enabled = !isBusy, modifier = Modifier.height(48.dp)) {
            Icon(Icons.Outlined.PersonAddAlt, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("分享与导入")
        }
    }
}

@Composable
private fun PartnerScheduleContent(
    state: PartnerScheduleUiState,
    onWeekSelected: (Int) -> Unit,
    onGroupClick: (PartnerDisplayGroup) -> Unit
) {
    val campusByOwner = buildMap {
        put(state.myColor, state.campusKey)
        state.activePartnerSnapshot?.let { snapshot ->
            put(snapshot.identityColor, snapshot.campus)
        }
    }
    val pagerState = rememberPagerState(
        initialPage = partnerPagerPageForWeek(state.week, state.maxWeek),
        pageCount = { state.maxWeek.coerceAtLeast(1) }
    )
    val latestWeek by rememberUpdatedState(state.week)
    val latestMaxWeek by rememberUpdatedState(state.maxWeek)

    LaunchedEffect(state.week, state.maxWeek) {
        val targetPage = partnerPagerPageForWeek(state.week, state.maxWeek)
        if (pagerState.currentPage != targetPage && pagerState.settledPage != targetPage) {
            // ViewModel 可能因导入或日期恢复而改变周次，Pager 必须同步到同一页。
            pagerState.scrollToPage(targetPage)
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val week = partnerWeekForPagerPage(page, latestMaxWeek)
                if (week != latestWeek) onWeekSelected(week)
            }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) {
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
            val week = partnerWeekForPagerPage(page, state.maxWeek)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp)
            ) {
                PartnerTimeGrid(
                    week = scheduleWeekForNumber(week, state.semesterStartMonday, state.maxWeek),
                    today = state.today,
                    groups = partnerDisplayGroups(
                        week = week,
                        courses = state.displayedCourses,
                        campusByOwner = campusByOwner
                    ),
                    classPeriods = state.classPeriods,
                    localCampus = state.campusKey,
                    campusByOwner = campusByOwner,
                    showWeekend = state.showWeekend,
                    showNoon = state.showNoon,
                    onGroupClick = onGroupClick
                )
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun PartnerTimeGrid(
    week: com.glut.schedule.data.model.ScheduleWeek,
    today: java.time.LocalDate,
    groups: List<PartnerDisplayGroup>,
    classPeriods: List<com.glut.schedule.data.model.ClassPeriod>,
    localCampus: String,
    campusByOwner: Map<PartnerIdentityColor, String>,
    showWeekend: Boolean,
    showNoon: Boolean,
    onGroupClick: (PartnerDisplayGroup) -> Unit
) {
    if (classPeriods.isEmpty()) return
    val dayCount = visibleDayCount(showWeekend)
    val visibleSections = partnerVisibleSections(classPeriods, showNoon, localCampus)
    val visiblePeriods = classPeriods.filter {
        partnerCanonicalSection(localCampus, it.section) in visibleSections
    }
    val rowHeight = 74.dp
    val gridHeight = rowHeight * visiblePeriods.size

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val timeWidth = 52.dp
        val dayWidth = (maxWidth - timeWidth) / dayCount
        Column {
            ScheduleCalendarHeader(
                week = week,
                today = today,
                leftWidth = timeWidth,
                dayWidth = dayWidth,
                dayCount = dayCount
            )
            Box(modifier = Modifier.fillMaxWidth().height(gridHeight)) {
                visiblePeriods.forEachIndexed { rowIndex, period ->
                    Column(
                        modifier = Modifier
                            .offset(y = rowHeight * rowIndex)
                            .width(timeWidth)
                            .height(rowHeight)
                            .padding(start = 6.dp, top = 7.dp)
                    ) {
                        Text(
                            text = if (classPeriods.size <= 11) {
                                period.section.toString()
                            } else {
                                period.periodLabel()
                            },
                            style = periodColumnTextStyle(fontSize = 14.sp, lineHeight = 16.sp),
                            maxLines = 1,
                            fontWeight = FontWeight.Bold,
                            color = PartnerScheduleVisualStyle.pagePrimaryText
                        )
                        Text(
                            text = period.startsAt,
                            style = periodColumnTextStyle(fontSize = 9.sp, lineHeight = 11.sp),
                            maxLines = 1,
                            color = PartnerScheduleVisualStyle.pageSecondaryText
                        )
                        Text(
                            text = period.endsAt,
                            style = periodColumnTextStyle(fontSize = 9.sp, lineHeight = 11.sp),
                            maxLines = 1,
                            color = PartnerScheduleVisualStyle.pageSecondaryText
                        )
                    }
                }
                groups.filter { it.dayOfWeek <= dayCount }.forEach { group ->
                    val startRow = partnerGridRowIndex(
                        group.startSection,
                        classPeriods,
                        showNoon,
                        localCampus
                    ) ?: return@forEach
                    val visibleSpan = visibleSections.count {
                        it in group.startSection..group.endSection
                    }.coerceAtLeast(1)
                    PartnerGroupCard(
                        group = group,
                        campusByOwner = campusByOwner,
                        modifier = Modifier
                            .offset(
                                x = timeWidth + dayWidth * (group.dayOfWeek - 1) + 3.dp,
                                y = rowHeight * startRow + 3.dp
                            )
                            .width(dayWidth - 6.dp)
                            .height(rowHeight * visibleSpan - 6.dp),
                        onClick = { onGroupClick(group) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PartnerGroupCard(
    group: PartnerDisplayGroup,
    campusByOwner: Map<PartnerIdentityColor, String>,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val ordered = group.courses.sortedBy { PartnerIdentityColor.entries.indexOf(it.ownerColor) }
    val mixedRepresentatives = partnerCardVisibleCourses(ordered, campusByOwner)
    val firstStyle = PartnerScheduleVisualStyle.courseCard(mixedRepresentatives.first().ownerColor)
    val secondStyle = mixedRepresentatives.getOrNull(1)?.let {
        PartnerScheduleVisualStyle.courseCard(it.ownerColor)
    } ?: firstStyle
    val background = if (group.kind == PartnerOverlapKind.NONE) {
        Brush.verticalGradient(listOf(firstStyle.surface, firstStyle.surface))
    } else {
        val colorStops = partnerSplitCardStops(firstStyle.surface, secondStyle.surface)
            .map { stop -> stop.position to stop.color }
            .toTypedArray()
        Brush.verticalGradient(colorStops = colorStops)
    }
    val semanticText = ordered.joinToString("；") { course ->
        "${course.ownerColor.displayName}课程${course.title}" +
            course.room?.let { "，教室$it" }.orEmpty() +
            course.teacher?.let { "，教师$it" }.orEmpty()
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(onClick = onClick)
            .semantics { contentDescription = semanticText }
            .padding(4.dp)
    ) {
        if (partnerCardShowsMetadata(group.kind)) {
            FullCourseContent(
                course = ordered.first(),
                sectionSpan = group.endSection - group.startSection + 1
            )
        } else {
            val showBadge = partnerShouldShowOverlapBadge(group.courses.size)
            MixedCourseContent(
                courses = mixedRepresentatives,
                reserveBadgeWidth = showBadge
            )
            if (showBadge) {
                OverlapBadge(
                    count = group.courses.size,
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }
        }
    }
}

@Composable
private fun FullCourseContent(
    course: PartnerCourse,
    sectionSpan: Int,
    modifier: Modifier = Modifier
) {
    val textColor = PartnerScheduleVisualStyle.courseCard(course.ownerColor).content
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            course.title,
            color = textColor,
            fontWeight = FontWeight.Bold,
            fontSize = courseCardTitleTextSize(course.title),
            lineHeight = courseCardTitleLineHeight(),
            maxLines = courseCardTitleMaxLines(sectionSpan),
            overflow = TextOverflow.Ellipsis
        )
        course.room?.let {
            Text(
                "@$it",
                color = textColor,
                fontSize = courseCardRoomTextSize(),
                lineHeight = courseCardRoomLineHeight(),
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        course.teacher?.let {
            Text(
                it,
                color = textColor.copy(alpha = 0.95f),
                fontSize = courseCardTeacherTextSize(),
                lineHeight = courseCardTeacherLineHeight(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MixedCourseContent(
    courses: List<PartnerCourse>,
    reserveBadgeWidth: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Top
    ) {
        courses.take(2).forEachIndexed { index, course ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(
                        end = if (index == 0 && reserveBadgeWidth) {
                            PartnerScheduleVisualStyle.overlapBadgeSize + 2.dp
                        } else {
                            0.dp
                        }
                    ),
                contentAlignment = if (index == 0) Alignment.TopStart else Alignment.BottomStart
            ) {
                Text(
                    course.title,
                    color = PartnerScheduleVisualStyle.courseCard(course.ownerColor).content,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    lineHeight = 12.sp,
                    maxLines = Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun OverlapBadge(count: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(PartnerScheduleVisualStyle.overlapBadgeSize),
        shape = CircleShape,
        color = Color(0xFF34333A)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(count.toString(), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PartnerManageSheet(
    state: PartnerScheduleUiState,
    onDismiss: () -> Unit,
    onColorChange: (PartnerIdentityColor) -> Unit,
    onShowWeekendChange: (Boolean) -> Unit,
    onShowNoonChange: (Boolean) -> Unit,
    onGenerate: (Boolean, Boolean) -> Unit,
    onImport: (String) -> Unit,
    onRevoke: () -> Unit,
    onDeletePartner: () -> Unit,
    snackbarHostState: SnackbarHostState,
    onFeedback: (String) -> Unit
) {
    val context = LocalContext.current
    val defaultShareOptions = remember { partnerDefaultShareOptions() }
    var shareRoom by remember { mutableStateOf(defaultShareOptions.shareRoom) }
    var shareTeacher by remember { mutableStateOf(defaultShareOptions.shareTeacher) }
    var inviteInput by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRevoke by remember { mutableStateOf(false) }
    var pendingReplacementInput by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = PartnerScheduleVisualStyle.manageSheetSurface,
        contentColor = PartnerScheduleVisualStyle.managePrimaryText
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "分享与导入",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = PartnerScheduleVisualStyle.managePrimaryText
                )

                PartnerManageSection(title = "我的身份色") {
                    IdentityColorSelector(
                        selected = state.myColor,
                        partnerColor = state.activePartnerSnapshot?.identityColor,
                        locked = state.activeInvite != null || state.isBusy,
                        onColorChange = onColorChange
                    )
                    state.activePartnerSnapshot?.identityColor?.let { partnerColor ->
                        Text(
                            "TA的颜色是${partnerColor.displayName}，该颜色不可重复使用",
                            fontSize = 12.sp,
                            color = PartnerScheduleVisualStyle.manageSecondaryText
                        )
                    }
                    if (state.activeInvite != null) {
                        Text(
                            "当前邀请码有效期间身份色保持不变，撤销后可重新选择",
                            fontSize = 12.sp,
                            color = PartnerScheduleVisualStyle.manageSecondaryText
                        )
                    }
                    PartnerSettingRow(
                        title = "显示周末",
                        description = "仅影响情侣/基友课表",
                        checked = state.showWeekend,
                        onCheckedChange = onShowWeekendChange
                    )
                    PartnerSettingRow(
                        title = "显示中午",
                        description = "仅影响情侣/基友课表",
                        checked = state.showNoon,
                        onCheckedChange = onShowNoonChange
                    )
                }

                PartnerManageSection(title = "分享我的课表") {
                    if (state.activeInvite == null) {
                        PartnerSettingRow(
                            title = "分享教室",
                            description = "对方可在课程详情中查看教室",
                            checked = shareRoom,
                            onCheckedChange = { shareRoom = it }
                        )
                        PartnerSettingRow(
                            title = "分享教师",
                            description = "对方可在课程详情中查看教师",
                            checked = shareTeacher,
                            onCheckedChange = { shareTeacher = it }
                        )
                        Button(
                            onClick = { onGenerate(shareRoom, shareTeacher) },
                            enabled = canGeneratePartnerInvite(
                                hasCourses = state.ownCourses.isNotEmpty(),
                                isBusy = state.isBusy,
                                hasActiveInvite = false
                            ),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PartnerScheduleVisualStyle.manageAccent,
                                contentColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            if (state.isBusy) {
                                CircularProgressIndicator(
                                    Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                            } else {
                                Text("生成24小时邀请码")
                            }
                        }
                    } else {
                        InviteCard(
                            invite = state.activeInvite,
                            context = context,
                            onFeedback = onFeedback
                        )
                        Button(
                            onClick = { confirmRevoke = true },
                            enabled = !state.isBusy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFFE3E0),
                                contentColor = Color(0xFFB3261E),
                                disabledContainerColor = Color(0xFFFFE3E0).copy(alpha = 0.55f),
                                disabledContentColor = Color(0xFFB3261E).copy(alpha = 0.45f)
                            ),
                            border = BorderStroke(1.dp, Color(0xFFB3261E).copy(alpha = 0.55f)),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Icon(Icons.Outlined.LinkOff, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("撤销当前邀请码")
                        }
                    }
                }

                PartnerManageSection(title = "导入TA的课表") {
                    OutlinedTextField(
                        value = inviteInput,
                        onValueChange = { inviteInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("TA的邀请码") },
                        singleLine = true,
                        colors = partnerManageTextFieldColors()
                    )
                    Button(
                        onClick = {
                            if (state.partnerSnapshot == null) {
                                onImport(inviteInput)
                            } else {
                                pendingReplacementInput = inviteInput
                            }
                        },
                        enabled = !state.isBusy && inviteInput.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PartnerScheduleVisualStyle.manageAccent,
                            contentColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text(if (state.partnerSnapshot == null) "导入TA的课表" else "覆盖现有TA的课表")
                    }
                }

                if (state.partnerSnapshot != null) {
                    PartnerManageSection(title = "本地数据") {
                        TextButton(
                            onClick = { confirmDelete = true },
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("删除本地TA的课表", color = Color(0xFFB3261E))
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            // ModalBottomSheet 位于页面根 Snackbar 之上，提示必须在面板层内承载才可见。
            PartnerFeedbackHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    if (confirmRevoke) {
        AlertDialog(
            onDismissRequest = { confirmRevoke = false },
            title = { Text("撤销当前邀请码？") },
            text = {
                Text("撤销后，对方将无法再使用该邀请码导入你的课表；已经导入的课表不会被删除。")
            },
            confirmButton = {
                TextButton(onClick = {
                    onRevoke()
                    confirmRevoke = false
                }) {
                    Text("确认撤销", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRevoke = false }) { Text("取消") }
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除TA的课表？") },
            text = { Text("只删除本机保存的TA的课表，不会撤销对方的邀请码。") },
            confirmButton = {
                TextButton(onClick = {
                    onDeletePartner()
                    confirmDelete = false
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }
    pendingReplacementInput?.let { input ->
        AlertDialog(
            onDismissRequest = { pendingReplacementInput = null },
            title = { Text("覆盖现有TA的课表？") },
            text = { Text("导入成功后，本机现有的TA的课表将被新快照替换。") },
            confirmButton = {
                TextButton(onClick = {
                    onImport(input)
                    pendingReplacementInput = null
                }) { Text("确认覆盖") }
            },
            dismissButton = {
                TextButton(onClick = { pendingReplacementInput = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun PartnerManageSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = PartnerScheduleVisualStyle.manageSectionSurface,
        contentColor = PartnerScheduleVisualStyle.managePrimaryText,
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                title,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = PartnerScheduleVisualStyle.managePrimaryText
            )
            content()
        }
    }
}

@Composable
private fun IdentityColorSelector(
    selected: PartnerIdentityColor,
    partnerColor: PartnerIdentityColor?,
    locked: Boolean,
    onColorChange: (PartnerIdentityColor) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PartnerIdentityColor.entries.chunked(4).forEach { rowColors ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowColors.forEach { color ->
                    val style = PartnerScheduleVisualStyle.courseCard(color)
                    val isPartnerColor = color == partnerColor
                    val enabled = !locked && !isPartnerColor
                    val isSelected = color == selected
                    Surface(
                        onClick = { onColorChange(color) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = style.surface.copy(alpha = if (enabled || isSelected) 1f else 0.48f),
                        contentColor = style.content,
                        border = BorderStroke(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = style.content.copy(alpha = if (isSelected) 1f else 0.35f)
                        )
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    color.displayName,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                                if (isPartnerColor) {
                                    Text("TA的颜色", fontSize = 8.sp, maxLines = 1)
                                }
                            }
                            if (isSelected) {
                                Icon(
                                    Icons.Outlined.Check,
                                    contentDescription = "已选择",
                                    modifier = Modifier.align(Alignment.TopEnd).size(15.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PartnerSettingRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = PartnerScheduleVisualStyle.managePrimaryText
            )
            Text(
                description,
                fontSize = 11.sp,
                color = PartnerScheduleVisualStyle.manageSecondaryText
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = PartnerScheduleVisualStyle.manageAccent
            )
        )
    }
}

@Composable
private fun partnerManageTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = PartnerScheduleVisualStyle.managePrimaryText,
    unfocusedTextColor = PartnerScheduleVisualStyle.managePrimaryText,
    focusedBorderColor = PartnerScheduleVisualStyle.manageAccent,
    unfocusedBorderColor = PartnerScheduleVisualStyle.manageSecondaryText.copy(alpha = 0.55f),
    focusedLabelColor = PartnerScheduleVisualStyle.manageAccent,
    unfocusedLabelColor = PartnerScheduleVisualStyle.manageSecondaryText,
    cursorColor = PartnerScheduleVisualStyle.manageAccent,
    focusedSupportingTextColor = PartnerScheduleVisualStyle.manageSecondaryText,
    unfocusedSupportingTextColor = PartnerScheduleVisualStyle.manageSecondaryText
)

@Composable
private fun PartnerFeedbackHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier
            .padding(horizontal = 20.dp, vertical = 18.dp)
            .navigationBarsPadding()
    ) { data ->
        Snackbar(
            snackbarData = data,
            shape = RoundedCornerShape(14.dp),
            containerColor = PartnerScheduleVisualStyle.feedbackSurface,
            contentColor = PartnerScheduleVisualStyle.feedbackContent
        )
    }
}

@Composable
private fun InviteCard(
    invite: StoredPartnerInvite,
    context: Context,
    onFeedback: (String) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = PartnerScheduleVisualStyle.inviteSurface,
        contentColor = PartnerScheduleVisualStyle.inviteContent,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(invite.code.chunked(4).joinToString(" "), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                partnerInviteExpiryText(invite.expiresAt),
                fontSize = 12.sp,
                color = Color(0xFF756D70)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("TA的课表邀请码", invite.code))
                        onFeedback("邀请码已复制")
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = PartnerScheduleVisualStyle.inviteAction
                    ),
                    border = BorderStroke(1.dp, PartnerScheduleVisualStyle.inviteAction)
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("复制")
                }
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, partnerInviteShareText(invite.code))
                                },
                                "分享TA的课表邀请码"
                            )
                        )
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = PartnerScheduleVisualStyle.inviteAction
                    ),
                    border = BorderStroke(1.dp, PartnerScheduleVisualStyle.inviteAction)
                ) {
                    Icon(Icons.Outlined.IosShare, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("分享")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PartnerCourseDetailSheet(group: PartnerDisplayGroup, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                partnerOverlapDetailTitle(group.courses.size),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            group.courses.forEach { course ->
                val cardStyle = PartnerScheduleVisualStyle.courseCard(course.ownerColor)
                Surface(
                    color = cardStyle.surface,
                    contentColor = cardStyle.content,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "${course.ownerColor.displayName}课程",
                            fontSize = 12.sp,
                            color = cardStyle.content
                        )
                        Text(course.title, fontWeight = FontWeight.Bold)
                        course.room?.let { Text("教室：$it") }
                        course.teacher?.let { Text("教师：$it") }
                        val (startsAt, endsAt) = partnerCourseDetailTimeRange(course)
                        Text("时间：$startsAt–$endsAt")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

internal fun partnerWeekForPagerPage(page: Int, maxWeek: Int): Int =
    (page + 1).coerceIn(1, maxWeek.coerceAtLeast(1))

internal fun partnerPagerPageForWeek(week: Int, maxWeek: Int): Int =
    week.coerceIn(1, maxWeek.coerceAtLeast(1)) - 1
