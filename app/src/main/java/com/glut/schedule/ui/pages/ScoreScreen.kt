package com.glut.schedule.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glut.schedule.data.model.ScoreInfo

private val ScorePrimary = Color(0xFF141821)
private val ScoreSecondary = Color(0xFF667085)
private val ScoreAccent = Color(0xFF3F7DF6)
private val ScorePageBg = Color(0xFFF6F4EF)
private val ScoreCardBg = Color(0xFFFFFEFB)
private val ScoreChipBg = Color(0xFFE8E4D6)
private val ScoreSemesterHeaderBg = Color(0xFF141821)

@Composable
fun ScoreScreen(
    viewModel: ScoreViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    val allScores = uiState.scores

    // Build academic-year groups from ALL scores
    val allGroups = remember(allScores) {
        allScores
            .groupBy { score ->
                val y = score.year.toIntOrNull() ?: 0
                if (score.term == 2) y else y - 1  // 秋=2 marks学年起点, 春=1 → year-1
            }
            .map { (startYear, scores) ->
                AcademicYearGroup(
                    startYear = startYear,
                    autumnScores = scores.filter { it.term == 2 }.sortedBy { it.courseName },
                    springScores = scores.filter { it.term == 1 }.sortedBy { it.courseName }
                )
            }
            .sortedBy { it.startYear }
    }

    var selectedStartYear by remember { mutableStateOf<Int?>(null) }
    // Default to latest year on first load (last in ascending list)
    LaunchedEffect(allGroups) {
        if (selectedStartYear == null && allGroups.isNotEmpty()) {
            selectedStartYear = allGroups.last().startYear
        }
    }
    val selectedGroup = allGroups.find { it.startYear == selectedStartYear }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ScorePageBg)
    ) {
        if (uiState.isRefreshing) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = ScoreAccent,
                trackColor = ScoreAccent.copy(alpha = 0.12f)
            )
        }

        if (uiState.message.isNotBlank()) {
            Text(
                uiState.message,
                color = ScoreSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
            )
        }

        // Academic year filter chips
        if (allGroups.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                allGroups.forEach { group ->
                    FilterChip(
                        label = "${group.startYear}-${group.startYear + 1}",
                        isSelected = group.startYear == selectedStartYear,
                        onClick = { selectedStartYear = group.startYear },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Empty states
        if (allScores.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (uiState.hasCookie) "暂无成绩数据\n请点击刷新按钮获取"
                    else "请先在导入课表页面登录教务系统",
                    color = ScoreSecondary,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else if (selectedGroup == null) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text("该学年暂无成绩", color = ScoreSecondary, fontSize = 14.sp)
            }
        } else {
            // Single academic year card
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                item(key = "year_${selectedGroup.startYear}") {
                    AcademicYearBlock(
                        startYear = selectedGroup.startYear,
                        gpa = selectedGroup.allGpa,
                        autumnScores = selectedGroup.autumnScores,
                        springScores = selectedGroup.springScores
                    )
                }

                // Bottom summary
                item {
                    var showGpaInfo by remember { mutableStateOf(false) }
                    val overallGpa = allScores
                        .filter { it.category == "必修" }
                        .let { req ->
                            val totalCredit = req.sumOf { it.credit }
                            if (totalCredit > 0) req.sumOf { it.credit * it.gpa } / totalCredit
                            else 0.0
                        }
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        color = ScoreSemesterHeaderBg,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "平均学分绩点 ${String.format("%.2f", overallGpa)}",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "ℹ",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 14.sp,
                                modifier = Modifier.clickable { showGpaInfo = true }
                            )
                        }
                    }
                    if (showGpaInfo) {
                        val uriHandler = LocalUriHandler.current
                        val linkUrl = "https://jwc.glut.edu.cn/xsfw/cjhks1/pjxfjd.htm"
                        AlertDialog(
                            onDismissRequest = { showGpaInfo = false },
                            title = { Text("绩点计算说明", fontSize = 16.sp) },
                            text = {
                                Column {
                                    Text(
                                        "计算公式：\n\n" +
                                        "GPA = Σ(必修课学分 × 绩点) ÷ Σ(必修课学分)\n\n" +
                                        "• 仅统计选课属性为「必修」的课程\n" +
                                        "• 限选、任选课不参与计算\n" +
                                        "• 不及格课程绩点为 0，仍计入分母\n" +
                                        "• 同一门课多次修读，分子取最高分，分母累加",
                                        fontSize = 14.sp
                                    )
                                    Spacer(modifier = Modifier.padding(top = 8.dp))
                                    Row {
                                        Text("数据来源：", fontSize = 14.sp)
                                        Text(
                                            "桂林理工大学教务处",
                                            color = ScoreAccent,
                                            fontSize = 14.sp,
                                            textDecoration = TextDecoration.Underline,
                                            modifier = Modifier.clickable { uriHandler.openUri(linkUrl) }
                                        )
                                    }
                                }
                            },
                            confirmButton = {
                                Text("知道了", color = ScoreAccent, fontSize = 14.sp,
                                    modifier = Modifier.clickable { showGpaInfo = false })
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        color = if (isSelected) ScoreSemesterHeaderBg else ScoreChipBg,
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            label,
            color = if (isSelected) Color.White else ScorePrimary,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp).fillMaxWidth()
        )
    }
}

