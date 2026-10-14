package com.glut.schedule.ui.pages

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glut.schedule.data.model.ManualDayCopyRule
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private val PageBg = Color(0xFFF6F4EF)
private val PanelBg = Color(0xFFFFFEFB)
private val TextPrimary = Color(0xFF141821)
private val TextSecondary = Color(0xFF667085)

/** 计数这类弱化元素。比 [TextSecondary] 再淡一档，但不参与正文。 */
private val TextFaint = Color(0xFF98A0AF)
private val DividerColor = Color(0xFFEDE8DE)

/**
 * 自动调休的身份色：沿用页面本来就有的强调蓝。
 *
 * 这一页原本两类长得一模一样——手动表单的日期块和自动规则的行内徽标用的是**同一个蓝**，
 * 于是「自动 / 手动」在视觉上反而分不出来。现在两类各有一条色条贯穿分区标题、组标题与控件。
 */
private val Accent = Color(0xFF3F7DF6)

/** 手动调休的身份色：沿用课表上「调」角标已经在用的橙，语义正好对上「调休」。 */
private val ManualAccent = Color(0xFFE57411)
private val ManualChipBg = Color(0xFFFDF3E9)
private val ManualChipText = Color(0xFFC2610A)

private val WarningText = Color(0xFFB45843)
private val DangerText = Color(0xFFDC2626)
private val OnAccent = Color.White

/** 列表行里日期的颜色：比 [TextPrimary] 略亮，与标题区分开。 */
private val RuleDateText = Color(0xFF202633)

/**
 * 两个日期块之间箭头的固定占位宽。
 *
 * 固定宽度有两个作用：两边的日期块保持等宽，箭头也才能落在**日期块**的中线上——它原先的
 * 兄弟是「标签 + 日期块」整列，于是箭头落在整列的中线上，看起来比日期块高出一截。
 */
private val DateArrowWidth = 32.dp

/** 日期选择器以 UTC 零点为坐标；与 [LocalDate] 互转时必须固定用 UTC，否则会整体偏一天。 */
internal fun localDateToUtcMillis(date: LocalDate): Long =
    date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun utcMillisToLocalDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

/** 目标日期默认取原日期的次日；再加就超出学期时保持原日期不变。 */
internal fun nextDateWithinSemester(sourceDate: LocalDate, semesterEndDate: LocalDate): LocalDate =
    sourceDate.plusDays(1).takeIf { !it.isAfter(semesterEndDate) } ?: sourceDate

internal fun isWeekend(date: LocalDate): Boolean = date.dayOfWeek.value >= 6

internal enum class HolidayAdjustmentField { SOURCE, TARGET }

