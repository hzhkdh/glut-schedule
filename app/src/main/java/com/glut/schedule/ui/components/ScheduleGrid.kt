package com.glut.schedule.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
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

/**
 * 固定节次高度内必须显式约束文字行高，并移除部分系统字体自带的超大上下留白。
 * 否则在 vivo 等使用手写系统字体且放大字号的设备上，第三行结束时间会被父布局裁切。
 */
fun periodColumnTextStyle(
    fontSize: TextUnit,
    lineHeight: TextUnit
): TextStyle = TextStyle(
    fontSize = fontSize,
    lineHeight = lineHeight,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None
    )
)

data class ScheduleCalendarDay(
    val name: String,
    val date: LocalDate?,
    val isToday: Boolean
)

/**
 * 日期栏右下角的单字状态角标。
 *
 * 与小程序 `components/schedule-grid` 的 `dateMarker` 一致：一个日期只显示一个字，
 * 「调」优先于「休」——手动调课意味着该日确实追加了课程，比假期状态更需要被看到。
 */
internal enum class ScheduleDayMarker(val label: String) {
    HOLIDAY("休"),
    ADJUSTMENT("调")
}

/** 法定放假日的角标配色（与小程序 `.date-marker--holiday` 同值）。 */
private val HolidayMarkerColor = Color(0xFF2D9A72)

/** 手动调课目标日的角标配色（与小程序 `.date-marker--adjustment` 同值）。 */
private val AdjustmentMarkerColor = Color(0xFFE57411)

/**
 * 判定某一天该显示哪个角标。
 *
 * `date` 为 null（未显示日期、或历史学期缺少权威日历时）一律不显示角标——
 * 角标依附于具体自然日，没有日期就没有落点。
 * 只标法定放假日：补班日（节假日接口里 `holiday: false`）不会进 [holidayDates]，因此天然无角标。
 */
internal fun scheduleDayMarker(
    date: LocalDate?,
    holidayDates: Set<LocalDate>,
    manualAdjustmentDates: Set<LocalDate>
): ScheduleDayMarker? = when {
    date == null -> null
    date in manualAdjustmentDates -> ScheduleDayMarker.ADJUSTMENT
    date in holidayDates -> ScheduleDayMarker.HOLIDAY
    else -> null
}

/** 首页与情侣/基友课表共用同一份日期映射，避免星期、日期和今日高亮发生漂移。 */
fun scheduleCalendarDays(
    week: ScheduleWeek,
    today: LocalDate,
    dayCount: Int,
    showCalendarDates: Boolean
): List<ScheduleCalendarDay> = dayNames.take(dayCount).mapIndexed { index, name ->
    val date = week.dateFor(index + 1).takeIf { showCalendarDates }
    ScheduleCalendarDay(name = name, date = date, isToday = date == today)
}

@Composable
fun ScheduleGrid(
    week: ScheduleWeek,
    today: LocalDate,
    periods: List<ClassPeriod>,
    blocks: List<CourseBlock>,
    showWeekend: Boolean,
    showNoon: Boolean = false,
    showCalendarDates: Boolean = true,
    holidayDates: Set<LocalDate> = emptySet(),
    manualAdjustmentDates: Set<LocalDate> = emptySet(),
    /** 长按卡片时回调当前**显示中**的那一张（冲突组里只有它是可见的），打开卡片管理弹层。 */
    onCourseLongClick: ((CourseBlock) -> Unit)? = null,
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
            ScheduleCalendarHeader(
                week = week,
                today = today,
                leftWidth = leftWidth,
                dayWidth = dayWidth,
                dayCount = dayCount,
                showCalendarDates = showCalendarDates,
                holidayDates = holidayDates,
                manualAdjustmentDates = manualAdjustmentDates
            )

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
                    showNoon = effectiveShowNoon,
                    onCourseLongClick = onCourseLongClick,
                )
            }
        }
    }
}

