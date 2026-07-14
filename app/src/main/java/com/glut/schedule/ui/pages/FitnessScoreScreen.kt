package com.glut.schedule.ui.pages

import android.content.Context
import android.content.ContextWrapper
import android.app.Activity
import android.graphics.BitmapFactory
import android.view.WindowManager
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.glut.schedule.data.model.FitnessResult
import com.glut.schedule.data.model.FitnessScoreItem
import com.glut.schedule.data.model.FitnessStandardTable
import com.glut.schedule.data.model.FitnessStandardType

private val FitnessPrimary = Color(0xFF141821)
private val FitnessSecondary = Color(0xFF667085)
private val FitnessAccent = Color(0xFF3F7DF6)
private val FitnessSuccess = Color(0xFF2A9D72)
private val FitnessPageBg = Color(0xFFF6F4EF)
private val FitnessCardBg = Color(0xFFFFFEFB)
private val FitnessChipBg = Color(0xFFE8E4D6)
private val FitnessTableHeader = Color(0xFFEAF2FC)
private val FitnessTableEven = Color(0xFFF4F8FD)
private val FitnessTableOdd = Color(0xFFFFFEFB)
private val FitnessGrid = Color(0xFFD9E5F3)
private val CompositeLevelWidth = 52.dp
private val CompositeScoreWidth = 48.dp
private val CompositeValueWidth = 96.dp
private const val PASSWORD_RESET_URL = "https://tzcs.glut.edu.cn/student/findPassword.jsp"

@Composable
fun FitnessScoreScreen(
    viewModel: FitnessScoreViewModel,
    modifier: Modifier = Modifier,
    onTableGestureActive: (Boolean) -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(FitnessPageBg)
    ) {
        if (state.isRefreshing) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = FitnessAccent,
                trackColor = FitnessAccent.copy(alpha = 0.12f)
            )
        }
        MainTabs(
            selected = state.activeTab,
            onSelect = viewModel::selectTab,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
        if (state.message.isNotBlank()) {
            Text(
                state.message,
                color = FitnessSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }
        when (state.activeTab) {
            FitnessTab.LATEST -> ResultContent(
                result = state.current,
                summaryLabel = "当前体测总评",
                emptyText = "暂无体测成绩",
                onLogin = viewModel::showLogin,
                modifier = Modifier.weight(1f)
            )
            FitnessTab.HISTORY -> HistoryContent(
                state = state,
                onSelect = viewModel::selectHistory,
                onLogin = viewModel::showLogin,
                modifier = Modifier.weight(1f)
            )
            FitnessTab.STANDARD -> StandardContent(
                standards = state.standards,
                selectedKey = state.selectedStandardKey,
                onSelect = viewModel::selectStandard,
                onLogin = viewModel::showLogin,
                onTableGestureActive = onTableGestureActive,
                modifier = Modifier.weight(1f)
            )
        }
    }

    if (state.showLoginDialog) {
        SecureDialogWindow()
        FitnessLoginDialog(
            state = state,
            onUsernameChange = viewModel::updateUsername,
            onPasswordChange = viewModel::updatePassword,
            onCaptchaChange = viewModel::updateCaptcha,
            onCaptchaClick = viewModel::refreshCaptcha,
            onDismiss = viewModel::dismissLogin,
            onLogin = viewModel::login
        )
    }
}

@Composable
private fun SecureDialogWindow() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val activity = context.findActivity()
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

