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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glut.schedule.data.model.ProfessionalScoreCourse
import com.glut.schedule.data.model.ProfessionalScoreMissingCourse
import com.glut.schedule.data.model.ProfessionalScoreResult

private val PSPrimary = Color(0xFF141821)
private val PSSecondary = Color(0xFF667085)
private val PSAccent = Color(0xFF3F7DF6)
private val PSPageBg = Color(0xFFF6F4EF)
private val PSCardBg = Color(0xFFFFFEFB)
private val PSChipBg = Color(0xFFE8E4D6)
private val PSDark = Color(0xFF141821)
private val PSSuccess = Color(0xFF16A34A)
private val PSWarning = Color(0xFFD97706)

@Composable
fun ProfessionalScoreScreen(
    viewModel: ProfessionalScoreViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.hasCookie, uiState.hasStudyPlanData, uiState.hasScoreData) {
        if (uiState.hasCookie && (!uiState.hasStudyPlanData || !uiState.hasScoreData)) {
            viewModel.refreshData()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PSPageBg)
    ) {
        if (uiState.isRefreshing) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = PSAccent,
                trackColor = PSAccent.copy(alpha = 0.12f)
            )
        }

        if (uiState.message.isNotBlank()) {
            Text(
                uiState.message,
                color = PSSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
            )
        }

        val result = uiState.result
        when {
            !uiState.hasCookie -> {
                EmptyProfessionalScoreState("请先在导入课表页面登录教务系统")
            }
            uiState.availableAcademicYears.isEmpty() -> {
                EmptyProfessionalScoreState(
                    if (uiState.hasStudyPlanData) {
                        "教学计划中暂无可计算的本学年必修/限选课程"
                    } else {
                        "暂无教学计划和成绩数据\n请点击右上角刷新获取"
                    }
                )
            }
            result == null -> {
                EmptyProfessionalScoreState("请选择一个学年")
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    item(key = "academic_years") {
                        AcademicYearSelector(
                            academicYears = uiState.availableAcademicYears,
                            selectedAcademicYear = uiState.selectedAcademicYear,
                            onSelect = viewModel::selectAcademicYear
                        )
                    }
                    if (uiState.scoreUnavailableReason.isNotBlank()) {
                        item(key = "score_unavailable") {
                            ScoreUnavailableNote(uiState.scoreUnavailableReason)
                        }
                    }
                    item(key = "summary") {
                        ProfessionalScoreSummary(result)
                    }
                    item(key = "rule") {
                        RuleNote()
                    }
                    if (result.courses.isNotEmpty()) {
                        item(key = "included_title") {
                            SectionTitle("参与计算课程", "${result.courses.size} 门")
                        }
                        items(result.courses, key = { "${it.semester}_${it.courseName}_${it.scoreText}" }) { course ->
                            IncludedCourseRow(course)
                        }
                    }
                    if (result.missingCourses.isNotEmpty()) {
                        item(key = "missing_title") {
                            SectionTitle("暂未计入", "${result.missingCourses.size} 门")
                        }
                        items(result.missingCourses, key = { "${it.semester}_${it.courseName}" }) { course ->
                            MissingCourseRow(course)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AcademicYearSelector(
    academicYears: List<String>,
    selectedAcademicYear: String?,
    onSelect: (String) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 2.dp)
    ) {
        items(academicYears, key = { it }) { academicYear ->
            val selected = academicYear == selectedAcademicYear
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .clickable { onSelect(academicYear) },
                color = if (selected) PSDark else PSChipBg,
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(
                    academicYear,
                    color = if (selected) Color.White else PSPrimary,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
        }
    }
}

@Composable
private fun ScoreUnavailableNote(reason: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFFFFF7ED),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            reason,
            color = PSWarning,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            modifier = Modifier.padding(14.dp)
        )
    }
}

@Composable
private fun ProfessionalScoreSummary(result: ProfessionalScoreResult) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = PSDark,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                "${result.academicYear}（${result.includedSemesters.joinToString(" + ")}）",
                color = Color.White.copy(alpha = 0.74f),
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                result.professionalScore?.let { String.format("%.2f", it) } ?: "--",
                color = Color.White,
                fontSize = 38.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "专业学习成绩",
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SummaryPill("课程", "${result.courses.size} 门", Modifier.weight(1f))
                SummaryPill("学分", formatNum(result.totalCredit), Modifier.weight(1f))
                SummaryPill("缺失", "${result.missingCourses.size} 门", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SummaryPill(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = Color.White.copy(alpha = 0.64f), fontSize = 11.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RuleNote() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = PSCardBg,
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            "公式：Σ(课程百分制成绩 × 教学计划学分) ÷ Σ教学计划学分。按一个学年（秋季 + 次年春季）统一计算；课程范围以本专业培养计划当学年应修读课程为准，排除补修、重学/重修、体育、公共选修/任选、辅修、双学位等。五级制按优95、良85、中75、及格65、不及格40折算；补考、缓考、作弊/旷考按测评规则处理。",
            color = PSSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(14.dp)
        )
    }
}

@Composable
private fun SectionTitle(title: String, trailing: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = PSPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(trailing, color = PSSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun IncludedCourseRow(course: ProfessionalScoreCourse) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = PSCardBg,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        course.courseName,
                        color = PSPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(5.dp))
                    CourseMeta(course.groupName, course.attribute)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        String.format("%.1f", course.scoreValue).trimEnd('0').trimEnd('.'),
                        color = scoreColor(course.scoreValue),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${formatNum(course.credit)} 学分",
                        color = PSSecondary,
                        fontSize = 11.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = Color(0xFFEDE8DE), thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("原始成绩：${course.scoreText}", color = PSSecondary, fontSize = 12.sp)
                Text(
                    "加权 ${String.format("%.1f", course.weightedScore)}",
                    color = PSSecondary,
                    fontSize = 12.sp
                )
            }
            if (course.scoreSourceReason.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(course.scoreSourceReason, color = PSSecondary, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun MissingCourseRow(course: ProfessionalScoreMissingCourse) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = PSWarning.copy(alpha = 0.10f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        course.courseName,
                        color = PSPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(5.dp))
                    CourseMeta(course.groupName, course.attribute)
                }
                Text(
                    "${formatNum(course.credit)} 学分",
                    color = PSWarning,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(course.reason, color = PSSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun CourseMeta(groupName: String, attribute: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            attribute,
            color = attrColor(attribute),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(attrColor(attribute).copy(alpha = 0.12f))
                .padding(horizontal = 5.dp, vertical = 2.dp)
        )
        if (groupName.isNotBlank()) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                groupName,
                color = PSSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun EmptyProfessionalScoreState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            message,
            color = PSSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = 21.sp,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}

private fun attrColor(attribute: String): Color = when (attribute) {
    "必修" -> PSAccent
    "限选" -> PSWarning
    else -> PSSuccess
}

private fun scoreColor(score: Double): Color = when {
    score >= 85.0 -> PSSuccess
    score >= 70.0 -> PSAccent
    score >= 60.0 -> PSWarning
    else -> Color(0xFFDC2626)
}

private fun formatNum(value: Double): String {
    if (value == value.toLong().toDouble()) return value.toLong().toString()
    return String.format("%.2f", value).trimEnd('0').trimEnd('.')
}