@Composable
fun HolidayAdjustmentsScreen(viewModel: HolidayAdjustmentsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 用户主动操作（下载对照表 / 恢复已忽略）的结果提示。列表里的行既要显示日期也要显示
    // 来源，所以 pendingDelete 带整行而不是只带规则。
    val message by viewModel.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        if (message.isNotBlank()) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }
    var sourceDate by remember { mutableStateOf<LocalDate?>(null) }
    var targetDate by remember { mutableStateOf<LocalDate?>(null) }
    var pickingField by remember { mutableStateOf<HolidayAdjustmentField?>(null) }
    var pendingDelete by remember { mutableStateOf<ManualDayCopyRow?>(null) }
    var holidayConfirmTarget by remember { mutableStateOf<LocalDate?>(null) }

    // 学期日期就绪后初始化两个日期；换学期或重新导入校历导致范围变化时，把越界值拉回范围。
    LaunchedEffect(state.canEdit, state.semesterStartDate, state.semesterEndDate) {
        val start = state.semesterStartDate ?: return@LaunchedEffect
        val end = state.semesterEndDate ?: return@LaunchedEffect
        val currentSource = sourceDate
        if (currentSource == null || currentSource < start || currentSource > end) sourceDate = start
        val resolvedSource = sourceDate ?: start
        val currentTarget = targetDate
        if (currentTarget == null || currentTarget < start || currentTarget > end ||
            currentTarget == resolvedSource
        ) {
            targetDate = nextDateWithinSemester(resolvedSource, end)
        }
    }

    if (!state.canEdit) {
        HolidayAdjustmentsEmptyState()
        return
    }

    val start = state.semesterStartDate!!
    val end = state.semesterEndDate!!
    val sourceCourseCount = viewModel.sourceCourseCount(state, sourceDate)
    val targetIsHiddenWeekend = !state.showWeekend && targetDate?.let(::isWeekend) == true

    // 规则行按来源分成「自动补的 / 我加的」两组：来源由组标题声明，逐行不再挂徽标。
    val groups = groupDayCopyRows(viewModel.rowsFor(state))
    val autoRows = groups.firstOrNull { it.isAuto }?.rows.orEmpty()
    val manualRows = groups.firstOrNull { !it.isAuto }?.rows.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBg)
            .verticalScroll(rememberScrollState())
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            // 单页连续白面板：自动 / 手动两段之间只用分隔线，不各自套一张卡片。
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = PanelBg,
                shape = RoundedCornerShape(14.dp)
            ) {
                Column {
                    AutoDayCopySection(
                        state = state,
                        rows = autoRows,
                        onToggle = viewModel::setAutoDayCopyEnabled,
                        onRecompute = viewModel::recalculateAutoCopies,
                        onDownloadTable = viewModel::downloadMakeupTable,
                        onRestoreSuppressed = viewModel::restoreSuppressedAutoRules,
                        onDeleteRow = { pendingDelete = it }
                    )

                    Divider()

                    Column(modifier = Modifier.padding(16.dp)) {
                        SectionTitle(
                            accent = ManualAccent,
                            title = "手动调休",
                            subtitle = "自己指定把哪天的课挪到哪天"
                        )

                        // 标签单独一行、日期块单独一行：箭头才会落在日期块的中线上。
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                            Text(
                                "原日期",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(DateArrowWidth))
                            Text(
                                "目标日期",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            DateChip(
                                date = sourceDate,
                                modifier = Modifier.weight(1f),
                                onClick = { pickingField = HolidayAdjustmentField.SOURCE }
                            )
                            DateArrow()
                            DateChip(
                                date = targetDate,
                                modifier = Modifier.weight(1f),
                                onClick = { pickingField = HolidayAdjustmentField.TARGET }
                            )
                        }

                        Text(
                            "将复制 $sourceCourseCount 节课程；原日期课程不会移除",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                        if (targetIsHiddenWeekend) {
                            Text(
                                "目标日是周末；请开启「显示周末」后在首页查看该日课程。",
                                color = WarningText,
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                        Button(
                            onClick = {
                                val resolvedTarget = targetDate ?: return@Button
                                // 目标日是法定假日时先确认：该日首页仍显示「休」，但课程会照常追加。
                                if (viewModel.isHoliday(state, resolvedTarget)) {
                                    holidayConfirmTarget = resolvedTarget
                                } else {
                                    submitRule(viewModel, state, sourceDate, resolvedTarget, context)
                                }
                            },
                            enabled = sourceDate != null && targetDate != null,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ManualAccent),
                            modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
                        ) {
                            Text("添加调课", fontSize = 15.sp, modifier = Modifier.padding(vertical = 4.dp))
                        }

                        if (manualRows.isEmpty() && autoRows.isEmpty()) {
                            Text(
                                "暂未添加调休调课",
                                color = TextSecondary,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                            )
                        }
                        if (manualRows.isNotEmpty()) {
                            GroupHeader(
                                title = MANUAL_ROW_GROUP_TITLE,
                                count = manualRows.size,
                                accent = ManualAccent
                            )
                            manualRows.forEachIndexed { index, row ->
                                if (index > 0) Divider()
                                RuleRow(row = row, onDelete = { pendingDelete = row })
                            }
                        }
                    }

                    // 长按卡片删掉的卡片在这里恢复：与调休规则同属「我手动改动了课表」的叠加层。
                    if (state.hiddenItems.isNotEmpty()) {
                        Divider()
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("已隐藏的卡片", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    "${state.hiddenItems.size} 张",
                                    color = TextFaint,
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            state.hiddenItems.forEachIndexed { index, item ->
                                if (index > 0) Divider()
                                HiddenCardRow(
                                    label = item.label,
                                    // 失效记录是有意保留的（课再排回来依然隐藏），但要标出来。
                                    missingFromSchedule = !item.existsInSchedule,
                                    onRestore = { viewModel.restoreHiddenCard(item.id) }
                                )
                            }
                            TextButton(
                                onClick = viewModel::restoreAllHiddenCards,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("全部恢复", color = Accent, fontSize = 15.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    pickingField?.let { field ->
        HolidayAdjustmentDatePickerDialog(
            initialDate = if (field == HolidayAdjustmentField.SOURCE) sourceDate else targetDate,
            start = start,
            end = end,
            onDismiss = { pickingField = null },
            onConfirm = { picked ->
                if (field == HolidayAdjustmentField.SOURCE) {
                    sourceDate = picked
                    // 原日期改到与目标日期同一天时把目标日期顺延，避免保存时才报错。
                    if (targetDate == picked) targetDate = nextDateWithinSemester(picked, end)
                } else {
                    targetDate = picked
                }
                pickingField = null
            }
        )
    }

    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            // 显式指定容器色与文字色：不传会落到 Material3 的 surfaceContainerHigh，
            // 而本应用主题是 darkColorScheme，其默认值是深灰 #2B2930（黑底白字）。
            containerColor = PanelBg,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            // 自动规则不能只说「不再显示」：它还得说清「不会再被自动补回来」以及怎么找回，
            // 因为删除抑制正是「默认生效」模式下用户唯一的纠错手段。
            title = { Text(if (row.origin.isAuto) "忽略这条自动调休？" else "删除调休？") },
            text = {
                Text(
                    if (row.origin.isAuto) {
                        "${row.rule.sourceDate} 复制到 ${row.rule.targetDate} 的课程将不再显示，" +
                            "并且不会再被自动补回来（可用下方「恢复已忽略」找回）。"
                    } else {
                        "${row.rule.sourceDate} 复制到 ${row.rule.targetDate} 的课程将不再显示。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (row.origin.isAuto) {
                        viewModel.deleteAutoRule(row.rule.id)
                    } else {
                        viewModel.deleteRule(row.rule)
                    }
                    pendingDelete = null
                }) { Text(if (row.origin.isAuto) "忽略" else "删除", color = DangerText) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }

    holidayConfirmTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { holidayConfirmTarget = null },
            containerColor = PanelBg,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text("目标日为节假日") },
            text = { Text("该日首页仍会显示「休」，但课程会正常追加到该日期。") },
            confirmButton = {
                TextButton(onClick = {
                    submitRule(viewModel, state, sourceDate, target, context)
                    holidayConfirmTarget = null
                }) { Text("继续添加") }
            },
            dismissButton = {
                TextButton(onClick = { holidayConfirmTarget = null }) { Text("取消") }
            }
        )
    }
}

private fun submitRule(
    viewModel: HolidayAdjustmentsViewModel,
    state: HolidayAdjustmentsUiState,
    sourceDate: LocalDate?,
    targetDate: LocalDate,
    context: android.content.Context
) {
    val error = viewModel.addRule(state, sourceDate, targetDate)
    Toast.makeText(context, error ?: "调课已添加", Toast.LENGTH_SHORT).show()
}

/**
 * 分区标题左侧的身份色条：自动走蓝、手动走橙。
 *
 * 颜色即身份——不必读文字就知道这是哪一类，也不会像行内徽标那样被长文本挤变形。
 */
@Composable
private fun AccentRail(color: Color, height: Dp = 20.dp) {
    Box(
        modifier = Modifier
            .width(3.dp)
            .height(height)
            .clip(RoundedCornerShape(2.dp))
            .background(color)
    )
}

/** 分区标题：[accent] 色条 + 标题 + 副标题，右侧可挂一个控件（自动分区挂开关）。 */
@Composable
private fun SectionTitle(
    accent: Color,
    title: String,
    subtitle: String,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AccentRail(accent)
        Text(
            title,
            color = TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 10.dp).weight(1f)
        )
        trailing?.invoke()
    }
    Text(
        subtitle,
        color = TextSecondary,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        modifier = Modifier.padding(top = 6.dp, start = 13.dp)
    )
}

/**
 * 组标题：来源由它声明，逐行不再挂徽标。
 *
 * 徽标从行内挪到这里，是因为它在长日期文本旁边会被挤成「自 / 动 / · / 教 / 务」一个字一行，
 * 并把整行撑高到日期行看着偏上。分组的纯逻辑见 `ManualDayCopyGroupTest`。
 */
@Composable
private fun GroupHeader(title: String, count: Int, accent: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AccentRail(accent, height = 13.dp)
        Text(
            title,
            color = TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 8.dp)
        )
        Text(
            "$count 条",
            color = TextFaint,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

/** 日期块。标签由调用方单独成行渲染——标签留在这一列里会把箭头挤到整列的中线上。 */
@Composable
private fun DateChip(
    date: LocalDate?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = ManualChipBg,
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(
            text = date?.toString().orEmpty(),
            color = ManualChipText,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
        )
    }
}

/** 日期之间的箭头：固定宽度 + 居中，与两侧日期块在同一个 Row 里垂直居中。 */
@Composable
private fun DateArrow() {
    Text(
        text = "→",
        color = TextSecondary,
        fontSize = 18.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(DateArrowWidth)
    )
}

/**
 * 「已隐藏的卡片」的一行。
 *
 * 与 [RuleRow] 同构，只是右侧动作从「删除」换成「恢复」——语义相反，颜色也随之从警示色换成强调蓝。
 */
@Composable
private fun HiddenCardRow(
    label: String,
    missingFromSchedule: Boolean,
    onRestore: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (missingFromSchedule) {
                Text("已不在课表中", color = TextSecondary, fontSize = 13.sp)
            }
        }
        Text(
            text = "恢复",
            color = Accent,
            fontSize = 14.sp,
            modifier = Modifier.clickable(onClick = onRestore)
        )
    }
}

