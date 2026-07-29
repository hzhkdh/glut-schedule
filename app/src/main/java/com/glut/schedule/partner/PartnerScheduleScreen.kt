package com.glut.schedule.partner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.time.Duration
import java.time.LocalTime

private val PartnerPink = PartnerScheduleVisualStyle.pink
private val PartnerPinkSurface = PartnerScheduleVisualStyle.pinkSurface
private val PartnerPinkText = PartnerScheduleVisualStyle.pinkContent
private val PartnerBlue = PartnerScheduleVisualStyle.blue
private val PartnerBlueSurface = PartnerScheduleVisualStyle.blueSurface
private val PartnerBlueText = PartnerScheduleVisualStyle.blueContent
private val PartnerBackground = PartnerScheduleVisualStyle.pageBackground
private val EmptyCell = PartnerScheduleVisualStyle.emptyCell

@Composable
fun PartnerScheduleScreen(
    viewModel: PartnerScheduleViewModel,
    onDrawerOpen: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showManage by remember { mutableStateOf(false) }
    var detailGroup by remember { mutableStateOf<PartnerDisplayGroup?>(null) }

    LaunchedEffect(state.message) {
        if (state.message.isNotBlank()) {
            Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PartnerBackground)
            .statusBarsPadding()
    ) {
        PartnerHeader(
            week = state.week,
            commonFreeCount = state.commonFreeCountToday,
            onDrawerOpen = onDrawerOpen,
            onManage = { showManage = true }
        )
        if (state.partnerSnapshot == null) {
            PartnerEmptyState(
                isBusy = state.isBusy,
                onManage = { showManage = true }
            )
        } else {
            PartnerScheduleContent(
                state = state,
                onPreviousWeek = viewModel::previousWeek,
                onNextWeek = viewModel::nextWeek,
                onGroupClick = { detailGroup = it }
            )
        }
    }

    if (showManage) {
        PartnerManageSheet(
            state = state,
            onDismiss = { showManage = false },
            onColorChange = viewModel::setMyColor,
            onGenerate = viewModel::generateInvite,
            onImport = viewModel::importInvite,
            onRevoke = viewModel::revokeInvite,
            onDeletePartner = viewModel::deletePartnerSnapshot
        )
    }
    detailGroup?.let { group ->
        PartnerCourseDetailSheet(group = group, onDismiss = { detailGroup = null })
    }
}

@Composable
private fun PartnerHeader(
    week: Int,
    commonFreeCount: Int,
    onDrawerOpen: () -> Unit,
    onManage: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onDrawerOpen, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.Menu, contentDescription = "打开菜单")
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "TA课表",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF202127)
            )
            Text(
                text = "第 $week 周 · 今天有 $commonFreeCount 段共同空闲",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF82777B)
            )
        }
        IconButton(onClick = onManage, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.Share, contentDescription = "分享与导入", tint = Color(0xFFD94F78))
        }
    }
}

@Composable
private fun PartnerEmptyState(isBusy: Boolean, onManage: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(shape = CircleShape, color = PartnerPinkSurface, modifier = Modifier.size(88.dp)) {
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
            "还没有TA的课表",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = PartnerScheduleVisualStyle.pagePrimaryText
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "生成自己的邀请码，或输入TA发来的16位邀请码。",
            color = Color(0xFF756D70),
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
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onGroupClick: (PartnerDisplayGroup) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp)
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onPreviousWeek, enabled = state.week > 1, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "上一周")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                PartnerLegendDot(color = PartnerPink, text = "粉色")
                PartnerLegendDot(color = PartnerBlue, text = "蓝色")
            }
            IconButton(
                onClick = onNextWeek,
                enabled = state.week < state.maxWeek,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "下一周")
            }
        }
        PartnerTimeGrid(
            groups = partnerDisplayGroups(state.week, state.combinedCourses),
            classPeriods = state.classPeriods,
            onGroupClick = onGroupClick
        )
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun PartnerLegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 12.sp, color = Color(0xFF756D70))
    }
}

