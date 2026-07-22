package com.glut.schedule.ui.pages

import androidx.compose.foundation.Image
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

private val LoginPrimary = Color(0xFF141821)
private val LoginSecondary = Color(0xFF667085)
private val LoginAccent = Color(0xFF3F7DF6)
private val LoginPageBg = Color(0xFFF6F4EF)
private val LoginCardBg = Color(0xFFFFFEFB)
private val LoginBorder = Color(0xFFD1D5DB)

@Composable
fun DirectLoginScreen(
    viewModel: DirectLoginViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    var passwordVisible by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        containerColor = LoginPageBg
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            Text("登录教务系统，一键导入课程和考试", color = LoginSecondary, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))

            Spacer(modifier = Modifier.height(28.dp))

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
                    colors = CheckboxDefaults.colors(checkedColor = LoginAccent, uncheckedColor = Color(0xFF9CA3AF))
                )
                Text("记住密码", color = LoginSecondary, fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Campus selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("南宁分校", color = LoginPrimary, fontSize = 14.sp)
                Switch(
                    checked = uiState.isNanning,
                    onCheckedChange = { viewModel.toggleNanning() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = LoginAccent,
                        checkedTrackColor = LoginAccent.copy(alpha = 0.3f),
                        uncheckedThumbColor = LoginSecondary,
                        uncheckedTrackColor = Color(0xFFD1D5DB)
                    )
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Login button
            Button(
                onClick = viewModel::loginAndImport,
                enabled = !uiState.isLoggingIn,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = LoginAccent, disabledContainerColor = Color(0xFFB0C4DE)),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (uiState.isLoggingIn) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("正在导入...", fontSize = 16.sp, color = Color.White)
                } else {
                    Text("登 录 并 导 入", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

            // Message
            if (uiState.message.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(uiState.message, color = if (uiState.message.contains("成功") || uiState.message.contains("完成")) Color(0xFF2D9A72) else Color(0xFFDC2626), fontSize = 14.sp)
            }

            // Import result cards
            val result = uiState.importResult
            if (result != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ImportResultCard("课程", result.courseCount, LoginAccent, Modifier.weight(1f))
                    ImportResultCard("考试", result.examCount, Color(0xFF7C5FE7), Modifier.weight(1f))
                }
            }

            if (uiState.semesters.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                SemesterManagementSection(
                    semesters = uiState.semesters,
                    importingSemesterId = uiState.importingSemesterId,
                    onSemesterClick = viewModel::selectOrImportSemester
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Native captcha dialog for Nanning login
    if (uiState.showCaptchaDialog && uiState.captchaBitmap != null) {
        NanningCaptchaDialog(
            captchaBitmap = uiState.captchaBitmap!!,
            captchaInput = uiState.captchaInput,
            isLoggingIn = uiState.isLoggingIn,
            onCaptchaInputChange = viewModel::updateCaptchaInput,
            onRefresh = viewModel::refreshNanningCaptcha,
            onConfirm = viewModel::submitNanningCaptcha,
            onDismiss = viewModel::cancelNanningCaptcha
        )
    }

}

@Composable
private fun SemesterManagementSection(
    semesters: List<AcademicSemester>,
    importingSemesterId: String?,
    onSemesterClick: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("学期课表", color = LoginPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            "当前学期自动更新，历史学期下载后可离线只读查看",
            color = LoginSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
        )
        semesters.forEach { semester ->
            val isDownloading = importingSemesterId == semester.id || semester.cacheStatus == SemesterCacheStatus.DOWNLOADING
            val status = when {
                isDownloading -> "下载中"
                semester.isCurrent -> "当前"
                semester.cacheStatus == SemesterCacheStatus.CACHED -> "已缓存"
                semester.cacheStatus == SemesterCacheStatus.FAILED -> "重试"
                else -> "未下载"
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .heightIn(min = 48.dp)
                    .clickable(enabled = !isDownloading) { onSemesterClick(semester.id) },
                color = if (semester.isCurrent) LoginAccent.copy(alpha = 0.10f) else LoginCardBg,
                shape = RoundedCornerShape(12.dp),
                tonalElevation = if (semester.isCurrent) 1.dp else 0.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(semester.displayName, color = LoginPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        if (semester.cacheStatus == SemesterCacheStatus.CACHED && !semester.isCurrent) {
                            Text("历史学期 · 只读", color = LoginSecondary, fontSize = 12.sp)
                        }
                    }
                    if (isDownloading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = LoginAccent)
                    } else {
                        Text(status, color = if (status == "重试") Color(0xFFDC2626) else LoginAccent, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun NanningCaptchaDialog(
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
                    IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
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
