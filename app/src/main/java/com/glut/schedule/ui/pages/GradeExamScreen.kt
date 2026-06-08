package com.glut.schedule.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glut.schedule.data.model.GradeExamInfo

private val ScorePrimary = Color(0xFF141821)
private val ScoreSecondary = Color(0xFF667085)
private val ScoreAccent = Color(0xFF3F7DF6)
private val ScorePageBg = Color(0xFFF6F4EF)
private val ScoreCardBg = Color(0xFFFFFEFB)
private val ScoreSemesterHeaderBg = Color(0xFF141821)

private val PassGreen = Color(0xFF2D9A72)
private val FailOrange = Color(0xFFD97706)
private val StatusBlue = Color(0xFF3F7DF6)

@Composable
fun GradeExamScreen(
    viewModel: GradeExamViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

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

        if (uiState.exams.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (uiState.hasCookie) "暂无等级考试记录\n请点击刷新按钮获取"
                    else "请先在导入课表页面登录教务系统",
                    color = ScoreSecondary,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                itemsIndexed(
                    uiState.exams,
                    key = { _, exam -> exam.id }
                ) { index, exam ->
                    GradeExamCard(
                        exam = exam,
                        isLast = index == uiState.exams.lastIndex
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

private fun scoreColorFor(exam: GradeExamInfo): Color {
    val numericScore = exam.score.toDoubleOrNull() ?: return ScoreSecondary
    val isPass = when {
        exam.examName.contains("CET", ignoreCase = true) ||
        exam.examName.contains("大学英语", ignoreCase = true) ||
        exam.examName.contains("四级", ignoreCase = true) ||
        exam.examName.contains("六级", ignoreCase = true) ||
        exam.examName.contains("CET4", ignoreCase = true) ||
        exam.examName.contains("CET6", ignoreCase = true) ||
        exam.examName.contains("英语四", ignoreCase = true) ||
        exam.examName.contains("英语六", ignoreCase = true) -> numericScore >= 425
        else -> numericScore >= 60
    }
    return if (isPass) PassGreen else FailOrange
}

@Composable
private fun GradeExamCard(
    exam: GradeExamInfo,
    isLast: Boolean
) {
    val scoreColor = scoreColorFor(exam)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = ScoreCardBg,
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    exam.examName,
                    color = ScorePrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Status badge
                if (exam.status.isNotBlank()) {
                    StatusBadge(status = exam.status)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    exam.examTime,
                    color = ScoreSecondary,
                    fontSize = 13.sp
                )

                Text(
                    if (exam.score.isNotBlank()) exam.score else "-",
                    color = scoreColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(status: String) {
    val bgColor = when {
        status.contains("通过", ignoreCase = true) -> PassGreen
        status.contains("合格", ignoreCase = true) -> PassGreen
        status.contains("未通过", ignoreCase = true) -> FailOrange
        status.contains("不合格", ignoreCase = true) -> FailOrange
        else -> StatusBlue
    }

    Surface(
        shape = RoundedCornerShape(4.dp),
        color = bgColor.copy(alpha = 0.12f)
    ) {
        Text(
            status,
            color = bgColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
