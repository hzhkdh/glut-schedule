package com.glut.schedule.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.glut.schedule.data.settings.ImportSource

// 与导入页同一套浅色板。这里自带一份而不去 import 那边：那些常量在 DirectLoginScreen.kt 里是
// private，且本仓库各组件的既有做法就是自带色板（见 CourseCardManageSheet.kt）。
private val DialogPrimary = Color(0xFF141821)
private val DialogSecondary = Color(0xFF667085)
private val DialogAccent = Color(0xFF3F7DF6)
private val DialogBorder = Color(0xFFD1D5DB)
private val DialogError = Color(0xFFD64545)

/**
 * 教务会话过期的验证码续期弹窗。
 *
 * 两个入口共用这一份：导入页的登录流程，以及**任意菜单**里刷新时撞上会话失效触发的原地续期
 * （见 `SessionRenewalCoordinator`）。会话过期的场景下用户往往已经离开导入页，
 * 所以副标题要明说「不用去导入页」——否则用户的第一反应仍然是自己去找登录入口。
 *
 * 为什么必须显式给 [AlertDialog] 的 `containerColor`：`GlutScheduleTheme` 用的是
 * `darkColorScheme`，不给容器色就渲染成深灰，而这里的内容是按浅色板画的。
 */
@Composable
fun SessionRenewalDialog(
    source: ImportSource,
    captchaBitmap: Bitmap?,
    /** 本机已保存的密码；没有则为空串，由用户当场填。 */
    prefilledPassword: String,
    /** 上一次失败原因（验证码错、取图失败）；首次为空。 */
    error: String,
    isBusy: Boolean,
    onRefreshCaptcha: () -> Unit,
    onSubmit: (captcha: String, password: String) -> Unit,
    onDismiss: () -> Unit
) {
    var captchaInput by remember { mutableStateOf("") }
    // 预填以「本机保存的密码」为键：换了一位用户或密码变了才重新取，用户自己的改动不会被覆盖。
    var passwordInput by remember(prefilledPassword) { mutableStateOf(prefilledPassword) }
    var passwordVisible by remember { mutableStateOf(false) }

    val title = when (source) {
        ImportSource.NANNING -> "南宁教务会话已过期"
        ImportSource.GRADUATE -> "研究生教务会话已过期"
        else -> "教务会话已过期"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(title, fontWeight = FontWeight.SemiBold, color = DialogPrimary)
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "输入验证码后继续刷新，不用去导入页重新登录",
                    color = DialogSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                )

                // 取图失败时没有图可显示，但原因一定要说出来，否则用户只看到弹窗一闪。
                if (captchaBitmap != null) {
                    Image(
                        bitmap = captchaBitmap.asImageBitmap(),
                        contentDescription = "验证码",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                            .clickable { onRefreshCaptcha() }
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    IconButton(onClick = onRefreshCaptcha, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = "刷新验证码",
                            tint = DialogSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        if (captchaBitmap != null) "点击图片刷新" else "点这里重试",
                        color = DialogSecondary,
                        fontSize = 11.sp
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = captchaInput,
                    onValueChange = { captchaInput = it },
                    label = { Text("验证码", color = DialogSecondary) },
                    singleLine = true,
                    colors = renewalTextFieldColors(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it },
                    label = { Text("密码", color = DialogSecondary) },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Outlined.VisibilityOff
                                else Icons.Outlined.Visibility,
                                contentDescription = null,
                                tint = DialogSecondary
                            )
                        }
                    },
                    colors = renewalTextFieldColors(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                Text(
                    "只用于本次重新登录，不会改动「记住密码」的设置",
                    color = DialogSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                )

                if (error.isNotBlank()) {
                    Text(
                        error,
                        color = DialogError,
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(captchaInput, passwordInput) },
                enabled = !isBusy,
                colors = ButtonDefaults.buttonColors(containerColor = DialogAccent)
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("确认登录", color = Color.White, fontSize = 14.sp)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) {
                Text("取消", color = DialogSecondary)
            }
        },
        containerColor = Color.White,
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
private fun renewalTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = DialogPrimary,
    unfocusedTextColor = DialogPrimary,
    cursorColor = DialogAccent,
    focusedBorderColor = DialogAccent,
    unfocusedBorderColor = DialogBorder,
    focusedLabelColor = DialogAccent,
    unfocusedLabelColor = DialogSecondary
)
