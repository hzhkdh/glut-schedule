package com.glut.schedule.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    val selectedYear by viewModel.selectedYear.collectAsState()
    val availableYears by viewModel.availableYears.collectAsState()

    val allScores = uiState.scores
    val filteredScores = if (selectedYear == null) allScores
    else allScores.filter { it.year == selectedYear }

    val grouped = remember(filteredScores) {
        filteredScores
            .groupBy { Pair(it.year, it.term) }
            .entries
            .sortedByDescending { "${it.key.first}-${it.key.second}" }
    }

    val listState = rememberLazyListState()

    LaunchedEffect(selectedYear) {
        if (grouped.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

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

        // Filter chips
        if (availableYears.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 14.dp)
            ) {
                item {
                    FilterChip(
                        label = "全部学期",
                        isSelected = selectedYear == null,
                        onClick = { viewModel.selectYear(null) }
                    )
                }
                items(availableYears) { year ->
                    FilterChip(
                        label = year,
                        isSelected = selectedYear == year,
                        onClick = { viewModel.selectYear(year) }
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
        } else if (filteredScores.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text("该学年暂无成绩", color = ScoreSecondary, fontSize = 14.sp)
            }
        } else {
            // Semester blocks
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(grouped, key = { "semester_${it.key.first}_${it.key.second}" }) { (yearTerm, termScores) ->
                    val avgGpa = termScores
                        .filter { it.category == "必修" }
                        .let { req ->
                            val totalCredit = req.sumOf { it.credit }
                            if (totalCredit > 0) req.sumOf { it.credit * it.gpa } / totalCredit
                            else 0.0
                        }
                    SemesterBlock(
                        year = yearTerm.first,
                        term = yearTerm.second,
                        avgGpa = avgGpa,
                        scores = termScores
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
                        AlertDialog(
                            onDismissRequest = { showGpaInfo = false },
                            title = { Text("绩点计算说明", color = ScorePrimary, fontSize = 16.sp) },
                            text = {
                                Text(
                                    "计算公式：\n\n" +
                                    "GPA = Σ(必修课学分 × 绩点) ÷ Σ(必修课学分)\n\n" +
                                    "• 仅统计选课属性为「必修」的课程\n" +
                                    "• 限选、任选课不参与计算\n" +
                                    "• 不及格课程绩点为 0，仍计入分母\n" +
                                    "• 同一门课多次修读，分子取最高分，分母累加\n\n" +
                                    "数据来源：桂林理工大学教务处",
                                    color = ScoreSecondary,
                                    fontSize = 14.sp
                                )
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
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
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
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun SemesterBlock(
    year: String,
    term: Int,
    avgGpa: Double,
    scores: List<ScoreInfo>
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
                    "$year 第${term}学期",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "GPA ${String.format("%.2f", avgGpa)}",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp
                )
            }

            // Column headers
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                Text("课程名称", color = ScoreSecondary, fontSize = 11.sp, modifier = Modifier.weight(2.8f))
                Text("学分", color = ScoreSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Text("成绩", color = ScoreSecondary, fontSize = 11.sp, modifier = Modifier.weight(1.2f), textAlign = TextAlign.Center)
                Text("绩点", color = ScoreSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            }

            // Score rows
            scores.forEachIndexed { index, score ->
                ScoreRow(
                    score = score,
                    showDivider = index < scores.lastIndex
                )
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

private fun formatCredit(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else String.format("%.1f", value)
