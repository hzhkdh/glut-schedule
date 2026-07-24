package com.glut.schedule.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glut.schedule.data.model.ClassPeriod
import com.glut.schedule.data.model.NOON_SECTIONS
import com.glut.schedule.data.model.periodLabel
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
    showNoon: Boolean = false,
    showCalendarDates: Boolean = true,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val rowHeight = 74.dp
        val leftWidth = 52.dp
        val dayCount = visibleDayCount(showWeekend)
        val dayWidth = (maxWidth - leftWidth) / dayCount
        // 南宁课表（periods.size==11）无中午时段，不应过滤/偏移；桂林（14节）按用户设置
        val effectiveShowNoon = showNoon || periods.size <= 11
        val visibleBlocks = remember(blocks, dayCount, effectiveShowNoon) {
            blocks.filter { block ->
                block.occurrence.dayOfWeek <= dayCount &&
                    (effectiveShowNoon || block.occurrence.startSection !in NOON_SECTIONS)
            }
        }

        Column {
            Row(modifier = Modifier.fillMaxWidth()) {
                MonthHeader(week = week, width = leftWidth, showCalendarDates = showCalendarDates)
                WeekDayHeader(week = week, today = today, dayWidth = dayWidth, dayCount = dayCount, showCalendarDates = showCalendarDates)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                PeriodColumn(periods = periods, rowHeight = rowHeight, width = leftWidth, showNoon = effectiveShowNoon)
                TimetableBody(
                    periods = periods,
                    blocks = visibleBlocks,
                    rowHeight = rowHeight,
                    dayWidth = dayWidth,
                    dayCount = dayCount,
                    showNoon = effectiveShowNoon
                )
            }
        }
    }
}

