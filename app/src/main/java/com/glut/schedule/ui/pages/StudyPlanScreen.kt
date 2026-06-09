package com.glut.schedule.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glut.schedule.data.model.StudyPlanGroup

private val SPPrimary = Color(0xFF141821)
private val SPSecondary = Color(0xFF667085)
private val SPPageBg = Color(0xFFF6F4EF)
private val SPHeaderBg = Color(0xFF141821)
private val SPRowBg = Color(0xFFFFFEFB)
private val SPRowAlt = Color(0xFFF9F7F1)
private val SPPassGreen = Color(0xFF2D9A72)
private val SPFailRed = Color(0xFFDC2626)

data class StudyPlanUiState(
    val groups: List<StudyPlanGroup> = emptyList(),
    val isRefreshing: Boolean = false,
    val message: String = "",
    val hasCookie: Boolean = false,
    val cookieValue: String = ""
)

// Split long group names at the first full-width parenthesis for cleaner wrapping
private fun wrapName(name: String): String {
    val idx = name.indexOf('（') // full-width "（"
    if (idx > 0 && name.length - idx > 3) return name.substring(0, idx) + "\n" + name.substring(idx)
    return name
}

@Composable
fun StudyPlanScreen(
    viewModel: StudyPlanViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    // Auto-refresh ONLY on account switch (cookie value change), not on every page visit
    var prevCookie by remember { mutableStateOf("") }
    LaunchedEffect(uiState.cookieValue) {
        if (uiState.hasCookie && !uiState.isRefreshing && uiState.cookieValue != prevCookie && prevCookie.isNotEmpty()) {
            viewModel.refresh()
        }
        prevCookie = uiState.cookieValue
    }

    // Responsive: bigger font on wider screens
    val screenW = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val fs = if (screenW >= 420) 12.sp else 11.sp
    val hdr = if (screenW >= 420) 10.sp else 9.sp
    val pad = if (screenW >= 420) 2.dp else 0.dp

    Column(modifier = modifier.fillMaxSize().background(SPPageBg)) {
        if (uiState.isRefreshing) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF3F7DF6),
                trackColor = Color(0xFF3F7DF6).copy(alpha = 0.12f)
            )
        }
        if (uiState.message.isNotBlank()) {
            Text(uiState.message, color = SPSecondary, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
        }

        if (uiState.groups.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    if (uiState.hasCookie) "暂无教学计划数据\n请点击刷新按钮获取"
                    else "请先在导入课表页面登录教务系统",
                    color = SPSecondary, fontSize = 14.sp, textAlign = TextAlign.Center
                )
            }
        } else {
            // Header: name wider, number columns tighter, attribute left-aligned
            Row(modifier = Modifier.fillMaxWidth().background(SPHeaderBg).padding(vertical = 6.dp, horizontal = pad)) {
                HeaderCell("课组名称", 3f, fontSize = hdr, align = TextAlign.Start, startPad = 4.dp, endPad = 0.dp)
                HeaderCell("属性", 1.1f, fontSize = hdr, startPad = 2.dp, endPad = 0.dp)
                HeaderCell("学分要求", 1.25f, fontSize = hdr, startPad = 0.dp, endPad = 0.dp)
                HeaderCell("已获学分", 1.25f, fontSize = hdr, startPad = 0.dp, endPad = 0.dp)
                HeaderCell("门数要求", 1.25f, fontSize = hdr, startPad = 0.dp, endPad = 0.dp)
                HeaderCell("通过门数", 1.25f, fontSize = hdr, startPad = 0.dp, endPad = 0.dp)
                HeaderCell("是否合格", 1.25f, fontSize = hdr)
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(uiState.groups) { index, group ->
                    val bg = if (index % 2 == 0) SPRowBg else SPRowAlt
                    val displayName = wrapName(group.groupName)
                    StudyPlanRow(displayName, group, bg, fs, pad)
                    if (index < uiState.groups.lastIndex) {
                        HorizontalDivider(color = Color(0xFFEDE8DE), thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.HeaderCell(
    text: String, w: Float, fontSize: androidx.compose.ui.unit.TextUnit,
    align: TextAlign = TextAlign.Center, startPad: Dp = 0.dp, endPad: Dp = 0.dp
) {
    Text(text, color = Color.White, fontSize = fontSize, fontWeight = FontWeight.SemiBold,
        textAlign = align, maxLines = 1,
        modifier = Modifier.weight(w).padding(start = startPad, end = endPad))
}

@Composable
private fun StudyPlanRow(
    displayName: String, group: StudyPlanGroup, bg: Color,
    fontSize: androidx.compose.ui.unit.TextUnit, pad: Dp
) {
    val attrColor = when (group.attribute) {
        "必修" -> Color(0xFF3F7DF6)
        "限选" -> Color(0xFFD97706)
        else -> Color(0xFF16A34A)
    }

    Row(modifier = Modifier.fillMaxWidth().background(bg).padding(vertical = 6.dp, horizontal = pad),
        verticalAlignment = Alignment.CenterVertically) {
        DataCell(displayName, 3f, fontSize, bold = true, align = TextAlign.Start, startPad = 4.dp)
        DataCell(group.attribute, 1.1f, fontSize, color = attrColor,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(attrColor.copy(alpha = 0.12f)).padding(horizontal = 2.dp, vertical = 1.dp),
            startPad = 2.dp)
        DataCell(formatNum(group.creditRequired), 1.25f, fontSize)
        DataCell(formatNum(group.creditEarned), 1.25f, fontSize)
        DataCell("${group.countRequired}", 1.25f, fontSize)
        DataCell("${group.countPassed}", 1.25f, fontSize)
        Box(modifier = Modifier.weight(1.25f), contentAlignment = Alignment.Center) {
            if (group.isPassed)
                Icon(Icons.Outlined.CheckCircle, "通过", tint = SPPassGreen, modifier = Modifier.size(16.dp))
            else
                Icon(Icons.Outlined.Cancel, "未通过", tint = SPFailRed, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun RowScope.DataCell(
    text: String, w: Float, fontSize: androidx.compose.ui.unit.TextUnit,
    color: Color = SPPrimary, bold: Boolean = false, modifier: Modifier = Modifier,
    align: TextAlign = TextAlign.Center, startPad: Dp = 0.dp
) {
    Text(text, color = color, fontSize = fontSize,
        fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal,
        textAlign = align, softWrap = true,
        modifier = modifier.weight(w).padding(start = startPad, end = 0.dp))
}

private fun formatNum(value: Double): String {
    if (value == value.toLong().toDouble()) return value.toLong().toString()
    return String.format("%.2f", value).trimEnd('0').trimEnd('.')
}
