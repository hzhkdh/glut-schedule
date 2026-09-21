package com.glut.schedule.ui.pages

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
private val DividerColor = Color(0xFFEDE8DE)
private val Accent = Color(0xFF3F7DF6)
private val DateChipBg = Color(0xFFF1F5FD)
private val DateChipText = Color(0xFF2F6FE9)
private val WarningText = Color(0xFFB45843)
private val DangerText = Color(0xFFDC2626)

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
    var sourceDate by remember { mutableStateOf<LocalDate?>(null) }
    var targetDate by remember { mutableStateOf<LocalDate?>(null) }
    var pickingField by remember { mutableStateOf<HolidayAdjustmentField?>(null) }
    var pendingDelete by remember { mutableStateOf<ManualDayCopyRule?>(null) }
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBg)
            .verticalScroll(rememberScrollState())
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            // 单页连续白面板：说明 / 编辑 / 规则列表三段之间只用分隔线，不再各自套一张卡片。
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = PanelBg,
                shape = RoundedCornerShape(14.dp)
            ) {
                Column {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("复制整天课程", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "原日期课程会保留，目标日期会追加相同课程。仅作用于当前学期。",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }

                    Divider()

                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("新增调课", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            DateField(
                                label = "原日期",
                                date = sourceDate,
                                modifier = Modifier.weight(1f),
                                onClick = { pickingField = HolidayAdjustmentField.SOURCE }
                            )
                            Text("→", color = Color(0xFF98A0AF), fontSize = 18.sp)
                            DateField(
                                label = "目标日期",
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
                            colors = ButtonDefaults.buttonColors(containerColor = Accent),
                            modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
                        ) {
                            Text("添加调课", fontSize = 15.sp, modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }

                    Divider()

                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("已添加的调课", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            if (state.rules.isNotEmpty()) {
                                Text(
                                    "${state.rules.size} 条",
                                    color = Color(0xFF98A0AF),
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        val rows = viewModel.rowsFor(state)
                        if (rows.isEmpty()) {
                            Text(
                                "暂未添加调休调课",
                                color = TextSecondary,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                            )
                        }
                        rows.forEachIndexed { index, row ->
                            if (index > 0) Divider()
                            RuleRow(row = row, onDelete = { pendingDelete = row.rule })
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

    pendingDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除调课？") },
            text = { Text("${rule.sourceDate} 复制到 ${rule.targetDate} 的课程将不再显示。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteRule(rule)
                    pendingDelete = null
                }) { Text("删除", color = DangerText) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }

    holidayConfirmTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { holidayConfirmTarget = null },
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

@Composable
private fun DateField(
    label: String,
    date: LocalDate?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(modifier = modifier) {
        Text(label, color = TextSecondary, fontSize = 12.sp)
        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).clickable(onClick = onClick),
            color = DateChipBg,
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(
                text = date?.toString().orEmpty(),
                color = DateChipText,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
            )
        }
    }
}

@Composable
private fun RuleRow(row: ManualDayCopyRow, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "${row.rule.sourceDate} → ${row.rule.targetDate}",
                color = Color(0xFF202633),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
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
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                pickerState.selectedDateMillis
                    ?.let { millis -> onConfirm(utcMillisToLocalDate(millis)) }
                    ?: onDismiss()
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    ) {
        DatePicker(state = pickerState)
    }
}

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
