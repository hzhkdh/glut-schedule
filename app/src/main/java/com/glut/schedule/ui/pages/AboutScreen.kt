package com.glut.schedule.ui.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glut.schedule.BuildConfig
import com.glut.schedule.service.UpdateChecker
import com.glut.schedule.service.UpdateInfo
import kotlinx.coroutines.launch

@Composable
fun AboutScreen(
    updateChecker: UpdateChecker,
    onShowUpdateDialog: (UpdateInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val currentVersion = BuildConfig.VERSION_NAME

    Scaffold(
        modifier = modifier,
        containerColor = Color(0xFFF6F4EF)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // Info card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFFFFEFB),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(4.dp)) {
                    AboutInfoRow(
                        icon = Icons.Outlined.Info,
                        label = "当前版本",
                        value = "v$currentVersion",
                        onClick = {
                            scope.launch {
                                val info = updateChecker.check(currentVersion)
                                if (info != null && info.isNewer) {
                                    onShowUpdateDialog(info)
                                } else {
                                    onShowUpdateDialog(
                                        UpdateInfo(
                                            latestVersion = currentVersion,
                                            downloadUrl = "",
                                            releaseNotes = "已是最新版本",
                                            isNewer = false
                                        )
                                    )
                                }
                            }
                        }
                    )
                    HorizontalDivider(color = Color(0xFFEDE8DE))
                    AboutInfoRow(
                        icon = Icons.Outlined.Lock,
                        label = "开源许可",
                        value = "MIT License",
                        onClick = {
                            uriHandler.openUri("https://github.com/hzhkdh/glut-schedule/blob/main/LICENSE")
                        }
                    )
                    HorizontalDivider(color = Color(0xFFEDE8DE))
                    AboutInfoRow(
                        icon = Icons.Outlined.Language,
                        label = "项目地址",
                        value = "GitHub",
                        onClick = {
                            uriHandler.openUri("https://github.com/hzhkdh/glut-schedule")
                        }
                    )
                    HorizontalDivider(color = Color(0xFFEDE8DE))
                    AboutInfoRow(
                        icon = Icons.Outlined.Email,
                        label = "反馈建议",
                        value = "hezh0425@qq.com",
                        onClick = {
                            uriHandler.openUri("mailto:hezh0425@qq.com")
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "本应用图标及导航栏校徽为桂林理工大学标识，\n仅供校内学生个人使用，与学校官方无关。",
                color = Color(0xFF9CA3AF),
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Text(
                text = "Made with ❤️ by hezh",
                color = Color(0xFF4A5568),
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 24.dp)
            )
        }
    }
}

@Composable
private fun AboutInfoRow(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color(0xFF3F7DF6),
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = label,
            color = Color(0xFF141821),
            fontSize = 15.sp,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            color = Color(0xFF667085),
            fontSize = 13.sp,
            maxLines = 1
        )
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = Color(0xFF9CA3AF),
            modifier = Modifier.size(20.dp)
        )
    }
}
