package com.glut.schedule.ui.pages

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalUriHandler
import com.glut.schedule.data.model.CourseBlock
import com.glut.schedule.data.model.MAX_ACADEMIC_WEEK
import com.glut.schedule.data.model.MIN_ACADEMIC_WEEK
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.clampAcademicWeek
import com.glut.schedule.data.model.isActiveInWeek
import com.glut.schedule.data.model.scheduleWeekForNumber
import com.glut.schedule.ui.components.ScheduleGrid
import com.glut.schedule.ui.components.ScheduleHeader
import com.glut.schedule.ui.components.StarryScheduleBackground
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.LocalDate
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

@Composable
fun ScheduleScreen(
    viewModel: ScheduleViewModel,
    onImportClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }
    var showAddActions by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val backgroundPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        viewModel.setCustomBackgroundUri(uri.toString())
        showAddActions = false
    }
    val blocksByWeek = remember(uiState.courses) { courseBlocksByWeek(uiState.courses) }
    val pagerState = rememberPagerState(
        initialPage = pagerPageForWeekNumber(uiState.week.number),
        pageCount = { MAX_ACADEMIC_WEEK }
    )
    val latestWeekNumber by rememberUpdatedState(uiState.week.number)

    LaunchedEffect(uiState.week.number) {
        val targetPage = pagerPageForWeekNumber(uiState.week.number)
        if (pagerState.currentPage != targetPage && pagerState.settledPage != targetPage) {
            pagerState.animateScrollToPage(targetPage)
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val targetWeekNumber = weekNumberForPagerPage(page)
                if (targetWeekNumber != latestWeekNumber) {
                    viewModel.setWeekNumber(targetWeekNumber)
                }
            }
    }

    Box(modifier = modifier.fillMaxSize()) {
        StarryScheduleBackground(customBackgroundUri = uiState.customBackgroundUri)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .navigationBarsPadding()
        ) {
            ScheduleHeader(
                week = uiState.week,
                today = uiState.today,
                currentWeekNumber = uiState.currentWeekNumber,
                onImportClick = onImportClick,
                onAddClick = {
                    showAddActions = !showAddActions
                    showSettings = false
                    showAbout = false
                },
                onTodayClick = viewModel::returnToCurrentWeek,
                onMoreClick = {
                    showSettings = !showSettings
                    showAddActions = false
                    showAbout = false
                }
            )
            HorizontalPager(
                state = pagerState,
                key = { page -> page },
                flingBehavior = PagerDefaults.flingBehavior(
                    state = pagerState,
                    pagerSnapDistance = PagerSnapDistance.atMost(1)
                ),
                beyondViewportPageCount = 0,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { page ->
                val pageWeekNumber = weekNumberForPagerPage(page)
                val pageWeek = scheduleWeekForNumber(pageWeekNumber, uiState.semesterStartMonday)
                ScheduleGrid(
                    week = pageWeek,
                    today = uiState.today,
                    periods = uiState.classPeriods,
                    blocks = blocksByWeek[pageWeekNumber].orEmpty(),
                    showWeekend = uiState.showWeekend,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        if (showAddActions) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showAddActions = false }
            )
            ScheduleAddActionsPanel(
                hasCustomBackground = uiState.customBackgroundUri.isNotBlank(),
                onPickBackground = { backgroundPicker.launch(arrayOf("image/*")) },
                onClearBackground = {
                    viewModel.clearCustomBackground()
                    showAddActions = false
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 52.dp, end = 52.dp)
            )
        }
        if (showSettings) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showSettings = false }
            )
            ScheduleSettingsPanel(
                showWeekend = uiState.showWeekend,
                semesterStartMonday = uiState.semesterStartMonday,
                onShowWeekendChange = viewModel::setShowWeekend,
                onSemesterStartSubmit = { date ->
                    viewModel.setSemesterStartDate(date)
                    showSettings = false
                },
                onAboutClick = {
                    showSettings = false
                    showAbout = true
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 52.dp, end = 12.dp)
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 18.dp)
                .navigationBarsPadding()
        )
    }
    if (showAbout) {
        AboutScheduleDialog(onDismiss = { showAbout = false })
    }
}

@Composable
private fun ScheduleAddActionsPanel(
    hasCustomBackground: Boolean,
    onPickBackground: () -> Unit,
    onClearBackground: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.76f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TextButton(onClick = onPickBackground) {
                Text(text = "自定义背景", color = Color.White, fontSize = 13.sp)
            }
            TextButton(onClick = onClearBackground, enabled = hasCustomBackground) {
                Text(text = "恢复默认背景", color = Color.White, fontSize = 13.sp)
            }
        }
    }
}

