package com.glut.schedule.partner

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal data class PartnerCourseCardStyle(
    val surface: Color,
    val content: Color
)

/**
 * TA 课表使用固定的浅色身份卡片，因此在这里成对定义背景与前景，
 * 避免深色系统主题把白色内容色继承到浅色表面上。
 */
internal object PartnerScheduleVisualStyle {
    val pagePrimaryText = Color.White
    val pageSecondaryText = Color.White.copy(alpha = 0.72f)

    val manageSheetSurface = Color(0xFFFFF9FB)
    val manageSectionSurface = Color(0xFFFFEFF4)
    val managePrimaryText = Color(0xFF2A2226)
    val manageSecondaryText = Color(0xFF6F6268)
    val manageAccent = Color(0xFFA8325A)

    val inviteSurface = Color.White
    val inviteContent = Color(0xFF202127)
    val inviteAction = manageAccent
    val feedbackSurface = Color(0xFFFCECF2)
    val feedbackContent = Color(0xFF6F2944)

    val overlapBadgeSize = 22.dp

    // 旧版粉蓝专用渲染仍在本轮 UI 重构过程中引用，统一转发到新调色板，避免出现两套色值。
    fun courseCard(identityColor: PartnerIdentityColor): PartnerCourseCardStyle =
        when (identityColor) {
            PartnerIdentityColor.PINK ->
                PartnerCourseCardStyle(surface = Color(0xFFFFDDE7), content = Color(0xFF7A2947))
            PartnerIdentityColor.BLUE ->
                PartnerCourseCardStyle(surface = Color(0xFFD5E8FB), content = Color(0xFF174A7A))
            PartnerIdentityColor.PURPLE ->
                PartnerCourseCardStyle(surface = Color(0xFFE9DDFB), content = Color(0xFF4F2A7F))
            PartnerIdentityColor.TEAL ->
                PartnerCourseCardStyle(surface = Color(0xFFD4F1EE), content = Color(0xFF155A55))
            PartnerIdentityColor.GREEN ->
                PartnerCourseCardStyle(surface = Color(0xFFDDF2D8), content = Color(0xFF2E5E2A))
            PartnerIdentityColor.ORANGE ->
                PartnerCourseCardStyle(surface = Color(0xFFFFE4CC), content = Color(0xFF7A3F0E))
            PartnerIdentityColor.RED ->
                PartnerCourseCardStyle(surface = Color(0xFFFFDAD6), content = Color(0xFF8C1D18))
            PartnerIdentityColor.GOLD ->
                PartnerCourseCardStyle(surface = Color(0xFFF7E7B2), content = Color(0xFF5F4B00))
        }

}
