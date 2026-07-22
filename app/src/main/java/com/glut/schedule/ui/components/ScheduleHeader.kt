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

@Composable
fun ScheduleHeader(
    week: ScheduleWeek,
    today: LocalDate,
    currentWeekNumber: Int,
    onWeekTitleClick: () -> Unit,
    onRefreshClick: () -> Unit,
    semesterLabel: String,
    semesters: List<AcademicSemester>,
    isHistorical: Boolean,
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
                    .clickable(onClick = onWeekTitleClick),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
            ) {
                Text(
                    text = scheduleHeaderPrimaryText(week.number, currentWeekNumber, dayLabel),
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable { semesterMenuExpanded = true }
        ) {
            Text(
                text = buildString {
                    append(semesterLabel.ifBlank { "选择学期" })
                    if (isHistorical) append(" · 只读")
                    append("  ▾")
                },
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 56.dp, end = 12.dp, top = 14.dp, bottom = 12.dp)
            )
            DropdownMenu(
                expanded = semesterMenuExpanded,
                onDismissRequest = { semesterMenuExpanded = false }
            ) {
                semesters
                    .filter { it.isCurrent || it.cacheStatus == SemesterCacheStatus.CACHED }
                    .forEach { semester ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(semester.displayName, modifier = Modifier.weight(1f))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(if (semester.isCurrent) "当前" else "已缓存", fontSize = 12.sp)
                                }
                            },
                            onClick = {
                                semesterMenuExpanded = false
                                onSemesterSelected(semester.id)
                            }
                        )
                    }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("管理与下载其他学期") },
                    onClick = {
                        semesterMenuExpanded = false
                        onManageSemesters()
                    }
                )
            }
        }
    }
}

internal fun scheduleHeaderPrimaryText(
    weekNumber: Int,
    currentWeekNumber: Int,
    dayLabel: String
): String {
    return if (weekNumber == currentWeekNumber) {
        "第${weekNumber}周 $dayLabel"
    } else {
        "第${weekNumber}周(非本周)"
    }
}

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