@Composable
private fun MonthHeader(
    week: ScheduleWeek,
    width: Dp,
    showCalendarDates: Boolean
) {
    Box(
        modifier = Modifier
            .width(width)
            .padding(
                start = scheduleGridMonthHeaderStartPaddingDp().dp,
                top = scheduleGridMonthHeaderTopPaddingDp().dp
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = if (showCalendarDates) scheduleGridMonthText(week.monday) else "周次",
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 12.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

fun scheduleGridMonthText(date: LocalDate): String {
    return "${date.monthValue}月"
}

fun scheduleGridMonthHeaderStartPaddingDp(): Int = 15

fun scheduleGridMonthHeaderTopPaddingDp(): Int = 6

@Composable
private fun WeekDayHeader(
    week: ScheduleWeek,
    today: LocalDate,
    dayWidth: Dp,
    dayCount: Int,
    showCalendarDates: Boolean
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        dayNames.take(dayCount).forEachIndexed { index, name ->
            val day = index + 1
            val date = week.dateFor(day)
            val isToday = showCalendarDates && date == today
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
                if (showCalendarDates) {
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
}

@Composable
private fun PeriodColumn(
    periods: List<ClassPeriod>,
    rowHeight: Dp,
    width: Dp,
    showNoon: Boolean = false
) {
    // 南宁（11节）无中午时段，节次标签直排；桂林（14节）用 periodLabel() 含午1/午2+偏移
    val isNanning = periods.size <= 11
    Column(modifier = Modifier.width(width)) {
        periods.forEach { period ->
            val isNoon = !isNanning && period.section in NOON_SECTIONS
            val h = if (!showNoon && isNoon) 0.dp else rowHeight
            if (h > 0.dp) {
                Column(
                    modifier = Modifier
                        .height(h)
                        .padding(start = 6.dp, top = 7.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = if (isNanning) "${period.section}" else period.periodLabel(),
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
}

@Composable
private fun TimetableBody(
    periods: List<ClassPeriod>,
    blocks: List<CourseBlock>,
    rowHeight: Dp,
    dayWidth: Dp,
    dayCount: Int,
    showNoon: Boolean = false
) {
    val visiblePeriodCount = if (showNoon) periods.size else periods.size - NOON_SECTIONS.size
    val totalHeight = rowHeight * visiblePeriodCount
    Box(
        modifier = Modifier
            .size(width = dayWidth * dayCount, height = totalHeight)
    ) {
        val blockGroups = remember(blocks) { overlappingCourseBlockGroups(blocks) }
        blockGroups.forEach { group ->
            val groupKey = remember(group) { courseBlockGroupKey(group) }
            key(groupKey) {
                var activeIndex by remember { mutableStateOf(0) }
                val activeBlock = group[activeIndex % group.size]
                val nextBlock = if (group.size > 1) {
                    { activeIndex = (activeIndex + 1) % group.size }
                } else {
                    null
                }

                CourseCard(
                    block = activeBlock,
                    conflictCount = group.size,
                    onClick = nextBlock,
                    modifier = Modifier
                        .offset(
                            x = dayWidth * (activeBlock.occurrence.dayOfWeek - 1) + 2.dp,
                            y = rowHeight * (if (!showNoon && activeBlock.occurrence.startSection > 6)
                                activeBlock.occurrence.startSection - 3
                            else
                                activeBlock.occurrence.startSection - 1) + 3.dp
                        )
                        .size(
                            width = dayWidth - 4.dp,
                            height = rowHeight * activeBlock.occurrence.sectionSpan - 6.dp
                        )
                )
            }
        }
    }
}

fun overlappingCourseBlockGroups(blocks: List<CourseBlock>): List<List<CourseBlock>> {
    return blocks
        .groupBy { it.occurrence.dayOfWeek }
        .toSortedMap()
        .flatMap { (_, dayBlocks) ->
            val sortedBlocks = dayBlocks.sortedWith(courseBlockPositionComparator())
            val groups = mutableListOf<List<CourseBlock>>()
            val currentGroup = mutableListOf<CourseBlock>()
            var currentEndSection = 0

            sortedBlocks.forEach { block ->
                if (currentGroup.isEmpty() || block.occurrence.startSection <= currentEndSection) {
                    currentGroup += block
                    currentEndSection = maxOf(currentEndSection, block.occurrence.endSection)
                } else {
                    groups += currentGroup.sortedWith(courseBlockDisplayComparator())
                    currentGroup.clear()
                    currentGroup += block
                    currentEndSection = block.occurrence.endSection
                }
            }

            if (currentGroup.isNotEmpty()) {
                groups += currentGroup.sortedWith(courseBlockDisplayComparator())
            }

            groups
        }
}

private fun courseBlockGroupKey(group: List<CourseBlock>): String {
    return group.joinToString(separator = "|") { block ->
        listOf(
            block.course.id,
            block.occurrence.id,
            block.occurrence.dayOfWeek,
            block.occurrence.startSection,
            block.occurrence.endSection
        ).joinToString(separator = ":")
    }
}

private fun courseBlockPositionComparator(): Comparator<CourseBlock> {
    return compareBy<CourseBlock> { it.occurrence.startSection }
        .thenBy { it.occurrence.endSection }
        .thenBy { it.course.title }
        .thenBy { it.occurrence.id }
}

private fun courseBlockDisplayComparator(): Comparator<CourseBlock> {
    return compareBy<CourseBlock> { it.occurrence.sectionSpan }
        .thenBy { it.occurrence.startSection }
        .thenBy { it.occurrence.endSection }
        .thenBy { it.course.title }
        .thenBy { it.occurrence.id }
}

@Composable
private fun CourseCard(
    block: CourseBlock,
    conflictCount: Int,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val color = remember(block.course.colorHex) { Color(android.graphics.Color.parseColor(block.course.colorHex)) }
    val titleSize = courseCardTitleTextSize(block.course.title)
    val titleLineHeight = courseCardTitleLineHeight()
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(color)
            .then(clickableModifier)
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .semantics {
                contentDescription =
                    "${block.course.title}，${block.course.teacher}，${block.course.room}" +
                    if (conflictCount > 1) "，共${conflictCount}门冲突课程" else ""
            }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = block.course.title,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = titleSize,
                lineHeight = titleLineHeight,
                maxLines = courseCardTitleMaxLines(block.occurrence.sectionSpan),
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "@${block.course.room}",
                color = Color.White,
                fontSize = courseCardRoomTextSize(),
                lineHeight = courseCardRoomLineHeight(),
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = block.course.teacher,
                color = Color.White.copy(alpha = 0.95f),
                fontSize = courseCardTeacherTextSize(),
                lineHeight = courseCardTeacherLineHeight()
            )
        }

        if (conflictCount > 1) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .size(15.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE11D48)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = conflictCount.toString(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    lineHeight = 9.sp
                )
            }
        }
    }
}

fun courseCardTitleTextSize(title: String): TextUnit = 11.sp

fun courseCardTitleLineHeight(): TextUnit = 12.sp

fun courseCardRoomTextSize(): TextUnit = 10.sp

fun courseCardRoomLineHeight(): TextUnit = 11.sp

fun courseCardTeacherTextSize(): TextUnit = 10.sp

fun courseCardTeacherLineHeight(): TextUnit = 11.sp

fun courseCardTitleMaxLines(sectionSpan: Int): Int {
    return when {
        sectionSpan <= 1 -> 2
        sectionSpan == 2 -> 5
        else -> sectionSpan * 3
    }
}
