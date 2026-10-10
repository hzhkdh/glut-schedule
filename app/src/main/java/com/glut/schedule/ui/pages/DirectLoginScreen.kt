package com.glut.schedule.ui.pages

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glut.schedule.data.model.AcademicSemester
import com.glut.schedule.data.model.SemesterCacheStatus
import com.glut.schedule.data.settings.ImportSource
import com.glut.schedule.service.academic.SemesterDownloadItemStatus
import com.glut.schedule.service.academic.SemesterDownloadMode
import com.glut.schedule.service.academic.SemesterDownloadState

private val LoginPrimary = Color(0xFF141821)
private val LoginSecondary = Color(0xFF667085)
private val LoginAccent = Color(0xFF3F7DF6)
private val LoginPageBg = Color(0xFFF6F4EF)
private val LoginCardBg = Color(0xFFFFFEFB)
private val LoginBorder = Color(0xFFD1D5DB)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectLoginScreen(
    viewModel: DirectLoginViewModel,
    modifier: Modifier = Modifier,
    /**
     * 离开导入页、回到课表页。
     *
     * 导入成功后用户想看的必然是课表；不给这个出口时主按钮只是复位成「登录并导入」，
     * 用户不知道该自己切页，只会留在导入页反复点导入（重复拉一次全量数据）。
     */
    onDone: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val semesterDownloadState by viewModel.semesterDownloadState.collectAsStateWithLifecycle()
    var passwordVisible by remember { mutableStateOf(false) }
    // 学期管理区的「查看课表」：先切到该学期，再跳出导入页。
    // viewSemester 只切学期不跳转，单独调用会让这个按钮看起来是坏的——点了界面毫无变化。
    val viewSemesterAndOpenSchedule: (String) -> Unit = { semesterId ->
        viewModel.viewSemester(semesterId)
        onDone()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LoginPageBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
            Spacer(modifier = Modifier.height(12.dp))

            Text("登录教务系统，一键导入课程和考试", color = LoginSecondary, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))

            Spacer(modifier = Modifier.height(20.dp))

            // Student ID
            OutlinedTextField(
                value = uiState.username, onValueChange = viewModel::updateUsername,
                label = { Text("学号", color = LoginSecondary) },
                singleLine = true,
                colors = loginTextFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Password
            OutlinedTextField(
                value = uiState.password, onValueChange = viewModel::updatePassword,
                label = { Text("密码", color = LoginSecondary) },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = null, tint = LoginSecondary)
                    }
                },
                colors = loginTextFieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Remember password
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = uiState.rememberPassword,
                    onCheckedChange = viewModel::updateRememberPassword,
                    enabled = uiState.secureCredentialStorageAvailable,
                    colors = CheckboxDefaults.colors(checkedColor = LoginAccent, uncheckedColor = Color(0xFF9CA3AF))
                )
                Text(
                    if (uiState.secureCredentialStorageAvailable) "记住密码" else "系统加密存储不可用，已禁止记住密码",
                    color = LoginSecondary,
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 导入来源：南宁分校 / 桂林本部 / 研究生。顺序即 ImportSource 的声明顺序。
            // 用下拉而不是开关，是因为三个来源无法用「是/否」表达；
            // 首次默认桂林本部，之后恢复「上一次成功导入」的来源。
            var sourceExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = sourceExpanded,
                onExpandedChange = { sourceExpanded = !sourceExpanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = uiState.importSource.label,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text("导入来源", color = LoginSecondary) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sourceExpanded) },
                    colors = loginTextFieldColors(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                )
                ExposedDropdownMenu(
                    expanded = sourceExpanded,
                    onDismissRequest = { sourceExpanded = false },
                    containerColor = LoginCardBg
                ) {
                    ImportSource.entries.forEach { source ->
                        DropdownMenuItem(
                            text = { Text(source.label, color = LoginPrimary) },
                            onClick = {
                                viewModel.updateImportSource(source)
                                sourceExpanded = false
                            },
                            colors = MenuDefaults.itemColors(textColor = LoginPrimary),
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 主按钮。三种状态：导入中 → 进度；导入完成 → 「查看课表」并跳转；其余 → 「登 录 并 导 入」。
            // 完成后必须换成「查看课表」：此前按钮复位回「登 录 并 导 入」，用户看不出下一步，
            // 就留在导入页反复点导入，而每次都会重新拉一遍全量数据。
            Button(
                onClick = { if (uiState.importCompleted) onDone() else viewModel.loginAndImport() },
                enabled = !uiState.isLoggingIn,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = LoginAccent, disabledContainerColor = Color(0xFFB0C4DE)),
                shape = RoundedCornerShape(12.dp)
            ) {
                when {
                    uiState.isLoggingIn -> {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("正在导入...", fontSize = 16.sp, color = Color.White)
                    }
                    uiState.importCompleted ->
                        Text("查看课表 ›", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    else ->
                        Text("登 录 并 导 入", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

            // 「重新导入」只在完成态出现：换账号或想再导一次的回头路。
            // 用 TextButton 是刻意的——主按钮已经把用户送去课表，这里只是少数情况下的出口，
            // 视觉上必须明显轻于主按钮，否则两颗实心按钮会让人再一次不知道该按哪个。
            if (uiState.importCompleted) {
                TextButton(
                    onClick = viewModel::restartImport,
                    enabled = !uiState.isLoggingIn,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text("重新导入", color = LoginSecondary, fontSize = 14.sp)
                }
            }

            // Message
            if (uiState.message.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                val messageColor = when (loginMessageTone(uiState.message)) {
                    LoginMessageTone.SUCCESS -> Color(0xFF2D9A72)
                    LoginMessageTone.ERROR -> Color(0xFFDC2626)
                    LoginMessageTone.INFO -> LoginSecondary
                }
                Text(uiState.message, color = messageColor, fontSize = 14.sp)
            }

            // Import result cards。研究生门户没有考试模块，改显示成绩与培养计划，
            // 否则两颗「考试 0」的卡片会让人以为导入漏了东西。
            val result = uiState.importResult
            if (result != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ImportResultCard("课程", result.courseCount, LoginAccent, Modifier.weight(1f))
                    if (uiState.isGraduate) {
                        ImportResultCard("成绩", result.scoreCount, Color(0xFF7C5FE7), Modifier.weight(1f))
                        ImportResultCard("培养计划", result.studyPlanCount, Color(0xFF2D9A72), Modifier.weight(1f))
                    } else {
                        ImportResultCard("考试", result.examCount, Color(0xFF7C5FE7), Modifier.weight(1f))
                    }
                }
            }

            // 调课提示：研究生门户的调课记录暂不并入课表，只如实告知，不改数据。
            if (uiState.adjustmentWarning.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(uiState.adjustmentWarning, color = LoginSecondary, fontSize = 13.sp)
            }

            if (uiState.semesters.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                SemesterManagementSection(
                    semesters = uiState.semesters,
                    viewedSemesterId = uiState.viewedSemesterId,
                    importingSemesterId = uiState.importingSemesterId,
                    downloadState = semesterDownloadState,
                    onDownloadSemester = viewModel::downloadSemester,
                    onDownloadAll = viewModel::downloadAllSemesters,
                    onRetryFailed = viewModel::retryFailedSemester,
                    onViewSemester = viewSemesterAndOpenSchedule
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
    }

    // 验证码弹窗：南宁与研究生共用（两者都要求图形验证码）。
    if (uiState.showCaptchaDialog && uiState.captchaBitmap != null) {
        LoginCaptchaDialog(
            captchaBitmap = uiState.captchaBitmap!!,
            captchaInput = uiState.captchaInput,
            isLoggingIn = uiState.isLoggingIn,
            onCaptchaInputChange = viewModel::updateCaptchaInput,
            onRefresh = viewModel::refreshCaptcha,
            onConfirm = viewModel::submitCaptcha,
            onDismiss = viewModel::cancelCaptcha
        )
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SemesterManagementSection(
    semesters: List<AcademicSemester>,
    viewedSemesterId: String,
    importingSemesterId: String?,
    downloadState: SemesterDownloadState,
    onDownloadSemester: (String) -> Unit,
    onDownloadAll: () -> Unit,
    onRetryFailed: (String) -> Unit,
    onViewSemester: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var selectedSemesterId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedSemester = semesters.firstOrNull { it.id == selectedSemesterId }
        ?: semesters.firstOrNull { it.isCurrent }
        ?: semesters.first()
    val activeSemesterId = downloadState.activeSemesterId ?: importingSemesterId
    val isDownloading = activeSemesterId == selectedSemester.id ||
        selectedSemester.cacheStatus == SemesterCacheStatus.DOWNLOADING
    val anyDownloadRunning = downloadState.isRunning || importingSemesterId != null
    val isViewable = selectedSemester.isCurrent || selectedSemester.cacheStatus == SemesterCacheStatus.CACHED
    val canRedownload = !selectedSemester.isCurrent &&
        selectedSemester.cacheStatus == SemesterCacheStatus.CACHED
    val actionLabel = when {
        isDownloading -> "下载中..."
        isViewable && selectedSemester.id == viewedSemesterId -> "正在查看"
        isViewable -> "查看课表"
        selectedSemester.cacheStatus == SemesterCacheStatus.FAILED -> "重新下载"
        else -> "下载并缓存"
    }
    // 「全部下载」按下的语义是**全部重新下载**（协调器 startAll 同样不看 cacheStatus），
    // 所以这里数的是「有几个历史学期」，不是「有几个待下载」。
    // 数待下载会造成：历史学期一旦全部缓存，按钮就变成一条灰底状态说明，用户读成「按钮没了」。
    // 只有「一个历史学期都没有」时按钮才该置灰——那时它确实无事可做。
    val historicalSemesterCount = semesters.count { semester -> !semester.isCurrent }
    val completedItems = downloadState.items.count { item ->
        item.status == SemesterDownloadItemStatus.SUCCEEDED ||
            item.status == SemesterDownloadItemStatus.FAILED
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("学期课表", color = LoginPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            "选择学期，按需下载并离线保存",
            color = LoginSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
        )
        Button(
            onClick = onDownloadAll,
            enabled = !anyDownloadRunning && historicalSemesterCount > 0,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = LoginAccent,
                contentColor = Color.White,
                disabledContainerColor = LoginBorder.copy(alpha = 0.45f),
                disabledContentColor = LoginSecondary
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            // 文案恒为「全部下载」：不再因为「历史学期均已缓存」而改名，
            // 否则按钮看起来像被一条状态说明取代了（与小程序 import.wxml 的写法一致）。
            val text = when {
                downloadState.isRunning && downloadState.mode != SemesterDownloadMode.SINGLE ->
                    "已完成 $completedItems/${downloadState.items.size}"
                else -> "全部下载"
            }
            Text(text, fontWeight = FontWeight.SemiBold)
        }
        downloadState.lastBulkSummary?.let { summary ->
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                color = Color(0xFFFAFAF8),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text("最近一次全部下载", color = LoginPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    summary.items.forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 38.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(item.displayName, color = LoginPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text(
                                if (item.status == SemesterDownloadItemStatus.SUCCEEDED) {
                                    if (item.skippedRowCount > 0) "已完成，跳过${item.skippedRowCount}条" else "已完成"
                                } else "失败",
                                color = if (item.status == SemesterDownloadItemStatus.SUCCEEDED) Color(0xFF15803D) else Color(0xFFDC2626),
                                fontSize = 12.sp
                            )
                            if (item.status == SemesterDownloadItemStatus.FAILED) {
                                TextButton(
                                    onClick = { onRetryFailed(item.semesterId) },
                                    enabled = !anyDownloadRunning
                                ) { Text("重试") }
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = selectedSemester.displayName,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text("选择要下载的学期") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = loginTextFieldColors(),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = LoginCardBg
            ) {
                semesters.forEach { semester ->
                    val status = when {
                        activeSemesterId == semester.id || semester.cacheStatus == SemesterCacheStatus.DOWNLOADING -> "下载中"
                        semester.isCurrent -> "当前"
                        semester.cacheStatus == SemesterCacheStatus.CACHED -> "已缓存"
                        semester.cacheStatus == SemesterCacheStatus.FAILED -> "重试"
                        else -> "未下载"
                    }
                    DropdownMenuItem(
                        text = {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(semester.displayName, modifier = Modifier.weight(1f))
                                Text(status, color = if (status == "重试") Color(0xFFDC2626) else LoginAccent, fontSize = 12.sp)
                            }
                        },
                        onClick = {
                            selectedSemesterId = semester.id
                            expanded = false
                        },
                        colors = MenuDefaults.itemColors(textColor = LoginPrimary),
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }
        }
        Button(
            onClick = {
                if (isViewable) onViewSemester(selectedSemester.id)
                else onDownloadSemester(selectedSemester.id)
            },
            enabled = !anyDownloadRunning && !isDownloading && selectedSemester.id != viewedSemesterId,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = LoginAccent,
                contentColor = Color.White,
                disabledContainerColor = LoginBorder.copy(alpha = 0.45f),
                disabledContentColor = LoginSecondary
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (isDownloading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(actionLabel, fontWeight = FontWeight.SemiBold)
        }
        if (canRedownload) {
            OutlinedButton(
                onClick = { onDownloadSemester(selectedSemester.id) },
                enabled = !anyDownloadRunning && !isDownloading,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 8.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("重新下载", color = LoginAccent, fontWeight = FontWeight.SemiBold)
            }
            // 原先这里有一行「该学期原用模式X缓存，重下将改用模式Y」的提示。导入线路已统一，
            // 重下不再改变取值口径，提示随之删除。
        }
    }
}

/**
 * 图形验证码弹窗。
 *
 * 原名 `NanningCaptchaDialog`；研究生登录同样需要验证码，弹窗内容与交互完全一致，
 * 因此合并成一个并改名，避免留下一个「南宁专用」却给两个来源用的误导性名字。
 */
@Composable
private fun LoginCaptchaDialog(
    captchaBitmap: android.graphics.Bitmap,
    captchaInput: String,
    isLoggingIn: Boolean,
    onCaptchaInputChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("请输入验证码", fontWeight = FontWeight.SemiBold, color = LoginPrimary)
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Captcha image — click to refresh, enlarged for readability
                Image(
                    bitmap = captchaBitmap.asImageBitmap(),
                    contentDescription = "验证码",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .clickable { onRefresh() }
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    IconButton(onClick = onRefresh, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = "刷新验证码",
                            tint = LoginSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text("点击图片刷新", color = LoginSecondary, fontSize = 11.sp)
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = captchaInput,
                    onValueChange = onCaptchaInputChange,
                    label = { Text("验证码", color = LoginSecondary) },
                    singleLine = true,
                    colors = loginTextFieldColors(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isLoggingIn,
                colors = ButtonDefaults.buttonColors(containerColor = LoginAccent)
            ) {
                if (isLoggingIn) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text("确认登录", color = Color.White, fontSize = 14.sp)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoggingIn) {
                Text("取消", color = LoginSecondary)
            }
        },
        containerColor = Color.White,
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
private fun ImportResultCard(label: String, count: Int, color: Color, modifier: Modifier) {
    Surface(modifier = modifier, color = color.copy(alpha = 0.10f), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(count.toString(), color = color, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(label, color = color.copy(alpha = 0.8f), fontSize = 13.sp)
        }
    }
}

@Composable
private fun loginTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = LoginPrimary,
    unfocusedTextColor = LoginPrimary,
    cursorColor = LoginAccent,
    focusedBorderColor = LoginAccent,
    unfocusedBorderColor = LoginBorder,
    focusedLabelColor = LoginAccent,
    unfocusedLabelColor = LoginSecondary
)
internal enum class LoginMessageTone { SUCCESS, ERROR, INFO }

internal fun loginMessageTone(message: String): LoginMessageTone {
    val normalized = message.trim()
    return when {
        listOf("失败", "错误", "失效", "无法", "未获取").any(normalized::contains) ->
            LoginMessageTone.ERROR
        listOf("已缓存", "成功", "完成").any(normalized::contains) ->
            LoginMessageTone.SUCCESS
        else -> LoginMessageTone.INFO
    }
}