@Composable
fun ScheduleCalendarHeader(
    week: ScheduleWeek,
    today: LocalDate,
    leftWidth: Dp,
    dayWidth: Dp,
    dayCount: Int,
    showCalendarDates: Boolean = true,
    holidayDates: Set<LocalDate> = emptySet(),
    manualAdjustmentDates: Set<LocalDate> = emptySet()
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        MonthHeader(week = week, width = leftWidth, showCalendarDates = showCalendarDates)
        WeekDayHeader(
            week = week,
            today = today,
            dayWidth = dayWidth,
            dayCount = dayCount,
            showCalendarDates = showCalendarDates,
            holidayDates = holidayDates,
            manualAdjustmentDates = manualAdjustmentDates
        )
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
    showCalendarDates: Boolean,
    holidayDates: Set<LocalDate>,
    manualAdjustmentDates: Set<LocalDate>
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        scheduleCalendarDays(week, today, dayCount, showCalendarDates).forEach { item ->
            val marker = scheduleDayMarker(item.date, holidayDates, manualAdjustmentDates)
            // 用 Box 叠加而不是往列里塞第三行：角标绝对定位在右下角，
            // 不改变日期栏高度、列宽，也不会挤动下面的课程卡片。
            Box(
                modifier = Modifier
                    .width(dayWidth)
                    .padding(bottom = 4.dp)
            ) {
                Column(
                    modifier = Modifier.align(Alignment.TopCenter),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = item.name,
                        color = if (item.isToday) Color.White else Color.White.copy(alpha = 0.42f),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    item.date?.let { date ->
                        Text(
                            text = date.dayOfMonth.toString(),
                            color = if (item.isToday) Color.White else Color.White.copy(alpha = 0.34f),
                            fontSize = 12.sp,
                            modifier = if (item.isToday) {
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
                if (marker != null) {
                    Text(
                        text = marker.label,
                        color = if (marker == ScheduleDayMarker.ADJUSTMENT) {
                            AdjustmentMarkerColor
                        } else {
                            HolidayMarkerColor
                        },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 3.dp)
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
                        style = periodColumnTextStyle(fontSize = 14.sp, lineHeight = 16.sp),
                        maxLines = 1,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = period.startsAt,
                        color = Color.White.copy(alpha = 0.56f),
                        style = periodColumnTextStyle(fontSize = 9.sp, lineHeight = 11.sp),
                        maxLines = 1
                    )
                    Text(
                        text = period.endsAt,
                        color = Color.White.copy(alpha = 0.56f),
                        style = periodColumnTextStyle(fontSize = 9.sp, lineHeight = 11.sp),
                        maxLines = 1
                    )
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
    showNoon: Boolean = false,
    onCourseLongClick: ((CourseBlock) -> Unit)? = null,
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
                    onConflictClick = nextBlock,
                    // 交给上层的始终是 activeBlock：冲突组里只有当前显示的那一张是看得见的。
                    onLongClick = onCourseLongClick?.let { callback -> { callback(activeBlock) } },
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
    onConflictClick: (() -> Unit)?,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val color = remember(block.course.colorHex) { Color(android.graphics.Color.parseColor(block.course.colorHex)) }
    val titleSize = courseCardTitleTextSize(block.course.title)
    val titleLineHeight = courseCardTitleLineHeight()
    // 长按与「单击切换冲突课」共存：combinedClickable 保证长按时不会再触发 onClick，
    // 长按也不推进冲突索引，两者互不干扰。
    val clickableModifier = Modifier.combinedClickable(
        onClick = { if (conflictCount > 1) onConflictClick?.invoke() },
        onLongClick = onLongClick
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(color)
            .then(clickableModifier)
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .semantics {
                contentDescription =
                    "${block.course.title}，${block.course.teacher}，${block.course.room}" +
                    (if (conflictCount > 1) "，共${conflictCount}门冲突课程，单击切换" else "") +
                    "，长按管理卡片"
            }
    ) {
        Column(
            modifier = Modifier,
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
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
                    .align(Alignment.BottomEnd)
                    .offset(x = 2.dp, y = 2.dp)
                    .size(18.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
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