/**
 * 「自动调休」区块。
 *
 * 开关之外只有几个文字按钮，**没有任何确认弹窗**：需求是「打开就直接生效、不要求用户点确认」，
 * 而且这一页的 AlertDialog 数量被源码契约测试钉死为 2
 * （见 `HolidayAdjustmentsUiContractTest.dialogsPinLightContainerColorsInsteadOfInheritingTheDarkTheme`）。
 */
@Composable
private fun AutoDayCopySection(
    state: HolidayAdjustmentsUiState,
    rows: List<ManualDayCopyRow>,
    onToggle: (Boolean) -> Unit,
    onRecompute: () -> Unit,
    onDownloadTable: () -> Unit,
    onRestoreSuppressed: () -> Unit,
    onDeleteRow: (ManualDayCopyRow) -> Unit
) {
    Column(modifier = Modifier.padding(16.dp)) {
        SectionTitle(
            accent = Accent,
            title = "自动调休",
            subtitle = "系统按学校通知补课，不需要你操作",
            trailing = { Switch(checked = state.autoDayCopyEnabled, onCheckedChange = onToggle) }
        )

        // 只说结果，不说数据从哪来——来源由下面那条「可能不准」的警告负责，且只在没有对照表时出现。
        // 原来是「已启用 · 补了几天 · 对照表 rev N」全挤在一个中间点串里。
        Text(
            text = if (state.autoDayCopyEnabled) {
                autoResultText(state)
            } else {
                "未启用：只有你手动添加的调课生效"
            },
            color = TextSecondary,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            modifier = Modifier.padding(top = 10.dp, start = 13.dp)
        )
        if (!state.autoDayCopyEnabled) return@Column

        // 只有「这批规则不是学校通知来的」才需要说明——那是用户唯一能据此判断准不准的信息。
        // 有对照表时**什么都不显示**：rev 与更新日期是维护者概念，用户读不懂，也做不了任何决定。
        if (!state.remoteTableReady) {
            Text(
                "学校通知尚未录入，以下为推算结果，可能不准",
                color = WarningText,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 4.dp, start = 13.dp)
            )
        }
        if (isMakeupTableStale(state.remoteUpdatedAt)) {
            Text(
                "对照表可能已过期，可点下方按钮或到首页下拉刷新同步。",
                color = WarningText,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 4.dp, start = 13.dp)
            )
        }
        state.autoNotices.forEach { notice ->
            Text(
                "· $notice",
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 4.dp, start = 13.dp)
            )
        }

        // 自动补出来的那几天：组标题与色条都走本分区的身份色。
        if (rows.isNotEmpty()) {
            GroupHeader(title = AUTO_ROW_GROUP_TITLE, count = rows.size, accent = Accent)
            rows.forEachIndexed { index, row ->
                if (index > 0) Divider()
                RuleRow(row = row, onDelete = { onDeleteRow(row) })
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 只有「重新计算」永远是纯本地的；下载对照表是这一页唯一的联网动作，且只由点击触发。
            TextButton(onClick = onRecompute) { Text("重新计算", color = Accent, fontSize = 14.sp) }
            if (!state.remoteTableReady) {
                TextButton(onClick = onDownloadTable) { Text("下载教务对照表", color = Accent, fontSize = 14.sp) }
            }
            if (state.suppressedAutoCount > 0) {
                TextButton(onClick = onRestoreSuppressed) {
                    Text("恢复已忽略（${state.suppressedAutoCount}）", color = Accent, fontSize = 14.sp)
                }
            }
        }
    }
}

