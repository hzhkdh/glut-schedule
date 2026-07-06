package com.glut.schedule.ui.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Info
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glut.schedule.R
import com.glut.schedule.data.model.CourseStatus
import com.glut.schedule.data.model.StudyPlanGroup
import com.glut.schedule.data.model.StudyPlanGroupWithCourses
import com.glut.schedule.data.model.StudyPlanCourse

private val SPPrimary = Color(0xFF141821)
private val SPSecondary = Color(0xFF667085)
private val SPPageBg = Color(0xFFF6F4EF)
private val SPHeaderBg = Color(0xFF141821)
private val SPRowBg = Color(0xFFFFFEFB)
private val SPRowAlt = Color(0xFFF9F7F1)
private val SPCardBg = Color(0xFFF0EDE5)
private val SPNeutralGray = Color(0xFF9CA3AF)
private val SPLegendBg = Color(0xFFEEECE4)

private fun attrColor(attribute: String): Color = when (attribute) {
    "必修" -> Color(0xFF3F7DF6)
    "限选" -> Color(0xFFD97706)
    else -> Color(0xFF16A34A)
}

private fun wrapName(name: String): String {
    val idx = name.indexOf('（')
    if (idx > 0 && name.length - idx > 3) return name.substring(0, idx) + "\n" + name.substring(idx)
    return name
}