fun weekNumberForPagerPage(page: Int): Int {
    return clampAcademicWeek(page + 1)
}

fun pagerPageForWeekNumber(weekNumber: Int): Int {
    return clampAcademicWeek(weekNumber) - MIN_ACADEMIC_WEEK
}

fun courseBlocksByWeek(courses: List<ScheduleCourse>): Map<Int, List<CourseBlock>> {
    return (MIN_ACADEMIC_WEEK..MAX_ACADEMIC_WEEK).associateWith { weekNumber ->
        courses.flatMap { course ->
            course.occurrences
                .filter { occurrence -> occurrence.isActiveInWeek(weekNumber) }
                .map { occurrence -> CourseBlock(course, occurrence) }
        }
    }
}

@Composable
private fun ScheduleSettingsPanel(
    showWeekend: Boolean,
    semesterStartMonday: LocalDate,
    onShowWeekendChange: (Boolean) -> Unit,
    onSemesterStartSubmit: (LocalDate) -> Unit,
    onAboutClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")
    var dateInput by remember(semesterStartMonday) {
        mutableStateOf(normalizeSemesterStartDigits(semesterStartMonday.format(formatter)))
    }
    val parsedDate = parseSemesterStartInput(dateInput)
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.72f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "显示周末", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(checked = showWeekend, onCheckedChange = onShowWeekendChange)
            }
            Text(
                text = "学期起点 ${semesterStartMonday.format(formatter)}",
                color = Color.White,
                fontSize = 13.sp
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = dateInput,
                    onValueChange = { dateInput = normalizeSemesterStartDigits(it) },
                    singleLine = true,
                    label = { Text("开学日期") },
                    placeholder = { Text("2026/03/09") },
                    isError = dateInput.isNotBlank() && parsedDate == null,
                    visualTransformation = SemesterStartDateVisualTransformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { parsedDate?.let(onSemesterStartSubmit) },
                    enabled = parsedDate != null
                ) {
                    Text(text = "设置", color = Color.White, fontSize = 12.sp)
                }
            }
            TextButton(onClick = onAboutClick) {
                Text(text = "关于", color = Color.White, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun AboutScheduleDialog(
    onDismiss: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("确定")
            }
        },
        title = { Text("关于") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                aboutScheduleLines().forEach { line ->
                    if (line == ABOUT_PROJECT_URL) {
                        Text(
                            text = line,
                            color = Color(0xFF2563EB),
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier.clickable { uriHandler.openUri(ABOUT_PROJECT_URL) }
                        )
                    } else {
                        Text(text = line)
                    }
                }
            }
        }
    )
}

internal fun aboutScheduleLines(): List<String> {
    return listOf(
        "桂工课表 v0.2.3",
        "简洁 纯粹 高效",
        "开发者：hezh",
        "反馈邮箱：hezh0425@gmail.com",
        ABOUT_PROJECT_LABEL,
        ABOUT_PROJECT_URL
    )
}

internal const val ABOUT_PROJECT_URL = "https://github.com/hzhkdh/glut-schedule"
private const val ABOUT_PROJECT_LABEL = "项目地址："

private object SemesterStartDateVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        return TransformedText(
            AnnotatedString(formatSemesterStartInput(text.text)),
            SemesterStartDateOffsetMapping(text.text.length)
        )
    }
}

private class SemesterStartDateOffsetMapping(
    private val originalLength: Int
) : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int {
        return offset + (if (offset >= 4) 1 else 0) + (if (offset >= 6) 1 else 0)
    }

    override fun transformedToOriginal(offset: Int): Int {
        return when {
            offset <= 4 -> offset
            offset <= 7 -> offset - 1
            else -> offset - 2
        }.coerceIn(0, originalLength)
    }
}

internal fun normalizeSemesterStartDigits(value: String): String {
    return value.filter { it.isDigit() }.take(8)
}

internal fun formatSemesterStartInput(value: String): String {
    val digits = normalizeSemesterStartDigits(value)
    return buildString {
        digits.forEachIndexed { index, char ->
            if (index == 4 || index == 6) append('/')
            append(char)
        }
        if (digits.length == 4 || digits.length == 6) append('/')
    }
}

internal fun parseSemesterStartInput(value: String): LocalDate? {
    val normalized = if (value.all { it.isDigit() } && value.length == 8) {
        formatSemesterStartInput(value)
    } else {
        value.trim().replace('-', '/')
    }
    if (normalized.isBlank()) return null
    val formatter = DateTimeFormatterBuilder()
        .appendPattern("uuuu/M/d")
        .toFormatter()
        .withResolverStyle(ResolverStyle.STRICT)
    return runCatching { LocalDate.parse(normalized, formatter) }.getOrNull()
}
