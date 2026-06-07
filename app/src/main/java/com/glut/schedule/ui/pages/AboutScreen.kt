package com.glut.schedule.ui.pages

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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Code
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
        modifier = modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .navigationBarsPadding(),
        containerColor = Color(0xFF0B1622)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            // App icon
            Box(
                modifier = Modifier.size(80.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "🎓", fontSize = 48.sp)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "桂工课表",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "桂林理工大学 · 课表管理",
                color = Color(0xFF8A93A3),
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Info card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF172033),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(4.dp)) {
                    AboutInfoRow(
                        icon = Icons.Outlined.Code,
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
                    HorizontalDivider(color = Color(0xFF1E2A3D))
                    AboutInfoRow(
                        icon = Icons.Outlined.Lock,
                        label = "开源许可",
                        value = "MIT License",
                        onClick = {
                            uriHandler.openUri("https://github.com/hzhkdh/glut-schedule/blob/main/LICENSE")
                        }
                    )
                    HorizontalDivider(color = Color(0xFF1E2A3D))
                    AboutInfoRow(
                        icon = Icons.Outlined.Code,
                        label = "项目地址",
                        value = "GitHub",
                        onClick = {
                            uriHandler.openUri("https://github.com/hzhkdh/glut-schedule")
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

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
            tint = Color(0xFF7DD3FC),
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = label,
            color = Color.White,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            color = Color(0xFF8A93A3),
            fontSize = 14.sp
        )
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = Color(0xFF4A5568),
            modifier = Modifier.size(20.dp)
        )
    }
}
