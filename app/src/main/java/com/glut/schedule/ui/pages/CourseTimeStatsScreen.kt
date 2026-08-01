package com.glut.schedule.ui.pages

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glut.schedule.data.model.CourseTimeDimension
import com.glut.schedule.data.model.CourseTimeStatsItem
import com.glut.schedule.data.model.CourseTimeStatsUnavailableReason
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

private val StatsPageBg = Color(0xFFF6F4EF)
private val StatsCardBg = Color(0xFFFFFEFB)
private val StatsTextPrimary = Color(0xFF141821)
private val StatsTextSecondary = Color(0xFF667085)
private val StatsAccent = Color(0xFF245B78)
private val StatsTrack = Color(0xFFE5E7EB)
private val StatsWarningBg = Color(0xFFFFF7E6)
private val StatsWarningText = Color(0xFF9A6700)

@Composable
fun CourseTimeStatsScreen(
    viewModel: CourseTimeStatsViewModel,
    onImportClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(StatsPageBg),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 14.dp,
            top = 12.dp,
            end = 14.dp,
            bottom = 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            FiltersCard(
                state = state,
                onScopeSelected = viewModel::selectScope,
                onDimensionSelected = viewModel::selectDimension
            )
        }

        if (state.isAllSemesters && state.stats.coverage.downloadedSemesters > 0) {
            item {
                CoverageBanner(
                    eligible = state.stats.coverage.eligibleSemesters,
                    downloaded = state.stats.coverage.downloadedSemesters,
                    excludedLabels = state.stats.coverage.excludedSemesters.map { it.semesterLabel }
                )
            }
        }

        if (state.semesterOptions.isEmpty()) {
            item {
                StatsEmptyState(
                    title = "尚未导入课表",
                    detail = "导入当前学期课表后，即可按课程、教室和教师查看计划课时。",
                    actionLabel = "前往导入课表",
                    onAction = onImportClick
                )
            }
        } else if (
            state.stats.coverage.downloadedSemesters > 0 &&
            state.stats.coverage.eligibleSemesters == 0
        ) {
            val reason = state.stats.coverage.excludedSemesters.firstOrNull()?.reason
            item {
                StatsEmptyState(
                    title = "该学期暂时无法统计",
                    detail = unavailableReasonText(reason),
                    actionLabel = "前往学期管理",
                    onAction = onImportClick
                )
            }
        } else if (state.stats.items.isEmpty()) {
            item {
                StatsEmptyState(
                    title = "该范围暂无排课",
                    detail = "本地快照已完整读取，但没有可计入计划课时的课程。",
                    actionLabel = null,
                    onAction = onImportClick
                )
            }
        } else {
            item {
                DistributionCard(
                    totalMinutes = state.stats.totalMinutes,
                    distribution = state.stats.distribution
                )
            }
        }
    }
}

