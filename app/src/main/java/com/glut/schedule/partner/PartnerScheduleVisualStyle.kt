package com.glut.schedule.partner

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal data class PartnerCourseCardStyle(
    val surface: Color,
    val content: Color
)

/**
 * TA 课表固定使用浅色粉蓝卡片，因此在这里同时定义背景与前景，
 * 避免深色系统主题把白色内容色继承到浅色表面上。
 */
internal object PartnerScheduleVisualStyle {
    val pink = Color(0xFFF7B6CA)
    val pinkSurface = Color(0xFFFFDDE7)
    val pinkContent = Color(0xFF7A2947)
    val blue = Color(0xFF91BDEB)
    val blueSurface = Color(0xFFD5E8FB)
    val blueContent = Color(0xFF174A7A)

    val pageBackground = Color(0xFFFFFAF8)
    val pagePrimaryText = Color(0xFF202127)
    val emptyCell = Color(0xFFF1F1F3)

    val inviteSurface = Color.White
    val inviteContent = Color(0xFF202127)
    val inviteAction = blueContent

    val overlapBadgeSize = 22.dp

    fun courseCard(identityColor: PartnerIdentityColor): PartnerCourseCardStyle =
        if (identityColor == PartnerIdentityColor.PINK) {
            PartnerCourseCardStyle(surface = pinkSurface, content = pinkContent)
        } else {
            PartnerCourseCardStyle(surface = blueSurface, content = blueContent)
        }

    /**
     * 部分重合角标固定在右上角，仅在顶部留位，避免持续压缩窄卡片的正文宽度。
     */
    fun cardContentTopPadding(kind: PartnerOverlapKind): Dp =
        if (kind == PartnerOverlapKind.PARTIAL) overlapBadgeSize + 2.dp else 0.dp
}
