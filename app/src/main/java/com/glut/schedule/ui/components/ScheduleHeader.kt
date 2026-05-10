package com.glut.schedule.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glut.schedule.data.model.ScheduleWeek
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun ScheduleHeader(
    week: ScheduleWeek,
    today: LocalDate,
    currentWeekNumber: Int,
    onImportClick: () -> Unit,
    onAddClick: () -> Unit,
    onTodayClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val formatter = DateTimeFormatter.ofPattern("yyyy/M/d")
    val dayLabel = today.dayLabel()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
                .widthIn(min = 0.dp)
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
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onTodayClick,
                modifier = Modifier.size(36.dp),
                colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
            ) {
                Icon(Icons.Outlined.Today, contentDescription = "回到本周", modifier = Modifier.size(21.dp))
            }
            IconButton(
                onClick = onAddClick,
                modifier = Modifier.size(36.dp),
                colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "添加课程", modifier = Modifier.size(22.dp))
            }
            IconButton(
                onClick = onImportClick,
                modifier = Modifier.size(36.dp),
                colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
            ) {
                Icon(Icons.Outlined.CloudDownload, contentDescription = "从教务导入课表", modifier = Modifier.size(22.dp))
            }
            IconButton(
                onClick = onMoreClick,
                modifier = Modifier.size(36.dp),
                colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
            ) {
                Icon(Icons.Outlined.MoreHoriz, contentDescription = "更多", modifier = Modifier.size(22.dp))
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