@Composable
private fun FiltersCard(
    state: CourseTimeStatsUiState,
    onScopeSelected: (String) -> Unit,
    onDimensionSelected: (CourseTimeDimension) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = StatsCardBg,
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "统计维度",
                    color = StatsTextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                ScopeDropdown(state, onScopeSelected)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(StatsTrack)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                CourseTimeDimension.entries.forEach { dimension ->
                    val selected = dimension == state.dimension
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onDimensionSelected(dimension) },
                        color = if (selected) Color.White else Color.Transparent,
                        shape = RoundedCornerShape(9.dp),
                        shadowElevation = if (selected) 1.dp else 0.dp
                    ) {
                        Text(
                            dimension.title,
                            color = if (selected) StatsAccent else StatsTextSecondary,
                            fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.padding(vertical = 9.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScopeDropdown(
    state: CourseTimeStatsUiState,
    onScopeSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = if (state.isAllSemesters) {
        "全部学期"
    } else {
        state.semesterOptions.firstOrNull { it.id == state.selectedScopeId }?.label ?: "选择学期"
    }

    Box {
        Surface(
            modifier = Modifier.clickable { expanded = true },
            color = StatsPageBg,
            shape = RoundedCornerShape(10.dp)
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(selectedLabel, color = StatsTextPrimary, fontSize = 13.sp)
                Icon(
                    Icons.Outlined.KeyboardArrowDown,
                    contentDescription = "选择学期",
                    tint = StatsTextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            // 页面固定使用浅色统计卡片，因此弹层也显式使用同一容器色，避免系统深色主题造成深底深字。
            containerColor = StatsCardBg
        ) {
            state.semesterOptions.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            option.label + if (option.statisticsReady) "" else " · 需重新下载",
                            color = if (option.statisticsReady) StatsTextPrimary else StatsWarningText
                        )
                    },
                    onClick = {
                        onScopeSelected(option.id)
                        expanded = false
                    }
                )
            }
            if (state.semesterOptions.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text("全部学期", color = StatsTextPrimary) },
                    onClick = {
                        onScopeSelected(CourseTimeStatsViewModel.ALL_SCOPE_ID)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun CoverageBanner(
    eligible: Int,
    downloaded: Int,
    excludedLabels: List<String>
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (eligible == downloaded) StatsCardBg else StatsWarningBg,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                "已统计 $eligible/$downloaded 个学期",
                color = if (eligible == downloaded) StatsTextSecondary else StatsWarningText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (excludedLabels.isNotEmpty()) {
                Text(
                    "${excludedLabels.joinToString("、")} 需重新下载后才能加入汇总",
                    color = StatsWarningText,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun DistributionCard(
    totalMinutes: Int,
    distribution: List<CourseTimeStatsItem>
) {
    var selectedKey by remember(distribution.map { it.key }) { mutableStateOf<String?>(null) }
    val selectedItem = distribution.firstOrNull { it.key == selectedKey }
    val center = buildCourseTimeStatsChartCenter(totalMinutes, selectedItem)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = StatsCardBg,
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "总时长分布",
                    color = StatsTextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text("${distribution.size} 项", color = StatsTextSecondary, fontSize = 12.sp)
            }
            DonutChart(
                distribution = distribution,
                selectedKey = selectedKey,
                center = center,
                onSelect = { selectedKey = it },
                onReset = { selectedKey = null },
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .widthIn(max = 260.dp)
                    .fillMaxWidth()
            )
            CategoryGrid(
                items = distribution,
                selectedKey = selectedKey,
                onSelect = { selectedKey = it }
            )
        }
    }
}

@Composable
private fun DonutChart(
    distribution: List<CourseTimeStatsItem>,
    selectedKey: String?,
    center: CourseTimeStatsChartCenter,
    onSelect: (String) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .semantics {
                    contentDescription = "计划课时环形图，共 ${distribution.size} 项"
                }
                .pointerInput(distribution, selectedKey) {
                    detectTapGestures { tap ->
                        val selectedStroke = 34.dp.toPx()
                        val radius = (min(size.width, size.height).toFloat() - selectedStroke) / 2f
                        val index = findCourseTimeDonutSegment(
                            tapX = tap.x,
                            tapY = tap.y,
                            width = size.width.toFloat(),
                            height = size.height.toFloat(),
                            innerRadius = radius - selectedStroke / 2f,
                            outerRadius = radius + selectedStroke / 2f,
                            shares = distribution.map { it.share }
                        )
                        if (index != null) onSelect(distribution[index].key)
                    }
                }
        ) {
            val selectedStroke = 34.dp.toPx()
            val normalStroke = 28.dp.toPx()
            val radius = (size.minDimension - selectedStroke) / 2f
            val arcTopLeft = Offset(
                x = (size.width - radius * 2f) / 2f,
                y = (size.height - radius * 2f) / 2f
            )
            val arcSize = Size(radius * 2f, radius * 2f)
            var startAngle = -90f

            drawCircle(
                color = StatsTrack.copy(alpha = 0.72f),
                radius = radius,
                style = Stroke(width = normalStroke)
            )
            distribution.forEach { item ->
                val sweepAngle = (item.share * 360.0).toFloat()
                if (sweepAngle > 0f) {
                    val selected = item.key == selectedKey
                    drawArc(
                        color = colorFromHex(item.colorHex).copy(
                            alpha = if (selectedKey == null || selected) 1f else 0.34f
                        ),
                        startAngle = startAngle,
                        sweepAngle = sweepAngle,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(
                            width = if (selected) selectedStroke else normalStroke,
                            cap = StrokeCap.Butt
                        )
                    )
                }
                startAngle += sweepAngle
            }
        }

        Column(
            modifier = Modifier
                .size(132.dp)
                .clip(RoundedCornerShape(66.dp))
                .clickable(onClick = onReset)
                .semantics {
                    contentDescription =
                        "${center.label}，${center.timeAccessibilityText}，${center.shareText}"
                }
                .padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                center.label,
                color = StatsTextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                center.timeText,
                color = StatsTextPrimary,
                fontSize = 20.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(top = 3.dp)
            )
            Text(
                center.shareText,
                color = StatsAccent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun CategoryGrid(
    items: List<CourseTimeStatsItem>,
    selectedKey: String?,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach { item ->
                    CategoryCard(
                        item = item,
                        selected = item.key == selectedKey,
                        onClick = { onSelect(item.key) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (rowItems.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CategoryCard(
    item: CourseTimeStatsItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val itemColor = colorFromHex(item.colorHex)
    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 76.dp)
            .semantics {
                contentDescription =
                    "${item.label}，${formatStatsMinutesAccessible(item.minutes)}，${formatShare(item.share)}"
            },
        color = itemColor.copy(alpha = 0.09f),
        shape = RoundedCornerShape(13.dp),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) itemColor else itemColor.copy(alpha = 0.18f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 5.dp)
                    .size(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(itemColor)
            )
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Text(
                    item.label,
                    color = StatsTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${formatStatsMinutes(item.minutes)} · ${formatShare(item.share)}",
                    color = StatsTextPrimary.copy(alpha = 0.78f),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    // 紧凑 h/m 表达可在双列卡片内完整显示，避免时间被拆成两行。
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun StatsEmptyState(
    title: String,
    detail: String,
    actionLabel: String?,
    onAction: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = StatsCardBg,
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, color = StatsTextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(
                detail,
                color = StatsTextSecondary,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
            if (actionLabel != null) {
                TextButton(onClick = onAction, modifier = Modifier.padding(top = 8.dp)) {
                    Text(actionLabel, color = StatsAccent)
                }
            }
        }
    }
}

private fun unavailableReasonText(reason: CourseTimeStatsUnavailableReason?): String = when (reason) {
    CourseTimeStatsUnavailableReason.MISSING_MAX_WEEK -> "学期最大周次缺失，请重新下载该学期课表。"
    CourseTimeStatsUnavailableReason.MISSING_CLASS_PERIODS -> "历史作息或节次时间不完整，请重新下载该学期课表。"
    CourseTimeStatsUnavailableReason.INVALID_WEEK_TEXT -> "课程周次信息无法可靠解析，请重新下载该学期课表。"
    null -> "本地课表数据不完整，请重新下载后再试。"
}

internal fun formatStatsMinutes(minutes: Int): String {
    val hours = minutes / 60
    val remaining = minutes % 60
    return when {
        hours == 0 -> "${remaining}m"
        remaining == 0 -> "${hours}h"
        else -> "${hours}h${remaining}m"
    }
}

internal fun formatStatsMinutesAccessible(minutes: Int): String {
    val hours = minutes / 60
    val remaining = minutes % 60
    return when {
        hours == 0 -> "$remaining 分钟"
        remaining == 0 -> "$hours 小时"
        else -> "$hours 小时 $remaining 分"
    }
}

private fun formatShare(share: Double): String =
    String.format(Locale.ROOT, "%.1f%%", share * 100.0)

internal data class CourseTimeStatsChartCenter(
    val label: String,
    val timeText: String,
    val timeAccessibilityText: String,
    val shareText: String
)

internal fun buildCourseTimeStatsChartCenter(
    totalMinutes: Int,
    selectedItem: CourseTimeStatsItem?
): CourseTimeStatsChartCenter = if (selectedItem == null) {
    CourseTimeStatsChartCenter(
        label = "总计划课时",
        timeText = formatStatsMinutes(totalMinutes),
        timeAccessibilityText = formatStatsMinutesAccessible(totalMinutes),
        shareText = "100%"
    )
} else {
    CourseTimeStatsChartCenter(
        label = selectedItem.label,
        timeText = formatStatsMinutes(selectedItem.minutes),
        timeAccessibilityText = formatStatsMinutesAccessible(selectedItem.minutes),
        shareText = formatShare(selectedItem.share)
    )
}

/**
 * 以 12 点方向为起点顺时针命中环形图扇区；圆心与圆环外返回 null。
 */
internal fun findCourseTimeDonutSegment(
    tapX: Float,
    tapY: Float,
    width: Float,
    height: Float,
    innerRadius: Float,
    outerRadius: Float,
    shares: List<Double>
): Int? {
    if (shares.isEmpty() || width <= 0f || height <= 0f) return null
    val deltaX = tapX - width / 2f
    val deltaY = tapY - height / 2f
    val distance = hypot(deltaX, deltaY)
    if (distance < innerRadius || distance > outerRadius) return null

    val clockwiseDegrees = (
        Math.toDegrees(atan2(deltaY.toDouble(), deltaX.toDouble())) + 90.0 + 360.0
        ) % 360.0
    val ratio = clockwiseDegrees / 360.0
    var cumulative = 0.0
    shares.forEachIndexed { index, share ->
        cumulative += share.coerceAtLeast(0.0)
        if (ratio < cumulative) return index
    }
    return shares.indexOfLast { it > 0.0 }.takeIf { it >= 0 }
}

private fun colorFromHex(value: String): Color =
    runCatching { Color(AndroidColor.parseColor(value)) }.getOrDefault(StatsAccent)
