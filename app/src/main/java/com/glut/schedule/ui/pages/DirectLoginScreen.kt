package com.glut.schedule.ui.pages

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
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