@Composable
private fun PartnerTimeGrid(
    groups: List<PartnerDisplayGroup>,
    classPeriods: List<com.glut.schedule.data.model.ClassPeriod>,
    onGroupClick: (PartnerDisplayGroup) -> Unit
) {
    if (classPeriods.isEmpty()) return
    val dayCount = if (groups.any { it.dayOfWeek > 5 }) 7 else 5
    val startTime = classPeriods.minOf { LocalTime.parse(it.startsAt) }
    val endTime = classPeriods.maxOf { LocalTime.parse(it.endsAt) }
    val totalMinutes = Duration.between(startTime, endTime).toMinutes().coerceAtLeast(1)
    val gridHeight = (totalMinutes / 60f * 58f).dp.coerceAtLeast(480.dp)

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val timeWidth = 42.dp
        val dayWidth = (maxWidth - timeWidth) / dayCount
        Column {
            Row(modifier = Modifier.padding(start = timeWidth)) {
                listOf("一", "二", "三", "四", "五", "六", "日").take(dayCount).forEach { day ->
                    Text(
                        text = day,
                        modifier = Modifier.width(dayWidth),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = Color(0xFF756D70),
                        fontSize = 13.sp
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth().height(gridHeight)) {
                Row(modifier = Modifier.offset(x = timeWidth)) {
                    repeat(dayCount) {
                        Box(
                            Modifier
                                .width(dayWidth)
                                .height(gridHeight)
                                .padding(horizontal = 2.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(EmptyCell.copy(alpha = 0.72f))
                        )
                    }
                }
                classPeriods.forEach { period ->
                    val minute = Duration.between(startTime, LocalTime.parse(period.startsAt)).toMinutes()
                    val y = gridHeight * (minute.toFloat() / totalMinutes.toFloat())
                    Text(
                        text = period.section.toString(),
                        modifier = Modifier.offset(y = y - 2.dp).width(timeWidth),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        fontSize = 10.sp,
                        color = Color(0xFF958B8E)
                    )
                }
                groups.filter { it.dayOfWeek <= dayCount }.forEach { group ->
                    val (cardStartTime, cardEndTime) = partnerCardTimeRange(group)
                    val startMinute = Duration.between(startTime, LocalTime.parse(cardStartTime)).toMinutes()
                    val endMinute = Duration.between(startTime, LocalTime.parse(cardEndTime)).toMinutes()
                    val y = gridHeight * (startMinute.toFloat() / totalMinutes.toFloat())
                    val height = (gridHeight * ((endMinute - startMinute).toFloat() / totalMinutes.toFloat()))
                        .coerceAtLeast(42.dp)
                    PartnerGroupCard(
                        group = group,
                        modifier = Modifier
                            .offset(
                                x = timeWidth + dayWidth * (group.dayOfWeek - 1) + 3.dp,
                                y = y + 2.dp
                            )
                            .width(dayWidth - 6.dp)
                            .height(height - 4.dp),
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
    modifier: Modifier,
    onClick: () -> Unit
) {
    val ordered = group.courses.sortedBy { if (it.ownerColor == PartnerIdentityColor.PINK) 0 else 1 }
    val background = when (group.kind) {
        PartnerOverlapKind.EXACT, PartnerOverlapKind.SAME_COURSE -> Brush.verticalGradient(
            listOf(PartnerPinkSurface, PartnerPinkSurface, PartnerBlueSurface, PartnerBlueSurface)
        )
        else -> {
            val owner = ordered.first().ownerColor
            Brush.verticalGradient(
                listOf(
                    if (owner == PartnerIdentityColor.PINK) PartnerPinkSurface else PartnerBlueSurface,
                    if (owner == PartnerIdentityColor.PINK) PartnerPinkSurface else PartnerBlueSurface
                )
            )
        }
    }
    val semanticText = ordered.joinToString("；") { course ->
        "${if (course.ownerColor == PartnerIdentityColor.PINK) "粉色" else "蓝色"}课程${course.title}" +
            course.room?.let { "，教室$it" }.orEmpty() +
            course.teacher?.let { "，教师$it" }.orEmpty()
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(onClick = onClick)
            .semantics { contentDescription = semanticText }
            .padding(6.dp)
    ) {
        when (group.kind) {
            PartnerOverlapKind.EXACT -> DualCourseContent(ordered)
            PartnerOverlapKind.SAME_COURSE -> FullCourseContent(ordered.first())
            PartnerOverlapKind.PARTIAL -> {
                FullCourseContent(
                    course = ordered.first(),
                    modifier = Modifier.padding(
                        end = PartnerScheduleVisualStyle.cardContentEndPadding(group.kind)
                    )
                )
                OverlapBadge(
                    count = group.courses.size,
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }
            PartnerOverlapKind.NONE -> FullCourseContent(ordered.first())
        }
    }
}

@Composable
private fun FullCourseContent(course: PartnerCourse, modifier: Modifier = Modifier) {
    val textColor = if (course.ownerColor == PartnerIdentityColor.PINK) PartnerPinkText else PartnerBlueText
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            course.title,
            color = textColor,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            lineHeight = 12.sp,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
        course.room?.let {
            Text("@$it", color = textColor, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        course.teacher?.let {
            Text(it, color = textColor.copy(alpha = 0.88f), fontSize = 9.sp, maxLines = 1)
        }
    }
}

@Composable
private fun DualCourseContent(courses: List<PartnerCourse>) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        courses.take(2).forEach { course ->
            Text(
                course.title,
                color = if (course.ownerColor == PartnerIdentityColor.PINK) PartnerPinkText else PartnerBlueText,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                lineHeight = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
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
    onGenerate: (Boolean, Boolean) -> Unit,
    onImport: (String) -> Unit,
    onRevoke: () -> Unit,
    onDeletePartner: () -> Unit
) {
    val context = LocalContext.current
    var shareRoom by remember { mutableStateOf(true) }
    var shareTeacher by remember { mutableStateOf(false) }
    var inviteInput by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var pendingReplacementInput by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("分享与导入", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("我的身份色", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(
                    selected = state.myColor == PartnerIdentityColor.PINK,
                    onClick = { onColorChange(PartnerIdentityColor.PINK) },
                    label = { Text("粉色") }
                )
                FilterChip(
                    selected = state.myColor == PartnerIdentityColor.BLUE,
                    onClick = { onColorChange(PartnerIdentityColor.BLUE) },
                    label = { Text("蓝色") }
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = shareRoom, onCheckedChange = { shareRoom = it })
                Text("分享教室")
                Spacer(Modifier.width(16.dp))
                Checkbox(checked = shareTeacher, onCheckedChange = { shareTeacher = it })
                Text("分享教师")
            }
            Button(
                onClick = { onGenerate(shareRoom, shareTeacher) },
                enabled = canGeneratePartnerInvite(
                    hasCourses = state.ownCourses.isNotEmpty(),
                    isBusy = state.isBusy,
                    hasActiveInvite = state.activeInvite != null
                ),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                if (state.isBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text(if (state.activeInvite == null) "生成24小时邀请码" else "请先撤销当前邀请码")
            }
            state.activeInvite?.let { invite ->
                InviteCard(invite = invite, context = context)
                OutlinedButton(
                    onClick = onRevoke,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text("撤销当前邀请码")
                }
            }
            HorizontalDivider()
            OutlinedTextField(
                value = inviteInput,
                onValueChange = { inviteInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("TA的邀请码或二维码内容") },
                supportingText = { Text("支持16位邀请码和 GLUT-SCHEDULE:V1:…") },
                singleLine = true
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
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text(if (state.partnerSnapshot == null) "导入TA课表" else "覆盖现有TA课表")
            }
            if (state.partnerSnapshot != null) {
                TextButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("删除本地TA课表", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除TA课表？") },
            text = { Text("只删除本机保存的TA课表，不会撤销对方的邀请码。") },
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
            title = { Text("覆盖现有TA课表？") },
            text = { Text("导入成功后，本机现有的TA课表将被新快照替换。") },
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
private fun InviteCard(invite: StoredPartnerInvite, context: Context) {
    val payload = inviteQrPayload(invite.code)
    val qrBitmap = remember(payload) { createQrBitmap(payload, 560) }
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
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = "课表邀请码二维码",
                modifier = Modifier.size(190.dp)
            )
            Text(invite.code.chunked(4).joinToString(" "), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("有效至 ${invite.expiresAt}", fontSize = 12.sp, color = Color(0xFF756D70))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("TA课表邀请码", invite.code))
                        Toast.makeText(context, "邀请码已复制", Toast.LENGTH_SHORT).show()
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
                                    putExtra(Intent.EXTRA_TEXT, "TA课表邀请码：${invite.code}\n$payload")
                                },
                                "分享TA课表邀请码"
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
                if (group.courses.size > 1) "${group.courses.size} 门重叠课程" else "课程详情",
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
                            if (course.ownerColor == PartnerIdentityColor.PINK) "粉色课程" else "蓝色课程",
                            fontSize = 12.sp,
                            color = cardStyle.content
                        )
                        Text(course.title, fontWeight = FontWeight.Bold)
                        course.room?.let { Text("教室：$it") }
                        course.teacher?.let { Text("教师：$it") }
                        Text("时间：${course.startTime}–${course.endTime}")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun createQrBitmap(content: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(EncodeHintType.MARGIN to 1)
    )
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until size) {
            for (x in 0 until size) {
                setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
    }
}
