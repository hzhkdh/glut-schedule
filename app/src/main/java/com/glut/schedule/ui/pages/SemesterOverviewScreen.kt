package com.glut.schedule.ui.pages

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glut.schedule.data.model.SemesterAdjustment
import com.glut.schedule.data.model.displaySectionLabel
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val PageBg = Color(0xFFF6F4EF)
private val CardBg = Color(0xFFFFFEFB)
private val TextPrimary = Color(0xFF141821)
private val TextSecondary = Color(0xFF667085)
private val AccentBlue = Color(0xFF3F7DF6)
private val ProgressGray = Color(0xFFE5E7EB)
private val PastGray = Color(0xFFD1D5DB)
private val NextGreen = Color(0xFF16A34A)

@Composable
fun SemesterOverviewScreen(
    viewModel: SemesterOverviewViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PageBg)
    ) {
        if (uiState.isRefreshing) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = AccentBlue,
                trackColor = AccentBlue.copy(alpha = 0.12f)
            )
        }
        if (uiState.message.isNotBlank()) {
            Text(
                uiState.message,
                color = TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { SemesterInfoCard(uiState) }
            item { ProgressPieCard(uiState) }

            if (uiState.holidays.isNotEmpty()) {
                item { HolidaysCard(uiState.holidays) }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "调课一览",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = viewModel::refreshAdjustments,
                        enabled = !uiState.isRefreshing,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = "刷新调课",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            if (uiState.adjustmentsByWeek.isNotEmpty()) {
                val sortedWeeks = uiState.adjustmentsByWeek.keys.sorted()
                items(sortedWeeks) { week ->
                    AdjustmentWeekCard(
                        week,
                        uiState.adjustmentsByWeek[week].orEmpty(),
                        uiState.currentWeek,
                        uiState.hasNoon
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun SemesterInfoCard(state: SemesterOverviewUiState) {
    val fmt = DateTimeFormatter.ofPattern("yyyy.MM.dd")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = CardBg,
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(state.semesterLabel, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "${state.semesterStartDate.format(fmt)} → ${state.semesterEndDate.format(fmt)}",
                color = TextSecondary,
                fontSize = 14.sp
            )
            Text(
                "第 ${state.currentWeek} 周 · 共 ${state.totalWeeks} 周",
                color = TextSecondary,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun ProgressPieCard(state: SemesterOverviewUiState) {
    val remainingPercent = 1f - state.progressPercent
    val pct = (remainingPercent * 100).roundToInt()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = CardBg,
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("学期余额", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(12.dp))

            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(160.dp)) {
                Canvas(modifier = Modifier.size(160.dp)) {
                    val strokeW = 28f
                    val r = (size.minDimension - strokeW) / 2
                    val tl = Offset(strokeW / 2, strokeW / 2)
                    val arcSize = Size(r * 2, r * 2)

                    drawArc(
                        color = ProgressGray,
                        startAngle = -90f,
                        sweepAngle = 360f * state.progressPercent,
                        useCenter = false,
                        topLeft = tl,
                        size = arcSize,
                        style = Stroke(width = strokeW, cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = AccentBlue,
                        startAngle = -90f + 360f * state.progressPercent,
                        sweepAngle = 360f * remainingPercent,
                        useCenter = false,
                        topLeft = tl,
                        size = arcSize,
                        style = Stroke(width = strokeW, cap = StrokeCap.Round)
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${pct}%", color = AccentBlue, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("剩余", color = TextSecondary, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "已过 ${state.elapsedDays} 天 · 剩余 ${state.remainingDays} 天",
                color = TextSecondary,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun HolidaysCard(holidays: List<HolidayDisplay>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = CardBg,
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(4.dp)) {
            Text(
                "本学期节假日",
                color = TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 16.dp, top = 10.dp)
            )
            holidays.forEachIndexed { index, h ->
                if (index > 0) HorizontalDivider(color = Color(0xFFEDE8DE))
                HolidayRow(h)
            }
        }
    }
}

@Composable
private fun HolidayRow(h: HolidayDisplay) {
    val accentColor = when {
        h.isPast -> PastGray
        h.isOngoing -> NextGreen
        h.isNext -> NextGreen
        else -> TextSecondary
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Today,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                h.name,
                color = if (h.isPast) PastGray else TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            val dateStr = if (h.startDate == h.endDate) h.startDate
            else "${h.startDate} - ${h.endDate}"
            Text(dateStr, color = TextSecondary, fontSize = 13.sp)
            if (h.daysOff > 0) {
                Text("放假 ${h.daysOff} 天", color = TextSecondary, fontSize = 13.sp)
            }
        }
        if (h.isPast) {
            Text("已过", color = PastGray, fontSize = 13.sp)
        } else if (h.isOngoing) {
            Text("假期中", color = NextGreen, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        } else {
            val label = if (h.daysUntil == 0L) "今天" else "还有 ${h.daysUntil} 天"
            Text(label, color = accentColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun adjustmentTypeColor(type: String): Color = when (type) {
    "调课" -> Color(0xFF3F7DF6)
    "补课" -> Color(0xFFD97706)
    "停课" -> Color(0xFFDC2626)
    "代课" -> Color(0xFF8B5CF6)
    else -> Color(0xFF667085)
}

@Composable
private fun AdjustmentWeekCard(week: Int, adjustments: List<SemesterAdjustment>, currentWeek: Int, hasNoon: Boolean) {
    val isPast = week < currentWeek
    val isCurrent = week == currentWeek
    val cardBg = if (isCurrent) AccentBlue.copy(alpha = 0.06f) else CardBg
    val type = adjustments.firstOrNull()?.type ?: "调课"
    val typeColor = adjustmentTypeColor(type)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = cardBg,
        shape = RoundedCornerShape(12.dp),
        border = if (isCurrent) BorderStroke(1.dp, AccentBlue) else null
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "第${week}周",
                    color = if (isCurrent) AccentBlue else TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 14.dp, top = 8.dp)
                )
                if (isCurrent) {
                    Text(
                        "本周",
                        color = AccentBlue,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(start = 6.dp, top = 8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(AccentBlue.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            adjustments.forEachIndexed { idx, adj ->
                if (idx > 0) HorizontalDivider(color = Color(0xFFEDE8DE))
                AdjustmentRow(adj, isPast, hasNoon)
            }
        }
    }
}

@Composable
private fun AdjustmentRow(adj: SemesterAdjustment, isPast: Boolean, hasNoon: Boolean) {
    val dayNames = listOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")
    val alpha = if (isPast) 0.5f else 1f
    val typePrefix = when (adj.type) {
        "停课" -> "停"
        "补课" -> "补"
        "调课" -> "调"
        "代课" -> "代"
        else -> adj.type
    }
    val typeLabel = adj.type.ifBlank { "调课" }
    val typeColor = adjustmentTypeColor(adj.type)
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(adj.title, color = TextPrimary.copy(alpha = alpha), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                typeLabel,
                color = typeColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(typeColor.copy(alpha = 0.12f))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }
        val hasMakeup = adj.makeupWeek > 0 && adj.makeupDay > 0
        val hasOriginal = adj.originalWeek > 0 && adj.originalDay > 0
        if (hasMakeup || hasOriginal) Spacer(modifier = Modifier.height(4.dp))
        if (hasMakeup) {
            val mDay = dayNames.getOrElse(adj.makeupDay) { "周${adj.makeupDay}" }
            val mStart = displaySectionLabel(adj.makeupStartSection, hasNoon)
            val mEnd = displaySectionLabel(adj.makeupEndSection, hasNoon)
            Text(
                "$typePrefix  ${mDay} ${mStart}-${mEnd}节  ${adj.makeupRoom}",
                color = AccentBlue.copy(alpha = alpha),
                fontSize = 13.sp
            )
        }
        if (hasOriginal) {
            val oDay = dayNames.getOrElse(adj.originalDay) { "周${adj.originalDay}" }
            val oStart = displaySectionLabel(adj.originalStartSection, hasNoon)
            val oEnd = displaySectionLabel(adj.originalEndSection, hasNoon)
            Text(
                "原  第${adj.originalWeek}周 ${oDay} ${oStart}-${oEnd}节  ${adj.originalRoom}",
                color = TextSecondary.copy(alpha = alpha),
                fontSize = 12.sp
            )
        }
    }
}