private data class AcademicYearGroup(
    val startYear: Int,
    val autumnScores: List<ScoreInfo>,
    val springScores: List<ScoreInfo>
) {
    val allGpa: Double get() {
        val required = (autumnScores + springScores).filter { it.category == "必修" }
        val totalCredit = required.sumOf { it.credit }
        return if (totalCredit > 0) required.sumOf { it.credit * it.gpa } / totalCredit else 0.0
    }
}

@Composable
private fun ScoreColumnHeaders() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Text("课程名称", color = ScoreSecondary, fontSize = 11.sp, modifier = Modifier.weight(2.8f))
        Text("学分", color = ScoreSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        Text("成绩", color = ScoreSecondary, fontSize = 11.sp, modifier = Modifier.weight(1.2f), textAlign = TextAlign.Center)
        Text("绩点", color = ScoreSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
    }
}

@Composable
private fun AcademicYearBlock(
    startYear: Int,
    gpa: Double,
    autumnScores: List<ScoreInfo>,
    springScores: List<ScoreInfo>
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = ScoreCardBg,
        shape = RoundedCornerShape(10.dp)
    ) {
        Column {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ScoreSemesterHeaderBg)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "$startYear-${startYear + 1} 学年",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "GPA ${String.format("%.2f", gpa)}",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp
                )
            }

            // Term 1 (秋)
            TermSection("第1学期", "秋", Color(0xFFD97706), autumnScores)

            // Term 2 (春)
            if (springScores.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    color = Color(0xFFEDE8DE),
                    thickness = 1.dp
                )
                Spacer(Modifier.height(4.dp))
                TermSection("第2学期", "春", Color(0xFF16A34A), springScores)
            }
        }
    }
}

@Composable
private fun TermSection(
    termLabel: String,
    seasonChar: String,
    badgeColor: Color,
    scores: List<ScoreInfo>
) {
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // 彩色左边框
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp))
                .background(badgeColor)
        )
        Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
            // 标题行：学期名 + 季节徽章 + 课程数
            Row(
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(termLabel, color = ScorePrimary, fontSize = 13.sp,
                    fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    seasonChar,
                    color = badgeColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(badgeColor.copy(alpha = 0.12f))
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("${scores.size}门课程", color = ScoreSecondary, fontSize = 12.sp)
            }
            if (scores.isEmpty()) {
                Text(
                    "暂无成绩",
                    color = ScoreSecondary.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                ScoreColumnHeaders()
                scores.forEachIndexed { index, score ->
                    ScoreRow(
                        score = score,
                        showDivider = index < scores.lastIndex
                    )
                }
            }
        }
    }
}



@Composable
private fun ScoreRow(score: ScoreInfo, showDivider: Boolean) {
    val gpaColor = when {
        score.gpa >= 3.7 -> Color(0xFF2D9A72)
        score.gpa >= 3.0 -> Color(0xFF3F7DF6)
        score.gpa >= 2.0 -> Color(0xFFD97706)
        score.gpa > 0 -> Color(0xFFDC2626)
        else -> ScoreSecondary
    }

    val attrColor = when (score.category) {
        "必修" -> Color(0xFF3F7DF6)
        "限选" -> Color(0xFFD97706)
        else -> Color(0xFF16A34A)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(2.8f)) {
            Text(
                score.courseName,
                color = ScorePrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            if (score.category.isNotBlank()) {
                Text(
                    score.category,
                    color = attrColor,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(attrColor.copy(alpha = 0.12f))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
        Text(
            if (score.credit > 0) formatCredit(score.credit) else "-",
            color = ScoreSecondary,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center
        )
        Text(
            score.score,
            color = gpaColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1.2f),
            textAlign = TextAlign.Center
        )
        Text(
            if (score.gpa > 0) String.format("%.1f", score.gpa) else "-",
            color = gpaColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center
        )
    }

    if (showDivider) {
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 8.dp),
            color = ScorePageBg,
            thickness = 0.5.dp
        )
    }
}

private fun formatCredit(value: Double): String {
    if (value == value.toLong().toDouble()) return value.toLong().toString()
    return String.format("%.2f", value).trimEnd('0').trimEnd('.')
}