@Composable
fun StudyPlanScreen(
    viewModel: StudyPlanViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    var prevCookie by remember { mutableStateOf("") }
    LaunchedEffect(uiState.cookieValue) {
        if (uiState.hasCookie && !uiState.isRefreshing && uiState.cookieValue != prevCookie) {
            val isFirstLogin = prevCookie.isEmpty()
            val dataMissing = uiState.groups.isEmpty()
            if (!isFirstLogin || dataMissing) {
                viewModel.refresh()
            }
        }
        prevCookie = uiState.cookieValue
    }

    val screenW = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val fs = if (screenW >= 420) 13.sp else 12.sp
    val detailFs = if (screenW >= 420) 12.sp else 11.sp
    val hdr = if (screenW >= 420) 10.sp else 9.sp

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
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth().background(SPHeaderBg)
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HeaderCell("课组名称", 3f, fontSize = hdr, align = TextAlign.Start, startPad = 4.dp)
                HeaderCell("属性", 1.1f, fontSize = hdr, startPad = 2.dp)
                HeaderCell("学分要求", 1.25f, fontSize = hdr)
                HeaderCell("已获学分", 1.25f, fontSize = hdr)
                HeaderCell("门数要求", 1.25f, fontSize = hdr)
                HeaderCell("通过门数", 1.25f, fontSize = hdr)
                HeaderCell("是否合格", 1.25f, fontSize = hdr)
            }

            // Legend bar (below header)
            LegendBar(
                expanded = uiState.showLegend,
                onToggle = { viewModel.toggleLegend() },
                fontSize = detailFs
            )

            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(uiState.groupsWithCourses, key = { _, gwc -> gwc.group.id }) { index, gwc ->
                    val isExpanded = uiState.expandedGroupId == gwc.group.id
                    val bg = if (index % 2 == 0) SPRowBg else SPRowAlt

                    Column(modifier = Modifier.fillMaxWidth().background(bg)) {
                        GroupRow(
                            group = gwc.group,
                            isExpanded = isExpanded,
                            onClick = { viewModel.toggleExpanded(gwc.group.id) },
                            fontSize = fs
                        )

                        AnimatedVisibility(
                            visible = isExpanded,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column {
                                HorizontalDivider(color = Color(0xFFD5D0C4), thickness = 1.dp)
                                gwc.courses.forEachIndexed { ci, course ->
                                    CourseRow(course = course, fontSize = detailFs)
                                    if (ci < gwc.courses.lastIndex) {
                                        HorizontalDivider(
                                            color = Color(0xFFE8E2D6),
                                            thickness = 0.5.dp,
                                            modifier = Modifier.padding(start = 32.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (index < uiState.groupsWithCourses.lastIndex) {
                        HorizontalDivider(color = Color(0xFFEDE8DE), thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendBar(
    expanded: Boolean,
    onToggle: () -> Unit,
    fontSize: androidx.compose.ui.unit.TextUnit
) {
    Column(modifier = Modifier.fillMaxWidth().background(SPLegendBg)) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clickable { onToggle() }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Info, contentDescription = null,
                tint = SPSecondary, modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("状态说明", color = SPSecondary, fontSize = fontSize,
                fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "收起" else "展开",
                tint = SPSecondary, modifier = Modifier.size(16.dp)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                Text("图标说明：", color = SPPrimary, fontSize = fontSize,
                    fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(2.dp))
                LegendIconRow(R.drawable.study_course_pass, "最高成绩已及格", fontSize)
                LegendIconRow(R.drawable.study_course_failed, "最高成绩未及格", fontSize)
                LegendIconRow(R.drawable.study_course_failed_reelect, "不及格再次选课", fontSize)
                LegendIconRow(R.drawable.study_course_pass_reelect, "已及格再次选课", fontSize)
                LegendIconRow(R.drawable.study_course_unelected, "未选课", fontSize)
                LegendIconRow(R.drawable.study_course_unknown_pass, "已选课成绩未知", fontSize)

                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider(color = Color(0xFFD5D0C4), thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(6.dp))

                Text("课程信息说明：", color = SPPrimary, fontSize = fontSize,
                    fontWeight = FontWeight.Medium)
                Text("    学分 · 学时 · 考核方式 · 开课学期",
                    color = SPSecondary, fontSize = fontSize)
                Text("    例：0.5学分 · 8学时 · 考查 · 2024秋",
                    color = SPNeutralGray, fontSize = fontSize,
                    modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

@Composable
private fun LegendIconRow(
    iconRes: Int, label: String,
    fontSize: androidx.compose.ui.unit.TextUnit
) {
    Row(
        modifier = Modifier.padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, color = SPSecondary, fontSize = fontSize)
    }
}

@Composable
private fun RowScope.HeaderCell(
    text: String, w: Float, fontSize: androidx.compose.ui.unit.TextUnit,
    align: TextAlign = TextAlign.Center, startPad: Dp = 0.dp
) {
    Text(text, color = Color.White, fontSize = fontSize, fontWeight = FontWeight.SemiBold,
        textAlign = align, maxLines = 1,
        modifier = Modifier.weight(w).padding(start = startPad, end = 0.dp))
}

@Composable
private fun GroupRow(
    group: StudyPlanGroup,
    isExpanded: Boolean,
    onClick: () -> Unit,
    fontSize: androidx.compose.ui.unit.TextUnit
) {
    val aColor = attrColor(group.attribute)
    val displayName = wrapName(group.groupName)
    // Arrow overlays on right edge — doesn't consume layout width
    Box(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clickable { onClick() }
                .padding(vertical = 6.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(displayName, color = SPPrimary, fontSize = fontSize,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Start, softWrap = true,
                modifier = Modifier.weight(3f).padding(start = 4.dp))

            Text(group.attribute, color = aColor, fontSize = fontSize * 0.9f,
                fontWeight = FontWeight.Medium, maxLines = 1,
                modifier = Modifier.weight(1.1f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(aColor.copy(alpha = 0.12f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                textAlign = TextAlign.Center)

            Text(formatNum(group.creditRequired),
                color = SPPrimary, fontSize = fontSize,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1.25f))

            Text(formatNum(group.creditEarned),
                color = SPPrimary, fontSize = fontSize,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1.25f))

            Text("${group.countRequired}",
                color = SPPrimary, fontSize = fontSize,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1.25f))

            Text("${group.countPassed}",
                color = SPPrimary, fontSize = fontSize,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1.25f))

            Box(modifier = Modifier.weight(1.25f), contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(
                        if (group.isPassed) R.drawable.study_course_pass
                        else R.drawable.study_course_failed
                    ),
                    contentDescription = if (group.isPassed) "通过" else "未通过",
                    tint = Color.Unspecified,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Expand arrow overlaid on right edge — no layout space consumed
        Icon(
            if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (isExpanded) "收起" else "展开",
            tint = SPSecondary,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 2.dp)
                .size(18.dp)
        )
    }
}

@Composable
private fun CourseRow(
    course: StudyPlanCourse,
    fontSize: androidx.compose.ui.unit.TextUnit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(SPCardBg)
            .padding(vertical = 6.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(statusIconRes(course.status)),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                course.courseName,
                color = SPPrimary,
                fontSize = fontSize,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val detailParts = buildList {
                add(formatNum(course.credit) + "学分")
                if (course.hours.isNotBlank()) {
                    add(cleanHours(course.hours) + if (course.hours.endsWith("周")) "" else "学时")
                }
                if (course.assessment.isNotBlank()) add(course.assessment)
                if (course.semester.isNotBlank()) add(course.semester)
            }
            Text(
                detailParts.joinToString(" · "),
                color = SPSecondary,
                fontSize = fontSize * 0.9f,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun statusIconRes(status: CourseStatus): Int = when (status) {
    CourseStatus.PASSED -> R.drawable.study_course_pass
    CourseStatus.FAILED -> R.drawable.study_course_failed
    CourseStatus.FAILED_REELECT -> R.drawable.study_course_failed_reelect
    CourseStatus.PASSED_REELECT -> R.drawable.study_course_pass_reelect
    CourseStatus.UNELECTED -> R.drawable.study_course_unelected
    CourseStatus.UNKNOWN -> R.drawable.study_course_unknown_pass
}

private fun formatNum(value: Double): String {
    if (value == value.toLong().toDouble()) return value.toLong().toString()
    return String.format("%.2f", value).trimEnd('0').trimEnd('.')
}

/** Strip trailing ".0" from numeric hours strings like "8.0" → "8" */
private fun cleanHours(hours: String): String {
    return hours.replace(Regex("""\.0+$"""), "")
}
