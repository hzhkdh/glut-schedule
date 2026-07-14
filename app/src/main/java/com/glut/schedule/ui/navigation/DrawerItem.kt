package com.glut.schedule.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class DrawerItem(
    val title: String,
    val icon: ImageVector
) {
    Schedule("课程表", Icons.Outlined.CalendarMonth),
    Score("考试成绩", Icons.Outlined.Assessment),
    ProfessionalScore("专业成绩", Icons.Outlined.Calculate),
    Exam("考试安排", Icons.Outlined.EventNote),
    GradeExam("考级成绩", Icons.Outlined.EmojiEvents),
    FitnessScore("体测成绩", Icons.Outlined.FitnessCenter),
    StudyPlan("教学计划", Icons.Outlined.MenuBook),
    Import("导入课表", Icons.Outlined.Download),
    Settings("设置", Icons.Outlined.Settings),
    Notice("通知", Icons.Outlined.Notifications),
    SemesterOverview("学期概览", Icons.Outlined.DateRange),
    FAQ("常见问题", Icons.Outlined.HelpOutline),
    About("关于", Icons.Outlined.Info);
}
