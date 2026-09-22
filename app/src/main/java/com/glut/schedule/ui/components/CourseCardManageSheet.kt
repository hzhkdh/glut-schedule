package com.glut.schedule.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glut.schedule.data.model.CourseBlock
import com.glut.schedule.data.model.HiddenCardScope
import com.glut.schedule.data.model.HiddenCourseRule
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.hiddenCourseKey
import com.glut.schedule.data.model.hiddenRuleScopeText

private val PanelBackground = Color(0xFFFFFEFB)
private val TextPrimary = Color(0xFF141821)
private val TextSecondary = Color(0xFF667085)
private val DividerColor = Color(0xFFEDE9E2)
private val DangerColor = Color(0xFFB42318)

/**
 * 长按课程卡片后弹出的「卡片管理」。
 *
 * 一屏放下改色与删除两件事，颜色区直接复用设置页那一套 [CourseColorPaletteSection]。
 *
 * 删除分三档，按钮上写清各自的范围，避免用户选错：
 * - 「本周这次」只去掉当前查看的这一周，其他周照常；
 * - 「这个课次」去掉这个课次的所有周，同门课其他时段不受影响；
 * - 「整门课」这门课本学期的全部卡片（键只看课程名，同名课程会一并隐藏）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CourseCardManageSheet(
    block: CourseBlock,
    /** 完整课表，只用于把范围文案里的课程名与周次写准确。 */
    courses: List<ScheduleCourse>,
    currentWeekNumber: Int,
    onDismiss: () -> Unit,
    onSelectColor: (String) -> Unit,
    onRestoreColor: () -> Unit,
    onDelete: (HiddenCardScope) -> Unit
) {
    // 允许半展开：弹出时先停在屏幕一半处（露出颜色区），用户可以拖拽柄继续往上拉到全部内容。
    // Material3 1.4.0 的半展开锚点是内部算的，公开 API 无法指定「正好停在卡片颜色那一行」，
    // 但内容高于可用高度时默认就停在屏幕一半——正是想要的效果。
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var showAdvanced by remember { mutableStateOf(false) }

    // 下面三条规则只用来生成按钮上的范围文案，本身不落库；真正写入的规则由上层按同样口径构造。
    val courseKey = hiddenCourseKey(block.course.id, block.course.title)
    val occurrenceRule = remember(block) {
        HiddenCourseRule(
            scope = HiddenCardScope.OCCURRENCE,
            courseKey = courseKey,
            dayOfWeek = block.occurrence.dayOfWeek,
            startSection = block.occurrence.startSection,
            endSection = block.occurrence.endSection
        )
    }
    val weekRule = remember(occurrenceRule, currentWeekNumber) {
        occurrenceRule.copy(scope = HiddenCardScope.WEEK, week = currentWeekNumber)
    }
    val courseRule = remember(courseKey) {
        HiddenCourseRule(scope = HiddenCardScope.COURSE, courseKey = courseKey)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PanelBackground,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column {
                Text(
                    text = block.course.title,
                    color = TextPrimary,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = cardSubtitle(block),
                    color = TextSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Text("卡片颜色", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            CourseColorPaletteSection(
                onSelect = onSelectColor,
                onAdvanced = { showAdvanced = true },
                onRestore = onRestoreColor
            )

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(DividerColor))

            DeleteRow(
                label = "删除本周这次",
                scopeText = hiddenRuleScopeText(weekRule, courses),
                onClick = { onDelete(HiddenCardScope.WEEK) }
            )
            DeleteRow(
                label = "删除这个课次",
                scopeText = hiddenRuleScopeText(occurrenceRule, courses),
                onClick = { onDelete(HiddenCardScope.OCCURRENCE) }
            )
            DeleteRow(
                label = "删除整门课",
                scopeText = hiddenRuleScopeText(courseRule, courses),
                onClick = { onDelete(HiddenCardScope.COURSE) }
            )

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            ) {
                Text("取消", color = TextSecondary, fontSize = 16.sp)
            }
        }
    }

    // 高级调色叠在管理弹层之上；确认后同样走 onSelectColor，颜色最终写进同一份覆盖表。
    if (showAdvanced) {
        AdvancedColorSheet(
            initialColor = block.course.colorHex,
            onDismiss = { showAdvanced = false },
            onConfirm = { color ->
                showAdvanced = false
                onSelectColor(color)
            }
        )
    }
}

/** 「周二 3-4 节 · 04105 · 张老师」，空字段不渲染。 */
private fun cardSubtitle(block: CourseBlock): String {
    val weekday = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        .getOrNull(block.occurrence.dayOfWeek - 1) ?: "周?"
    val parts = mutableListOf("$weekday ${block.occurrence.startSection}-${block.occurrence.endSection} 节")
    if (block.course.room.isNotBlank()) parts += block.course.room
    if (block.course.teacher.isNotBlank()) parts += block.course.teacher
    return parts.joinToString(" · ")
}

@Composable
private fun DeleteRow(label: String, scopeText: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.DeleteOutline,
            contentDescription = null,
            tint = DangerColor,
            modifier = Modifier.size(20.dp)
        )
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(label, color = DangerColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            // 范围写清楚，否则三档之间用户只能靠猜。
            Text(scopeText, color = TextSecondary, fontSize = 13.sp)
        }
    }
}

/**
 * 这张卡是否来自「调休调课」规则生成的一次性副本。
 *
 * 副本是渲染期派生对象，`manualCopyBlocksForWeek` 同时改过它的星期与周次，
 * 对它做「本周这次」在语义上没法与「隐藏源周的原课次」区分开；要删副本请去
 * 「设置 → 调休调课」删规则。所以调用侧对这类卡片直接不弹管理弹层。
 */
internal fun CourseBlock.isManualCopyBlock(): Boolean =
    occurrence.id.contains("-manual-copy-")