/**
 * 开关打开时的结果文案：只讲补了什么。
 *
 * 数据从哪来不在这里说——那是"可能不准"那条警告的事，而且只在没有对照表时才出现。
 * 对照表的版本号与更新日期**刻意不展示**：`rev 1` 是维护者的版本控制概念，用户既读不懂，
 * 也无法据此做任何决定；真正对用户有用的只有"这批补课可不可信"。
 */
private fun autoResultText(state: HolidayAdjustmentsUiState): String =
    if (state.autoRules.isEmpty()) "暂无自动补课" else "已自动补 ${state.autoRules.size} 天"

/**
 * 对照表是否可能过期：超过 120 天没更新就提示一次。
 *
 * 判不出来（维护者没填 updatedAt）时按「不提示」处理——宁可不报，也不要天天误报。
 */
internal fun isMakeupTableStale(updatedAt: String, today: LocalDate = LocalDate.now()): Boolean {
    val parsed = runCatching { LocalDate.parse(updatedAt.trim()) }.getOrNull() ?: return false
    return parsed.plusDays(120).isBefore(today)
}

/**
 * 一条调休规则：日期一行，复制节数一行，删除在最右侧。
 *
 * 日期**必须独占一行**。整行可用的宽度是「面板宽 − 复制节数 − 删除」，而
 * `2026-10-06 → 2026-09-20` 在 15sp 下要占约 168dp，三者挤在一行里超出面板宽度，日期会被
 * 压到折行、箭头吊在行尾（真机实测过）。日期独占一行后既不会折行，箭头也必然与日期同基线。
 */
