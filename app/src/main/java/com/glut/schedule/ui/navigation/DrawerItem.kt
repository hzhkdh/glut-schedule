package com.glut.schedule.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class DrawerItem(
    val title: String,
    val icon: ImageVector
) {
    Schedule("课程表", Icons.Outlined.CalendarMonth),
    Score("考试成绩", Icons.Outlined.Assessment),
    Exam("考试安排", Icons.Outlined.EventNote),
    Import("导入课表", Icons.Outlined.Download),
    Settings("设置", Icons.Outlined.Settings),
    About("关于", Icons.Outlined.Info);
}
