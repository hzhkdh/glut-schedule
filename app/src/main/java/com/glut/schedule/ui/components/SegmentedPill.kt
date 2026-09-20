package com.glut.schedule.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 选中态的默认强调色，与导入页、上课时间页保持一致。 */
private val DefaultAccent = Color(0xFF3F7DF6)
private val DefaultUnselectedText = Color(0xFF667085)
private val DefaultBorder = Color(0xFFDDE2EA)

/**
 * 二选一分段按钮（Pill 风格）。
 *
 * 从「上课时间」页的雁山/屏风切换抽取而来，供导入线路（模式1 / 模式2）等场景复用，
 * 使全应用的二选一控件保持同一套视觉语言。颜色可覆盖以适配不同页面的配色。
 */
@Composable
fun SegmentedPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = DefaultAccent,
    selectedTextColor: Color = Color.White,
    unselectedTextColor: Color = DefaultUnselectedText,
    borderColor: Color = DefaultBorder
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = if (selected) accent else Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        border = if (selected) null else BorderStroke(1.dp, borderColor)
    ) {
        Text(
            text = label,
            color = if (selected) selectedTextColor else unselectedTextColor,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            textAlign = TextAlign.Center
        )
    }
}

/** 等宽铺满一行的分段按钮组；[selectedIndex] 越界时不选中任何一项。 */
@Composable
fun SegmentedPillRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = DefaultAccent,
    unselectedTextColor: Color = DefaultUnselectedText,
    borderColor: Color = DefaultBorder
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        options.forEachIndexed { index, label ->
            SegmentedPill(
                label = label,
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                modifier = Modifier.weight(1f),
                accent = accent,
                unselectedTextColor = unselectedTextColor,
                borderColor = borderColor
            )
        }
    }
}