@Composable
private fun RuleRow(row: ManualDayCopyRow, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "${row.rule.sourceDate} → ${row.rule.targetDate}",
                color = RuleDateText,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Text(
                "复制 ${row.courseCount} 节课",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Text(
            "删除",
            color = DangerText,
            fontSize = 14.sp,
            modifier = Modifier
                .clickable(onClick = onDelete)
                .padding(start = 12.dp, top = 6.dp, bottom = 6.dp)
        )
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(color = DividerColor)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HolidayAdjustmentDatePickerDialog(
    initialDate: LocalDate?,
    start: LocalDate,
    end: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit
) {
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = localDateToUtcMillis(initialDate ?: start),
        selectableDates = remember(start, end) { SemesterRangeSelectableDates(start, end) }
    )
    val pickerColors = holidayAdjustmentDatePickerColors()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        // 与两个 AlertDialog 同理：默认容器色是主题里未覆盖的 surfaceContainerHigh（深灰）。
        // 弹窗本体与内部日历共用同一套颜色，只改其中一处会留下深色的另一半。
        colors = pickerColors,
        confirmButton = {
            TextButton(onClick = {
                pickerState.selectedDateMillis
                    ?.let { millis -> onConfirm(utcMillisToLocalDate(millis)) }
                    ?: onDismiss()
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    ) {
        DatePicker(state = pickerState, colors = pickerColors)
    }
}

/**
 * 日期选择器的配色。
 *
 * 这一整套都必须显式给：`DatePicker` 内部各区域（标题区、星期标签、日号、选中态、今日边框）
 * 各有自己的槽位，只改容器色会留下深色的标题与深色的日号。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun holidayAdjustmentDatePickerColors() = DatePickerDefaults.colors(
    containerColor = PanelBg,
    titleContentColor = TextSecondary,
    headlineContentColor = TextPrimary,
    weekdayContentColor = TextSecondary,
    subheadContentColor = TextSecondary,
    navigationContentColor = TextPrimary,
    yearContentColor = TextPrimary,
    currentYearContentColor = Accent,
    selectedYearContentColor = OnAccent,
    selectedYearContainerColor = Accent,
    dayContentColor = TextPrimary,
    selectedDayContainerColor = Accent,
    selectedDayContentColor = OnAccent,
    todayContentColor = Accent,
    todayDateBorderColor = Accent,
    dividerColor = DividerColor
)

/** 选择范围限定在当前学期起止日期内，与小程序 `<picker mode="date">` 的 start/end 一致。 */
private class SemesterRangeSelectableDates(
    private val start: LocalDate,
    private val end: LocalDate
) : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean {
        val date = utcMillisToLocalDate(utcTimeMillis)
        return !date.isBefore(start) && !date.isAfter(end)
    }

    override fun isSelectableYear(year: Int): Boolean = year in start.year..end.year
}

@Composable
private fun HolidayAdjustmentsEmptyState() {
    Box(
        modifier = Modifier.fillMaxSize().background(PageBg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("暂未找到当前学期课表", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(
                "导入当前学期课程后，才能添加调休调课。",
                color = TextSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}
