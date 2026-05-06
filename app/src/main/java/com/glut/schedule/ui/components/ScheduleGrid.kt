package com.glut.schedule.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glut.schedule.data.model.ClassPeriod
import com.glut.schedule.data.model.CourseBlock
import com.glut.schedule.data.model.ScheduleWeek
import com.glut.schedule.data.model.visibleDayCount
import java.time.LocalDate

private val dayNames = listOf("一", "二", "三", "四", "五", "六", "日")

@Composable
fun ScheduleGrid(
    week: ScheduleWeek,
    today: LocalDate,
    periods: List<ClassPeriod>,
    blocks: List<CourseBlock>,
    showWeekend: Boolean,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val rowHeight = 74.dp
        val leftWidth = 52.dp
        val dayCount = visibleDayCount(showWeekend)
        val dayWidth = (maxWidth - leftWidth) / dayCount
        val visibleBlocks = remember(blocks, dayCount) {
            blocks.filter { it.occurrence.dayOfWeek <= dayCount }
        }

        Column {
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.width(leftWidth))
                WeekDayHeader(week = week, today = today, dayWidth = dayWidth, dayCount = dayCount)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                PeriodColumn(periods = periods, rowHeight = rowHeight, width = leftWidth)
                TimetableBody(
                    periods = periods,
                    blocks = visibleBlocks,
                    rowHeight = rowHeight,
                    dayWidth = dayWidth,
                    dayCount = dayCount
                )
            }
        }
    }
}

@Composable
private fun WeekDayHeader(
    week: ScheduleWeek,
    today: LocalDate,
    dayWidth: Dp,
    dayCount: Int
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        dayNames.take(dayCount).forEachIndexed { index, name ->
            val day = index + 1
            val date = week.dateFor(day)
            val isToday = date == today
            Column(
                modifier = Modifier
                    .width(dayWidth)
                    .padding(bottom = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = name,
                    color = if (isToday) Color.White else Color.White.copy(alpha = 0.42f),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = date.dayOfMonth.toString(),
                    color = if (isToday) Color.White else Color.White.copy(alpha = 0.34f),
                    fontSize = 12.sp,
                    modifier = if (isToday) {
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.22f))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    } else {
                        Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    }
                )
            }
        }
    }
}

@Composable
private fun PeriodColumn(
    periods: List<ClassPeriod>,
    rowHeight: Dp,
    width: Dp
) {
    Column(modifier = Modifier.width(width)) {
        periods.forEach { period ->
            Column(
                modifier = Modifier
                    .height(rowHeight)
                    .padding(start = 6.dp, top = 7.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = period.section.toString(),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(text = period.startsAt, color = Color.White.copy(alpha = 0.56f), fontSize = 9.sp)
                Text(text = period.endsAt, color = Color.White.copy(alpha = 0.56f), fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun TimetableBody(
    periods: List<ClassPeriod>,
    blocks: List<CourseBlock>,
    rowHeight: Dp,
    dayWidth: Dp,
    dayCount: Int
) {
    val totalHeight = rowHeight * periods.size
    Box(
        modifier = Modifier
            .size(width = dayWidth * dayCount, height = totalHeight)
    ) {
        blocks.forEach { block ->
            CourseCard(
                block = block,
                modifier = Modifier
                    .offset(
                        x = dayWidth * (block.occurrence.dayOfWeek - 1) + 2.dp,
                        y = rowHeight * (block.occurrence.startSection - 1) + 3.dp
                    )
                    .size(
                        width = dayWidth - 4.dp,
                        height = rowHeight * block.occurrence.sectionSpan - 6.dp
                    )
            )
        }
    }
}

@Composable
private fun CourseCard(
    block: CourseBlock,
    modifier: Modifier = Modifier
) {
    val color = remember(block.course.colorHex) { Color(android.graphics.Color.parseColor(block.course.colorHex)) }
    val titleSize = compactTitleSize(block.course.title)
    val titleLineHeight = compactLineHeight(titleSize)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .clipToBounds()
            .background(color)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        Text(
            text = block.course.title,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = titleSize,
            lineHeight = titleLineHeight
        )
        Text(
            text = "@${block.course.room}",
            color = Color.White,
            fontSize = 9.sp,
            lineHeight = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = block.course.teacher,
            color = Color.White.copy(alpha = 0.95f),
            fontSize = 9.sp,
            lineHeight = 10.sp
        )
    }
}

private fun compactTitleSize(title: String): TextUnit = when {
    title.length > 18 -> 6.sp
    title.length > 12 -> 7.sp
    title.length > 8 -> 8.sp
    else -> 11.sp
}

private fun compactLineHeight(fontSize: TextUnit): TextUnit = when (fontSize) {
    6.sp -> 7.sp
    7.sp -> 8.sp
    8.sp -> 9.sp
    else -> 12.sp
}
