package com.glut.schedule.ui.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
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
import androidx.compose.material3.Text
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

@Composable
fun DirectLoginScreen(
    viewModel: DirectLoginViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    var passwordVisible by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .navigationBarsPadding(),
        containerColor = Color(0xFF0B1622)
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

            Text("导入课表", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
            Text("登录教务系统，一键导入课程、考试和成绩", color = Color(0xFF8A93A3), fontSize = 14.sp, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))

            Spacer(modifier = Modifier.height(28.dp))

            // Student ID
            OutlinedTextField(
                value = uiState.username, onValueChange = viewModel::updateUsername,
                label = { Text("学号", color = Color(0xFF8A93A3)) },
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
                label = { Text("密码", color = Color(0xFF8A93A3)) },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = null, tint = Color(0xFF8A93A3))
                    }
                },
                colors = loginTextFieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Captcha
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = uiState.captchaText, onValueChange = viewModel::updateCaptchaText,
                    label = { Text("验证码", color = Color(0xFF8A93A3)) },
                    singleLine = true,
                    colors = loginTextFieldColors(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                )
                Box(
                    modifier = Modifier.size(width = 100.dp, height = 56.dp)
                        .background(Color(0xFF172033), RoundedCornerShape(10.dp))
                        .clickable { viewModel.loadCaptcha() },
                    contentAlignment = Alignment.Center
                ) {
                    val bitmap = uiState.captchaBitmap
                    if (bitmap != null) {
                        Image(bitmap = bitmap.asImageBitmap(), contentDescription = "验证码", modifier = Modifier.fillMaxSize())
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color(0xFF7DD3FC), strokeWidth = 2.dp)
                    }
                }
                IconButton(onClick = viewModel::loadCaptcha, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新验证码", tint = Color(0xFF8A93A3), modifier = Modifier.size(22.dp))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Remember password
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = uiState.rememberPassword,
                    onCheckedChange = viewModel::updateRememberPassword,
                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFF7DD3FC), uncheckedColor = Color(0xFF4A5568))
                )
                Text("记住密码（加密存储）", color = Color(0xFF8A93A3), fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Login button
            Button(
                onClick = viewModel::loginAndImport,
                enabled = !uiState.isLoggingIn,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7DD3FC), disabledContainerColor = Color(0xFF3A5068)),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (uiState.isLoggingIn) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("正在导入...", fontSize = 16.sp, color = Color.White)
                } else {
                    Text("登 录 并 导 入", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0B1622))
                }
            }

            // Message
            if (uiState.message.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(uiState.message, color = if (uiState.message.contains("成功") || uiState.message.contains("完成")) Color(0xFF4ADE80) else Color(0xFFF87171), fontSize = 14.sp)
            }

            // Import result cards
            val result = uiState.importResult
            if (result != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ImportResultCard("课程", result.courseCount, Color(0xFF7DD3FC), Modifier.weight(1f))
                    ImportResultCard("考试", result.examCount, Color(0xFFC4B5FD), Modifier.weight(1f))
                    ImportResultCard("成绩", result.scoreCount, Color(0xFF4ADE80), Modifier.weight(1f))
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ImportResultCard(label: String, count: Int, color: Color, modifier: Modifier) {
    Surface(modifier = modifier, color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(count.toString(), color = color, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(label, color = color.copy(alpha = 0.8f), fontSize = 13.sp)
        }
    }
}

@Composable
private fun loginTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
    cursorColor = Color(0xFF7DD3FC),
    focusedBorderColor = Color(0xFF7DD3FC), unfocusedBorderColor = Color(0xFF2D3A4D),
    focusedLabelColor = Color(0xFF7DD3FC), unfocusedLabelColor = Color(0xFF8A93A3)
)
