package com.glut.schedule.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.PeopleAlt
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.glut.schedule.data.settings.CampusCapabilities
import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.data.settings.MenuCapability
import com.glut.schedule.data.settings.capabilitiesFor

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
    CampusInfo("校园信息", Icons.Outlined.AccountBalance),
    CourseTimeStats("课时统计", Icons.Outlined.QueryStats),
    PartnerSchedule("情侣/基友课表", Icons.Outlined.PeopleAlt),
    Finance("财务", Icons.Outlined.AccountBalanceWallet),
    StudyPlan("教学计划", Icons.Outlined.MenuBook),
    Import("导入课表", Icons.Outlined.Download),
    Settings("设置", Icons.Outlined.Settings),
    Notice("通知", Icons.Outlined.Notifications),
    SemesterOverview("学期概览", Icons.Outlined.DateRange),
    FAQ("常见问题", Icons.Outlined.HelpOutline),
    About("关于", Icons.Outlined.Info);
}

internal fun campusDrawerItems(campus: CampusType): List<DrawerItem> = when (campus) {
    CampusType.GUILIN -> listOf(
        DrawerItem.CourseTimeStats,
        DrawerItem.CampusInfo,
        DrawerItem.PartnerSchedule,
        DrawerItem.Finance
    )
    CampusType.NANNING -> listOf(
        DrawerItem.CourseTimeStats,
        DrawerItem.CampusInfo,
        DrawerItem.PartnerSchedule,
        DrawerItem.Finance
    )
    // 研究生：门户没有这些模块，但按需求「保留但禁用」——入口照常出现在同一位置，
    // 置灰并提示「研究生暂不支持」，而不是像南宁那样整项消失。
    CampusType.GRADUATE -> listOf(
        DrawerItem.CourseTimeStats,
        DrawerItem.CampusInfo,
        DrawerItem.PartnerSchedule,
        DrawerItem.Finance
    )
}

/**
 * 菜单项对应的能力；返回 null 表示与教务数据无关，任何来源都可用。
 *
 * 把「哪一项依赖哪种能力」集中在这里，新增来源时只需在 `capabilitiesFor` 里补一条，
 * 不必再到每个渲染点写校区判断。
 */
internal fun DrawerItem.requiredCapability(): MenuCapability? = when (this) {
    DrawerItem.Score -> MenuCapability.SCORE
    DrawerItem.ProfessionalScore -> MenuCapability.PROFESSIONAL_SCORE
    DrawerItem.Exam -> MenuCapability.EXAM
    DrawerItem.GradeExam -> MenuCapability.GRADE_EXAM
    DrawerItem.FitnessScore -> MenuCapability.FITNESS_SCORE
    DrawerItem.CampusInfo -> MenuCapability.CAMPUS_INFO
    DrawerItem.CourseTimeStats -> MenuCapability.COURSE_TIME_STATS
    DrawerItem.PartnerSchedule -> MenuCapability.PARTNER_SCHEDULE
    DrawerItem.Finance -> MenuCapability.FINANCE
    DrawerItem.StudyPlan -> MenuCapability.STUDY_PLAN
    DrawerItem.Schedule,
    DrawerItem.Import,
    DrawerItem.Settings,
    DrawerItem.Notice,
    DrawerItem.SemesterOverview,
    DrawerItem.FAQ,
    DrawerItem.About -> null
}

internal fun DrawerItem.isEnabledFor(campus: CampusType): Boolean =
    requiredCapability()?.let { capabilitiesFor(campus).menuEnabled(it) } ?: true

/** 不可用时的提示文案；可用时为 null。 */
internal fun DrawerItem.unsupportedHintFor(campus: CampusType): String? =
    if (isEnabledFor(campus)) null else CampusCapabilities.UNSUPPORTED_HINT

internal val otherDrawerItems = listOf(
    DrawerItem.Settings,
    DrawerItem.Notice,
    DrawerItem.FAQ,
    DrawerItem.About
)

internal fun prepareDrawerSelection(
    current: DrawerItem,
    target: DrawerItem,
    onProfessionalScoreEntered: () -> Unit
): DrawerItem {
    if (target == DrawerItem.ProfessionalScore && current != target) {
        onProfessionalScoreEntered()
    }
    return target
}
