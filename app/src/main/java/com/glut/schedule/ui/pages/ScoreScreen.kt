package com.glut.schedule.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
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
import com.glut.schedule.data.model.ScoreInfo

@Composable
fun ScoreScreen(
    viewModel: ScoreViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    // Group scores by year+term
    val groupedScores = uiState.scores
        .groupBy { Pair(it.year, it.term) }
        .entries
        .sortedByDescending { it.key.first + it.key.second.toString() }

    Scaffold(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .navigationBarsPadding(),
        containerColor = Color(0xFF0B1622)
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "考试成绩", color = Color.White, fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.weight(1f).padding(start = 12.dp)
                )
                IconButton(
                    onClick = viewModel::refreshScores, enabled = !uiState.isRefreshing,
                    modifier = Modifier.size(44.dp),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White, disabledContentColor = Color.Gray)
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新", modifier = Modifier.size(24.dp))
                }
            }

            if (uiState.isRefreshing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF4ADE80), trackColor = Color(0xFF1A2E1A)
                )
            }

            if (uiState.message.isNotBlank()) {
                Text(uiState.message, color = Color(0xFF8A93A3), fontSize = 14.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
            }

            if (uiState.scores.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        if (uiState.hasCookie) "暂无成绩数据\n请点击刷新按钮获取" else "请先在导入课表页面登录教务系统",
                        color = Color(0xFF8A93A3), fontSize = 14.sp, textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    groupedScores.forEach { (yearTerm, termScores) ->
                        val (year, term) = yearTerm
                        item(key = "header_${year}_$term") {
                            val avgGpa = termScores.map { it.gpa }.filter { it > 0 }.average().let {
                                if (it.isNaN()) 0.0 else it
                            }
                            val totalCredit = termScores.sumOf { it.credit }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${year} 第${term}学期",
                                    color = Color(0xFF7DD3FC), fontSize = 16.sp, fontWeight = FontWeight.Bold
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text("绩点 ${String.format("%.2f", avgGpa)}", color = Color(0xFF8A93A3), fontSize = 13.sp)
                                    Text("学分 $totalCredit", color = Color(0xFF8A93A3), fontSize = 13.sp)
                                }
                            }
                        }
                        item(key = "headerRow_${year}_$term") {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("课程名称", color = Color(0xFF4A5568), fontSize = 12.sp, modifier = Modifier.weight(2.5f))
                                Text("成绩", color = Color(0xFF4A5568), fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                                Text("绩点", color = Color(0xFF4A5568), fontSize = 12.sp, modifier = Modifier.weight(0.8f), textAlign = TextAlign.Center)
                                Text("学分", color = Color(0xFF4A5568), fontSize = 12.sp, modifier = Modifier.weight(0.7f), textAlign = TextAlign.Center)
                            }
                        }
                        items(termScores, key = { it.id }) { score ->
                            ScoreRow(score)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScoreRow(score: ScoreInfo) {
    val gpaColor = when {
        score.gpa >= 3.7 -> Color(0xFF4ADE80)
        score.gpa >= 3.0 -> Color(0xFF7DD3FC)
        score.gpa >= 2.0 -> Color(0xFFFBBF24)
        score.gpa > 0 -> Color(0xFFF87171)
        else -> Color(0xFF8A93A3)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF172033),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(2.5f)) {
                Text(score.courseName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                if (score.category.isNotBlank()) {
                    Text(score.category, color = Color(0xFF4A5568), fontSize = 11.sp)
                }
            }
            Text(score.score, color = gpaColor, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Text(if (score.gpa > 0) String.format("%.1f", score.gpa) else "-", color = gpaColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.8f), textAlign = TextAlign.Center)
            Text(if (score.credit > 0) String.format("%.1f", score.credit) else "-", color = Color(0xFF8A93A3), fontSize = 13.sp, modifier = Modifier.weight(0.7f), textAlign = TextAlign.Center)
        }
    }
}
