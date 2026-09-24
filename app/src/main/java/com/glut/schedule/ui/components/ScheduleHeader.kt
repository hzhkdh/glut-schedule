package com.glut.schedule.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glut.schedule.data.model.AcademicSemester
import com.glut.schedule.data.model.ScheduleWeek
import com.glut.schedule.data.model.SemesterCacheStatus
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// 乳白菜单配色：与导入页下拉（DirectLoginScreen 的 LoginCardBg / LoginPrimary）同值。
// 那几个常量在那边是文件私有的，而本仓库的惯例本就是各文件自带同值常量
//（StatsCardBg、WidgetCard、SegmentedPill.DefaultActiveBackground 皆如此），
// 所以这里同样声明一份，不为此去新建主题层——那会波及 20+ 处已经用上乳白的页面。
private val MenuCardBg = Color(0xFFFFFEFB)
private val MenuTextPrimary = Color(0xFF141821)
private val MenuTextSecondary = Color(0xFF667085)
private val MenuAccent = Color(0xFF3F7DF6)
private val MenuDivider = Color(0xFFE5E7EB)

/** 「正在查看」标签文案，单独提出来供渲染分支与测试共用，避免两处各写一份字面量。 */
internal const val SEMESTER_MENU_STATUS_VIEWING = "正在查看"

/**
 * 学期下拉每行右侧的状态标签。
 *
 * 「正在查看」只在**非当前学期**上出现：当前学期永远显示「当前」。这样用户既能一眼看出
 * 自己在看哪一个学期，也不会因此丢掉「哪一个是当前学期」这个信息。
 */
internal fun semesterMenuStatusText(
    semester: AcademicSemester,
    viewedSemesterId: String?
): String = when {
    !semester.isCurrent && semester.id == viewedSemesterId -> SEMESTER_MENU_STATUS_VIEWING
    semester.isCurrent -> "当前"
    else -> "已缓存"
}

@Composable
fun ScheduleHeader(
    week: ScheduleWeek,
    today: LocalDate,
    currentWeekNumber: Int,
    onWeekTitleClick: () -> Unit,
    onRefreshClick: () -> Unit,
    semesters: List<AcademicSemester>,
    isHistorical: Boolean,
    viewedSemesterId: String? = null,
    onSemesterSelected: (String) -> Unit,
    onManageSemesters: () -> Unit,
    onReturnToCurrentClick: () -> Unit,
    onDrawerOpen: () -> Unit = {},
    isRefreshing: Boolean = false,
    modifier: Modifier = Modifier
) {
    var semesterMenuExpanded by remember { mutableStateOf(false) }
    val formatter = DateTimeFormatter.ofPattern("yyyy/M/d")
    val dayLabel = today.dayLabel()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onDrawerOpen,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
            ) {
                Icon(Icons.Outlined.Menu, contentDescription = "菜单", modifier = Modifier.size(22.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .padding(end = 8.dp)
                    .widthIn(min = 0.dp)
                    .then(
                        if (isWeekTitleClickable(isHistorical)) {
                            Modifier.clickable(onClick = onWeekTitleClick)
                        } else Modifier
                    ),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
            ) {
                Text(
                    text = scheduleHeaderPrimaryText(
                        week.number,
                        currentWeekNumber,
                        dayLabel,
                        isHistorical
                    ),
                    color = Color.White,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = today.format(formatter),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable { semesterMenuExpanded = true },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "▾",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                DropdownMenu(
                    expanded = semesterMenuExpanded,
                    onDismissRequest = { semesterMenuExpanded = false },
                    containerColor = MenuCardBg
                ) {
                    semesters
                        .filter { it.isCurrent || it.cacheStatus == SemesterCacheStatus.CACHED }
                        .forEach { semester ->
                            val statusText = semesterMenuStatusText(semester, viewedSemesterId)
                            // 正在查看的那一行用强调色，其余用次文字色——否则在乳白底上
                            //「已缓存」会和课程名抢注意力，看不出哪一行是「你现在正在看的」。
                            val isViewing = statusText == SEMESTER_MENU_STATUS_VIEWING
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(semester.displayName, modifier = Modifier.weight(1f))
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            statusText,
                                            fontSize = 12.sp,
                                            color = if (isViewing) MenuAccent else MenuTextSecondary
                                        )
                                    }
                                },
                                colors = MenuDefaults.itemColors(textColor = MenuTextPrimary),
                                onClick = {
                                    semesterMenuExpanded = false
                                    onSemesterSelected(semester.id)
                                }
                            )
                        }
                    HorizontalDivider(color = MenuDivider)
                    DropdownMenuItem(
                        text = { Text("管理与下载其他学期") },
                        colors = MenuDefaults.itemColors(textColor = MenuTextPrimary),
                        onClick = {
                            semesterMenuExpanded = false
                            onManageSemesters()
                        }
                    )
                }
            }

            if (isHistorical) {
                IconButton(
                    onClick = onReturnToCurrentClick,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "返回当前学期",
                        modifier = Modifier.size(21.dp)
                    )
                }
            } else {
                IconButton(
                    onClick = onRefreshClick,
                    enabled = !isRefreshing,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(19.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新课表", modifier = Modifier.size(21.dp))
                    }
                }
            }
        }
    }
}

internal fun scheduleHeaderPrimaryText(
    weekNumber: Int,
    currentWeekNumber: Int,
    dayLabel: String,
    isHistorical: Boolean = false
): String {
    return if (isHistorical) {
        "第${weekNumber}周"
    } else if (weekNumber == currentWeekNumber) {
        "第${weekNumber}周 $dayLabel"
    } else {
        "第${weekNumber}周(非本周)"
    }
}

internal fun isWeekTitleClickable(isHistorical: Boolean): Boolean = !isHistorical

private fun LocalDate.dayLabel(): String {
    return when (dayOfWeek) {
        DayOfWeek.MONDAY -> "周一"
        DayOfWeek.TUESDAY -> "周二"
        DayOfWeek.WEDNESDAY -> "周三"
        DayOfWeek.THURSDAY -> "周四"
        DayOfWeek.FRIDAY -> "周五"
        DayOfWeek.SATURDAY -> "周六"
        DayOfWeek.SUNDAY -> "周日"
    }
}
