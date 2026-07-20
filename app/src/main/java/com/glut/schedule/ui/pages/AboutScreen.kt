package com.glut.schedule.ui.pages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glut.schedule.BuildConfig
import com.glut.schedule.service.UpdateChecker
import com.glut.schedule.service.UpdateInfo
import kotlinx.coroutines.launch

@Composable
fun AboutScreen(
    updateChecker: UpdateChecker,
    updateAvailableVersion: String,
    onShowUpdateDialog: (UpdateInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val currentVersion = BuildConfig.VERSION_NAME
    val hasUpdate = updateAvailableVersion.isNotBlank()
        && UpdateChecker.compareVersions(updateAvailableVersion, currentVersion) > 0

    Scaffold(
        modifier = modifier,
        containerColor = Color(0xFFF6F4EF),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
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
                                trailing = {
                                    if (hasUpdate) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                "v$updateAvailableVersion",
                                                color = Color(0xFFDC2626),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .background(Color(0xFFDC2626), CircleShape)
                                            )
                                        }
                                    } else {
                                        Spacer(modifier = Modifier.width(1.dp))
                                    }
                                },
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
                                                    apkDownloadUrl = "",
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
                                icon = Icons.Outlined.Person,
                                label = "维护者",
                                value = "24人工智能 hezh",
                                onClick = {
                                    uriHandler.openUri("https://github.com/hzhkdh")
                                }
                            )
                            HorizontalDivider(color = Color(0xFFEDE8DE))
                            AboutInfoRow(
                                icon = Icons.Outlined.Groups,
                                label = "贡献者",
                                value = "24人工智能 mzjia",
                                onClick = {
                                    uriHandler.openUri("https://github.com/m-z-jia")
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
                                icon = MiniProgramIcon,
                                label = "小程序",
                                value = MINI_PROGRAM_SEARCH_TEXT,
                                onClick = {
                                    val message = if (copyMiniProgramSearchText(context)) {
                                        MINI_PROGRAM_COPY_SUCCESS
                                    } else {
                                        MINI_PROGRAM_COPY_FAILURE
                                    }
                                    scope.launch { snackbarHostState.showSnackbar(message) }
                                }
                            )
                            HorizontalDivider(color = Color(0xFFEDE8DE))
                            AboutInfoRow(
                                icon = Icons.Outlined.BugReport,
                                label = "提交问题",
                                value = "GitHub Issues",
                                onClick = {
                                    uriHandler.openUri("https://github.com/hzhkdh/glut-schedule/issues/new")
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
                }
            }

            item {
                Text(
                    text = "本应用为独立开发的非官方工具\n与任何学校官方机构无隶属或授权关系",
                    color = Color(0xFF9CA3AF),
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp, bottom = 24.dp)
                )
            }
        }
    }
}

private const val MINI_PROGRAM_SEARCH_TEXT = "桂系一站式"
private const val MINI_PROGRAM_COPY_SUCCESS = "已复制“桂系一站式”，请前往微信搜索"
private const val MINI_PROGRAM_COPY_FAILURE = "复制失败，请手动在微信搜索“桂系一站式”"

private val MiniProgramIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "MiniProgram",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 3f)
            curveTo(16.97f, 3f, 21f, 7.03f, 21f, 12f)
            curveTo(21f, 16.97f, 16.97f, 21f, 12f, 21f)
            curveTo(7.03f, 21f, 3f, 16.97f, 3f, 12f)
            curveTo(3f, 7.03f, 7.03f, 3f, 12f, 3f)
            close()
        }
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(14.8f, 7.8f)
            curveTo(13.7f, 6.5f, 11.5f, 6.5f, 10.1f, 7.8f)
            curveTo(8.8f, 9.1f, 8.8f, 10.6f, 10.1f, 11.4f)
            curveTo(11.3f, 12.2f, 12.7f, 11.8f, 13.5f, 10.7f)
        }
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(9.2f, 16.2f)
            curveTo(10.3f, 17.5f, 12.5f, 17.5f, 13.9f, 16.2f)
            curveTo(15.2f, 14.9f, 15.2f, 13.4f, 13.9f, 12.6f)
            curveTo(12.7f, 11.8f, 11.3f, 12.2f, 10.5f, 13.3f)
        }
    }.build()
}

private fun copyMiniProgramSearchText(context: Context): Boolean {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return false
    return runCatching {
        clipboard.setPrimaryClip(ClipData.newPlainText("小程序名称", MINI_PROGRAM_SEARCH_TEXT))
    }.isSuccess
}

@Composable
private fun AboutInfoRow(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {}
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
        trailing()
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
