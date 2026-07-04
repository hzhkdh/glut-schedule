package com.glut.schedule.ui.pages

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glut.schedule.data.model.ExamInfo
import com.glut.schedule.data.model.cleanExamText
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val ExamPaperBackground = Color(0xFFF6F4EF)
private val ExamPaperCard = Color(0xFFFFFEFB)
private val ExamTextPrimary = Color(0xFF141821)
private val ExamTextSecondary = Color(0xFF667085)
private val ExamTextTertiary = Color(0xFF8A93A3)
private val ExamDivider = Color(0xFFDDE2EA)
private val ExamAccent = Color(0xFF3F7DF6)
private val ExamAccentSoft = Color(0xFFEAF1FF)
private val ExamWarning = Color(0xFFE8752A)
private val ExamWarningSoft = Color(0xFFFFEFE4)
private val ExamCardBorder = Color(0xFFEDE8DE)

@Composable
fun ExamScreen(
    viewModel: ExamViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val now = LocalDateTime.now()
    val displayExams = examsForDisplay(uiState.exams, now)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ExamPaperBackground)
    ) {
        if (uiState.isRefreshing) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = ExamAccent,
                trackColor = ExamAccentSoft
            )
        }
        if (uiState.message.isNotBlank()) {
            Text(
                text = uiState.message,
                color = ExamTextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
            )
        }
        if (uiState.exams.isEmpty()) {
            ExamEmptyState(
                hasCookie = uiState.hasCookie,
                onRefresh = viewModel::refreshExams,
                modifier = Modifier.weight(1f)
            )
        } else {
            ExamList(
                exams = displayExams,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ExamEmptyState(
    hasCookie: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "暂无考试安排",
                color = ExamTextPrimary,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (hasCookie) "请点击刷新按钮获取最新考试安排\n或前往课表导入页面重新导入"
                else "请先在课表导入页面登录教务系统\n登录后点击下载按钮自动导入考试",
                color = ExamTextSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ExamList(
    exams: List<ExamDisplayItem>,
    modifier: Modifier = Modifier
) {
    val groupedByDate = exams.groupBy { it.exam.examDate }
    val today = LocalDate.now()

    LazyColumn(
        modifier = modifier.padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        groupedByDate.entries.forEachIndexed { index, (date, dayExams) ->
            item(key = "group_$date") {
                ExamTimelineGroup(
                    date = date,
                    dayExams = dayExams,
                    isToday = date == today,
                    isLast = index == groupedByDate.size - 1
                )
            }
        }
    }
}

@Composable
private fun ExamTimelineGroup(
    date: LocalDate,
    dayExams: List<ExamDisplayItem>,
    isToday: Boolean,
    isLast: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .width(42.dp)
                .fillMaxHeight()
        ) {
            if (!isLast) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawLine(
                        color = ExamDivider,
                        start = Offset(x = 12.dp.toPx(), y = 18.dp.toPx()),
                        end = Offset(x = 12.dp.toPx(), y = size.height),
                        strokeWidth = 1.5.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
            Box(
                modifier = Modifier
                    .padding(top = 7.dp)
                    .size(18.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (isToday) ExamAccent else Color(0xFFC4CAD3))
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ExamDateHeader(date = date, isToday = isToday)
            dayExams.forEach { item ->
                ExamCard(exam = item.exam, displayState = item.state)
            }
        }
    }
}

@Composable
private fun ExamDateHeader(
    date: LocalDate,
    isToday: Boolean
) {
    val dayLabel = dayLabel(date.dayOfWeek)
    val dateStr = date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
    val status = examDateStatus(date, LocalDate.now())

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$dateStr $dayLabel",
            color = if (isToday) ExamAccent else ExamTextSecondary,
            fontSize = 19.sp,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.SemiBold
        )
        if (status.isNotBlank()) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = status,
                color = ExamAccent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(ExamAccentSoft)
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun ExamCard(
    exam: ExamInfo,
    displayState: ExamDisplayState
) {
    val completed = displayState == ExamDisplayState.Completed
    val courseVisual = ExamCourseVisualMapper.visualFor(exam.courseName)
    val typeColors = examTypeColors(exam.examType)
    val timeText = buildString {
        append(exam.startTime)
        if (exam.endTime.isNotBlank()) append(" - ${exam.endTime}")
    }

    Box(
        modifier = Modifier.fillMaxWidth()
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer(alpha = if (completed) 0.66f else 1f),
            color = if (completed) Color(0xFFF3F4F6) else ExamPaperCard,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, if (completed) Color(0xFFE2E5EA) else ExamCardBorder),
            shadowElevation = if (completed) 1.dp else 6.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                CourseIcon(
                    icon = courseVisual.icon,
                    color = courseVisual.accent,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(44.dp)
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = exam.courseName,
                            color = if (completed) ExamTextSecondary else ExamTextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (exam.examType.isNotBlank()) {
                            Text(
                                text = exam.examType,
                                color = typeColors.content,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(typeColors.container)
                                    .padding(horizontal = 9.dp, vertical = 6.dp)
                            )
                        }
                    }
                    ExamMetaLine(
                        icon = Icons.Outlined.AccessTime,
                        text = timeText
                    )
                    ExamMetaLine(
                        icon = Icons.Outlined.LocationOn,
                        text = cleanExamText(exam.location),
                        maxLines = 2
                    )
                    if (exam.seatNumber.isNotBlank() || exam.note.isNotBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            if (exam.seatNumber.isNotBlank()) {
                                Text(
                                    text = "座位: ${exam.seatNumber}",
                                    color = ExamTextTertiary,
                                    fontSize = 12.sp
                                )
                            }
                            if (exam.note.isNotBlank()) {
                                Text(
                                    text = exam.note,
                                    color = ExamTextTertiary,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
        if (completed) {
            Text(
                text = "已结束",
                color = Color(0xFF6B7280),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .clip(RoundedCornerShape(bottomStart = 14.dp, topEnd = 12.dp))
                    .background(Color(0xFFD1D5DB))
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun CourseIcon(
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(26.dp)
        )
    }
}

@Composable
private fun ExamMetaLine(
    icon: ImageVector,
    text: String,
    maxLines: Int = 1
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = ExamTextTertiary,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(18.dp)
        )
        Text(
            text = text,
            color = ExamTextSecondary,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private data class ExamTypeColors(
    val container: Color,
    val content: Color
)

private fun examTypeColors(examType: String): ExamTypeColors {
    return if (examType.contains("补考") || examType.contains("重修")) {
        ExamTypeColors(container = ExamWarningSoft, content = ExamWarning)
    } else {
        ExamTypeColors(container = ExamAccentSoft, content = ExamAccent)
    }
}

private fun dayLabel(dayOfWeek: DayOfWeek): String = when (dayOfWeek) {
    DayOfWeek.MONDAY -> "周一"
    DayOfWeek.TUESDAY -> "周二"
    DayOfWeek.WEDNESDAY -> "周三"
    DayOfWeek.THURSDAY -> "周四"
    DayOfWeek.FRIDAY -> "周五"
    DayOfWeek.SATURDAY -> "周六"
    DayOfWeek.SUNDAY -> "周日"
}

internal fun isExamUpcoming(exam: ExamInfo, now: LocalDateTime): Boolean {
    val today = now.toLocalDate()
    return when {
        exam.examDate.isAfter(today) -> true
        exam.examDate.isBefore(today) -> false
        else -> {
            val endTime = parseExamTime(exam.endTime) ?: return true
            !now.toLocalTime().isAfter(endTime)
        }
    }
}

internal enum class ExamDisplayState {
    Upcoming,
    Completed
}

internal data class ExamDisplayItem(
    val exam: ExamInfo,
    val state: ExamDisplayState
)

internal fun examDisplayState(exam: ExamInfo, now: LocalDateTime): ExamDisplayState {
    return if (isExamUpcoming(exam, now)) {
        ExamDisplayState.Upcoming
    } else {
        ExamDisplayState.Completed
    }
}

internal fun examsForDisplay(exams: List<ExamInfo>, now: LocalDateTime): List<ExamDisplayItem> {
    return exams
        .map { exam -> ExamDisplayItem(exam = exam, state = examDisplayState(exam, now)) }
        .sortedWith(
            compareBy<ExamDisplayItem> { item ->
                if (item.state == ExamDisplayState.Upcoming) 0 else 1
            }
                .thenBy { item ->
                    if (item.state == ExamDisplayState.Upcoming)
                        item.exam.examDate.toEpochDay()
                    else
                        -item.exam.examDate.toEpochDay()
                }
                .thenBy { item ->
                    val seconds = (parseExamTime(item.exam.startTime) ?: LocalTime.MIN).toSecondOfDay()
                    if (item.state == ExamDisplayState.Upcoming) seconds else -seconds
                }
                .thenBy { item -> item.exam.courseName }
        )
}

internal fun examDateStatus(date: LocalDate, today: LocalDate): String {
    val days = ChronoUnit.DAYS.between(today, date)
    return when {
        days == 0L -> "今天"
        days == 1L -> "明天"
        days > 1L -> "还有 $days 天"
        else -> ""
    }
}

private fun parseExamTime(value: String): LocalTime? {
    val normalized = value.trim()
    if (normalized.isBlank()) return null
    val patterns = listOf("H:mm", "HH:mm", "H:mm:ss", "HH:mm:ss")
    return patterns.firstNotNullOfOrNull { pattern ->
        runCatching { LocalTime.parse(normalized, DateTimeFormatter.ofPattern(pattern)) }.getOrNull()
    }
}