@Composable
private fun MainTabs(
    selected: FitnessTab,
    onSelect: (FitnessTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            FitnessTab.LATEST to "最新成绩",
            FitnessTab.HISTORY to "历年成绩",
            FitnessTab.STANDARD to "评分标准"
        ).forEach { (tab, label) ->
            FitnessPill(
                label = label,
                selected = selected == tab,
                onClick = { onSelect(tab) },
                modifier = Modifier.weight(1f),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun FitnessPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 36.dp,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp
) {
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(if (selected) FitnessPrimary else FitnessChipBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) Color.White else FitnessSecondary,
            fontSize = fontSize,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

@Composable
private fun ResultContent(
    result: FitnessResult?,
    summaryLabel: String,
    emptyText: String,
    onLogin: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (result == null || result.items.isEmpty()) {
        EmptyFitnessState(emptyText, onLogin, modifier)
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { OverallCard(result, summaryLabel) }
        item {
            Text(
                "项目明细",
                color = FitnessPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
            )
        }
        items(result.items, key = { "${it.name}_${it.testScore}_${it.score}" }) { item ->
            FitnessItemCard(item)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryContent(
    state: FitnessScoreUiState,
    onSelect: (String) -> Unit,
    onLogin: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (state.history.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.history.forEach { record ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(22.dp))
                            .background(if (record.key == state.selectedHistoryKey) FitnessPrimary else FitnessChipBg)
                            .clickable { onSelect(record.key) }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            "${record.year} ${record.term}",
                            color = if (record.key == state.selectedHistoryKey) Color.White else FitnessSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
        val selected = state.selectedHistory
        val result = state.visibleHistoryResult
        ResultContent(
            result = result,
            summaryLabel = selected?.let { "${it.year} 第${it.term}学期总评" } ?: "历年体测总评",
            emptyText = if (state.history.isEmpty()) "暂无历年体测成绩" else "正在等待历年体测详情",
            onLogin = onLogin,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun OverallCard(result: FitnessResult, label: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = FitnessPrimary,
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(label, color = Color.White.copy(alpha = 0.72f), fontSize = 14.sp)
                Spacer(Modifier.height(5.dp))
                Text(
                    result.totalLevel.ifBlank { "---" },
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    result.totalScore.ifBlank { "---" },
                    color = Color.White,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold
                )
                Text("总分", color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun FitnessItemCard(item: FitnessScoreItem) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = FitnessCardBg,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, color = FitnessPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                Text("测试成绩 ${item.testScore.ifBlank { "---" }}", color = FitnessSecondary, fontSize = 14.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    item.score.ifBlank { "---" },
                    color = if (item.score.isBlank() || item.score == "---") FitnessSecondary else FitnessSuccess,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(item.conclusion.ifBlank { "---" }, color = FitnessSecondary, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun EmptyFitnessState(text: String, onLogin: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, color = FitnessPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("登录后即可查看体测数据", color = FitnessSecondary, fontSize = 14.sp)
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onLogin,
                colors = ButtonDefaults.buttonColors(containerColor = FitnessAccent)
            ) { Text("登录体测平台") }
        }
    }
}

@Composable
private fun StandardContent(
    standards: List<FitnessStandardTable>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    onLogin: () -> Unit,
    onTableGestureActive: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    if (standards.isEmpty()) {
        EmptyFitnessState("正在等待评分标准", onLogin, modifier)
        return
    }
    val selected = standards.firstOrNull { it.key == selectedKey } ?: standards.first()
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            standards.forEach { table ->
                FitnessPill(
                    label = table.title,
                    selected = table.key == selected.key,
                    onClick = { onSelect(table.key) },
                    modifier = Modifier.weight(1f),
                    height = 32.dp,
                    fontSize = 12.sp
                )
            }
        }
        Text(
            standardHeading(selected),
            color = FitnessPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        val note = selected.weightNote.ifBlank { selected.note }
        when (selected.type) {
            FitnessStandardType.COMPOSITE -> {
                CompositeStandardTable(
                    table = selected,
                    onGestureActive = onTableGestureActive,
                    modifier = Modifier.weight(1f)
                )
                if (note.isNotBlank()) StandardNote(note)
            }
            FitnessStandardType.BMI, FitnessStandardType.BONUS -> {
                ShortStandardTable(table = selected)
                if (note.isNotBlank()) StandardNote(note)
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun standardHeading(table: FitnessStandardTable): String = when (table.key) {
    "male" -> "表一 男生：学生体质测试评分标准"
    "female" -> "表二 女生：学生体质测试评分标准"
    "bmi" -> "表三 BMI：体重指数单项评分"
    else -> "表四 加分：学生体质测试加分项目"
}

@Composable
private fun StandardNote(note: String) {
    Text(
        note,
        color = FitnessSecondary,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp)
    )
}

@Composable
private fun CompositeStandardTable(
    table: FitnessStandardTable,
    onGestureActive: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val horizontalScrollState = rememberScrollState()
    val fixedWidth = CompositeLevelWidth + CompositeScoreWidth
    val valueWidth = CompositeValueWidth
    val totalWidth = fixedWidth + valueWidth * table.headers.size
    DisposableEffect(table.key) { onDispose { onGestureActive(false) } }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(2.dp))
            .background(FitnessTableOdd)
            .pointerInput(table.key) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onGestureActive(true)
                    try {
                        do {
                            val event = awaitPointerEvent()
                        } while (event.changes.any { it.pressed })
                    } finally {
                        onGestureActive(false)
                    }
                }
            }
            .horizontalScroll(horizontalScrollState)
    ) {
        CompositeRow(
            level = "等级",
            score = "分值",
            values = table.headers.map(::formatCompositeHeader),
            background = FitnessTableHeader,
            fixedScrollState = horizontalScrollState,
            valueWidth = valueWidth,
            totalWidth = totalWidth,
            header = true
        )
        LazyColumn(modifier = Modifier.requiredWidth(totalWidth).weight(1f)) {
            items(table.rows.size, key = { index -> "${table.key}_$index" }) { index ->
                val row = table.rows[index]
                CompositeRow(
                    level = row.level,
                    score = row.score,
                    values = row.values,
                    background = if (index % 2 == 0) FitnessTableOdd else FitnessTableEven,
                    fixedScrollState = horizontalScrollState,
                    valueWidth = valueWidth,
                    totalWidth = totalWidth,
                    header = false
                )
            }
        }
    }
}

@Composable
private fun CompositeRow(
    level: String,
    score: String,
    values: List<String>,
    background: Color,
    fixedScrollState: ScrollState,
    valueWidth: Dp,
    totalWidth: Dp,
    header: Boolean
) {
    val rowHeight = if (header) 58.dp else 48.dp
    Row(modifier = Modifier.requiredWidth(totalWidth).height(rowHeight).background(background)) {
        val fixedModifier = Modifier
            .graphicsLayer { translationX = fixedScrollState.value.toFloat() }
            .zIndex(2f)
        TableCell(level, background, header, fixedModifier.width(CompositeLevelWidth))
        TableCell(score, background, header, fixedModifier.width(CompositeScoreWidth))
        values.forEach { value ->
            TableCell(value, background, header, Modifier.width(valueWidth))
        }
    }
}

private fun formatCompositeHeader(header: String): String =
    header.replace(Regex("\\s*([（(])"), "\n$1")

@Composable
private fun TableCell(
    text: String,
    background: Color,
    header: Boolean,
    modifier: Modifier = Modifier,
    textColor: Color = FitnessPrimary.copy(alpha = if (header) 1f else 0.72f),
    forceBold: Boolean = false
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(background)
            .border(0.5.dp, FitnessGrid),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = if (header || forceBold) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            lineHeight = 15.sp,
            maxLines = if (header) 3 else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 3.dp)
        )
    }
}

@Composable
private fun ShortStandardTable(table: FitnessStandardTable, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))) {
        Row(modifier = Modifier.fillMaxWidth().height(46.dp)) {
            table.headers.forEach { header ->
                TableCell(header, FitnessTableHeader, true, Modifier.weight(1f))
            }
        }
        if (table.type == FitnessStandardType.BMI && table.scores.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().height(42.dp)) {
                table.scores.forEach { score ->
                    TableCell(
                        text = score,
                        background = FitnessTableOdd,
                        header = false,
                        modifier = Modifier.weight(1f),
                        textColor = FitnessSuccess,
                        forceBold = true
                    )
                }
            }
        }
        table.rows.forEachIndexed { index, row ->
            val background = if (index % 2 == 0) FitnessTableEven else FitnessTableOdd
            Row(modifier = Modifier.fillMaxWidth().height(42.dp)) {
                TableCell(row.label.ifBlank { row.score }, background, false, Modifier.weight(1f))
                row.values.forEach { value ->
                    TableCell(value, background, false, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun FitnessLoginDialog(
    state: FitnessScoreUiState,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onCaptchaChange: (String) -> Unit,
    onCaptchaClick: () -> Unit,
    onDismiss: () -> Unit,
    onLogin: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    var passwordVisible by remember { mutableStateOf(false) }
    val captchaBitmap = remember(state.captchaImage) {
        runCatching {
            val bytes = Base64.decode(state.captchaImage.substringAfter(','), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FitnessCardBg,
        titleContentColor = FitnessPrimary,
        textContentColor = FitnessPrimary,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(20.dp),
        title = { Text("登录体测平台", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = state.username,
                    onValueChange = onUsernameChange,
                    label = { Text("学号") },
                    singleLine = true,
                    colors = fitnessTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = onPasswordChange,
                    label = { Text("体测平台密码") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                                tint = FitnessSecondary
                            )
                        }
                    },
                    colors = fitnessTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "忘记密码？前往体测平台重置",
                    color = FitnessAccent,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable { uriHandler.openUri(PASSWORD_RESET_URL) }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = state.captcha,
                        onValueChange = onCaptchaChange,
                        label = { Text("验证码") },
                        singleLine = true,
                        colors = fitnessTextFieldColors(),
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .width(126.dp)
                            .height(56.dp)
                            .background(Color.White)
                            .clickable(onClick = onCaptchaClick),
                        contentAlignment = Alignment.Center
                    ) {
                        if (captchaBitmap != null) {
                            androidx.compose.foundation.Image(
                                bitmap = captchaBitmap,
                                contentDescription = "点击刷新验证码",
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text("点击刷新", color = FitnessSecondary, fontSize = 12.sp)
                        }
                    }
                }
                Text("点击验证码图片可刷新", color = FitnessSecondary, fontSize = 12.sp)
                if (state.loginError.isNotBlank()) {
                    Text(state.loginError, color = Color(0xFFDC2626), fontSize = 13.sp)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = FitnessSecondary) } },
        confirmButton = {
            TextButton(onClick = onLogin, enabled = !state.isRefreshing) {
                val actionColor = if (state.isRefreshing) {
                    FitnessAccent.copy(alpha = 0.38f)
                } else {
                    FitnessAccent
                }
                if (state.isRefreshing) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = actionColor,
                            strokeWidth = 2.dp
                        )
                        Text("登录中…", color = actionColor, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Text("登录并查询", color = actionColor, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    )
}

@Composable
private fun fitnessTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = FitnessPrimary,
    unfocusedTextColor = FitnessPrimary,
    cursorColor = FitnessAccent,
    focusedBorderColor = FitnessAccent,
    unfocusedBorderColor = Color(0xFFCAD2DE),
    focusedLabelColor = FitnessAccent,
    unfocusedLabelColor = FitnessSecondary,
    focusedContainerColor = Color.White,
    unfocusedContainerColor = Color.White
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
