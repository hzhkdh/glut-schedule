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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
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
import com.glut.schedule.data.model.SemesterAdjustment
import com.glut.schedule.data.model.adjustmentMarkerFor
import com.glut.schedule.data.model.readableMarkerColor
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
 * 课表网格线配色。
 *
 * 用半透明白而不是实色：课表可以铺任意自定义背景图，实色线在深色图上会白得刺眼、
 * 在浅色图上又完全看不见，半透明白在两种底上都只是「隐约可见」。
 * 时段分界（上午/中午/下午/晚上）比普通行线更亮更粗，让一天的分段一眼可辨。
 */
private val GridLineColor = Color.White.copy(alpha = 0.10f)
private val GridLineBoundaryColor = Color.White.copy(alpha = 0.26f)

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
    /** 是否绘制课表网格线；默认关闭，避免在自定义背景图上凭空多出一层线。 */
    showGridLines: Boolean = false,
    /**
     * 各时段的最后一个内部节次（上午/中午/下午/晚上依次排列），来自 `CampusCapabilities`。
     *
     * 只用来判断相邻两行是否跨时段，从而把分界缝画得比普通行线更重。
     * 默认空列表表示「不做时段区分」，此时所有行线粗细一致。
     */
    blockEndSections: List<Int> = emptyList(),
    /**
     * 该来源是否含「中午1/中午2」两个特殊节次。
     *
     * 过去这里靠 `periods.size <= 11` 推断「是不是南宁」，加入研究生（也是 12 节、
     * 也没有中午节次）后这条推断同时失效：它会把研究生的第 7-12 节当成桂林节次整体前移 2。
     * 默认值保持旧口径，调用方应按来源显式传入。
     */
    hasNoonSections: Boolean = periods.size > 11,
    showCalendarDates: Boolean = true,
    holidayDates: Set<LocalDate> = emptySet(),
    manualAdjustmentDates: Set<LocalDate> = emptySet(),
    /** 长按卡片时回调当前**显示中**的那一张（冲突组里只有它是可见的），打开卡片管理弹层。 */
    onCourseLongClick: ((CourseBlock) -> Unit)? = null,
    /** 该学期的教务调课记录，用来给调课/补课产生的卡片打「调」/「补」角标。 */
    adjustments: List<SemesterAdjustment> = emptyList(),
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val rowHeight = 74.dp
        val leftWidth = 52.dp
        val dayCount = visibleDayCount(showWeekend)
        val dayWidth = (maxWidth - leftWidth) / dayCount
        // 没有中午节次的来源（南宁、研究生）必须原样显示全部节次，不折叠也不偏移；
        // 桂林（14节）才按用户的「显示中午」开关决定是否折叠中间两行。
        val effectiveShowNoon = showNoon || !hasNoonSections
        // 可见节次行只有这一份，节次列、课程卡片定位和网格线全部按它算行号。
        // 各自再过滤一次的话，隐藏中午时线会比卡片整整错开两行。
        val visibleRows = remember(periods, effectiveShowNoon) {
            visiblePeriodRows(periods, effectiveShowNoon)
        }
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
                PeriodColumn(
                    visibleRows = visibleRows,
                    rowHeight = rowHeight,
                    width = leftWidth,
                    hasNoonSections = hasNoonSections
                )
                TimetableBody(
                    visibleRows = visibleRows,
                    blocks = visibleBlocks,
                    rowHeight = rowHeight,
                    dayWidth = dayWidth,
                    dayCount = dayCount,
                    showGridLines = showGridLines,
                    blockEndSections = blockEndSections,
                    onCourseLongClick = onCourseLongClick,
                    weekNumber = week.number,
                    adjustments = adjustments,
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

/**
 * 当前实际显示的节次行。
 *
 * 有中午节次的来源（桂林）在关闭「显示中午」时要把午1/午2 两行整行拿掉；
 * 南宁与研究生没有这两节，`effectiveShowNoon` 恒为 true，因此原样返回全部节次。
 */
fun visiblePeriodRows(
    periods: List<ClassPeriod>,
    effectiveShowNoon: Boolean
): List<ClassPeriod> = if (effectiveShowNoon) periods else periods.filter { it.section !in NOON_SECTIONS }

/** 内部节次号在可见行里的下标；不在可见行里（例如被隐藏的午1/午2）返回 null。 */
fun scheduleRowIndex(section: Int, visibleRows: List<ClassPeriod>): Int? =
    visibleRows.indexOfFirst { it.section == section }.takeIf { it >= 0 }

/**
 * 内部节次号属于哪个时段（0 起：上午 / 中午 / 下午 / 晚上）。
 *
 * [blockEndSections] 装的是各时段的最后一个内部节次，所以「比我小的上界有几个」就是我的时段号。
 * 用时段号而不是节次号本身来比较，隐藏中午时 4 与 7 会自然判成不同时段，
 * 它们之间那条缝就并成了一条分界线，不会留下两条并排的线或画在错误的位置。
 */
fun scheduleTimeBlockIndex(section: Int, blockEndSections: List<Int>): Int =
    blockEndSections.count { it < section }

@Composable
private fun PeriodColumn(
    visibleRows: List<ClassPeriod>,
    rowHeight: Dp,
    width: Dp,
    hasNoonSections: Boolean = true
) {
    // 有中午节次的来源（桂林）用 periodLabel() 把内部号映射成「午1/午2 + 5-12」；
    // 南宁与研究生没有中午节次，内部号即显示号，直排。
    Column(modifier = Modifier.width(width)) {
        visibleRows.forEach { period ->
            Column(
                modifier = Modifier
                    .height(rowHeight)
                    .padding(start = 6.dp, top = 7.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = if (hasNoonSections) period.periodLabel() else "${period.section}",
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

@Composable
private fun TimetableBody(
    visibleRows: List<ClassPeriod>,
    blocks: List<CourseBlock>,
    rowHeight: Dp,
    dayWidth: Dp,
    dayCount: Int,
    showGridLines: Boolean = false,
    blockEndSections: List<Int> = emptyList(),
    onCourseLongClick: ((CourseBlock) -> Unit)? = null,
    weekNumber: Int = 0,
    adjustments: List<SemesterAdjustment> = emptyList(),
) {
    val totalHeight = rowHeight * visibleRows.size
    Box(
        modifier = Modifier
            .size(width = dayWidth * dayCount, height = totalHeight)
            .drawBehind {
                if (!showGridLines) return@drawBehind
                // 逐行的**下缘**画线，行号与卡片共用同一份 visibleRows，隐藏中午时不会错位。
                // 末行贴着容器底边，线宽的一半会被裁掉，所以把 y 收进来半个线宽。
                val rowPx = rowHeight.toPx()
                val normalStroke = 0.7.dp.toPx()
                val boundaryStroke = 1.6.dp.toPx()
                visibleRows.forEachIndexed { index, period ->
                    val previous = visibleRows.getOrNull(index - 1)
                    // 只有自己可见的上邻居才算数；比较时段号而不是节次号，
                    // 于是「4 与 7 相邻」（隐藏中午）也会被判成一条分界线。
                    val isBoundary = previous != null &&
                        scheduleTimeBlockIndex(period.section, blockEndSections) !=
                        scheduleTimeBlockIndex(previous.section, blockEndSections)
                    val stroke = if (isBoundary) boundaryStroke else normalStroke
                    val y = (rowPx * (index + 1)).coerceAtMost(size.height - stroke / 2f)
                    drawLine(
                        color = if (isBoundary) GridLineBoundaryColor else GridLineColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = stroke
                    )
                }
            }
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
                    marker = adjustmentMarkerFor(
                        occurrence = activeBlock.occurrence,
                        courseTitle = activeBlock.course.title,
                        weekNumber = weekNumber,
                        adjustments = adjustments
                    ),
                    modifier = Modifier
                        .offset(
                            x = dayWidth * (activeBlock.occurrence.dayOfWeek - 1) + 2.dp,
                            // 行号直接从可见行列表查，不再自己按节次号推算偏移：
                            // 「隐藏中午时减 3」与 visibleRows 是同一规则的两种写法，分开写迟早漂移。
                            y = rowHeight * (
                                scheduleRowIndex(activeBlock.occurrence.startSection, visibleRows) ?: 0
                            ) + 3.dp
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
    /** 「调」/「补」角标文本；为空表示这张卡不是调课/补课产生的。 */
    marker: String? = null,
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

        if (marker != null) {
            // 左下角与右下角的冲突角标互不干涉。字色按卡片底色自适应：底色是任意可选的，
            // 固定字色必然会在某些卡上糊掉（详见 readableMarkerColor 的注释）。
            Text(
                text = marker,
                modifier = Modifier.align(Alignment.BottomStart),
                color = Color(android.graphics.Color.parseColor(readableMarkerColor(block.course.colorHex))),
                fontSize = 10.sp,
                lineHeight = 10.sp,
                fontStyle = FontStyle.Italic,
                fontWeight = FontWeight.ExtraBold
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
