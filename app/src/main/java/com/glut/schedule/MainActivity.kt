package com.glut.schedule

import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glut.schedule.service.UpdateInfo
import com.glut.schedule.ui.navigation.DrawerItem
import com.glut.schedule.ui.pages.AboutScreen
import com.glut.schedule.ui.pages.DirectLoginScreen
import com.glut.schedule.ui.pages.DirectLoginViewModel
import com.glut.schedule.ui.pages.DirectLoginViewModelFactory
import com.glut.schedule.ui.pages.ExamScreen
import com.glut.schedule.ui.pages.ExamViewModel
import com.glut.schedule.ui.pages.ExamViewModelFactory
import com.glut.schedule.ui.pages.ScheduleScreen
import com.glut.schedule.ui.pages.ScheduleViewModel
import com.glut.schedule.ui.pages.ScheduleViewModelFactory
import com.glut.schedule.ui.pages.ScoreScreen
import com.glut.schedule.ui.pages.ScoreViewModel
import com.glut.schedule.ui.pages.ScoreViewModelFactory
import com.glut.schedule.ui.theme.GlutScheduleTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as ScheduleApplication).appContainer
        val (bgW, bgH) = backgroundTargetSize()
        val initialBgUri = runBlocking(Dispatchers.IO) {
            container.settingsStore.customBackgroundUri.first()
        }
        if (initialBgUri.isNotBlank()) {
            val loaded = container.backgroundStore.preloadBlocking(initialBgUri, bgW, bgH)
            if (!loaded) {
                runBlocking(Dispatchers.IO) { container.settingsStore.setCustomBackgroundUri("") }
            }
        }

        setContent {
            GlutScheduleTheme {
                val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                var selectedItem by remember { mutableStateOf(DrawerItem.Schedule) }
                var showUpdateDialog by remember { mutableStateOf<UpdateInfo?>(null) }

                val scheduleViewModel: ScheduleViewModel = viewModel(
                    factory = ScheduleViewModelFactory(
                        repository = container.scheduleRepository,
                        settingsStore = container.settingsStore,
                        sessionStore = container.academicSessionStore,
                        loginService = container.academicLoginService,
                        apiProbeService = container.apiProbeService,
                        parser = container.academicScheduleParser
                    )
                )
                val examViewModel: ExamViewModel = viewModel(
                    factory = ExamViewModelFactory(
                        repository = container.scheduleRepository,
                        sessionStore = container.academicSessionStore,
                        examService = container.academicExamService,
                        loginService = container.academicLoginService
                    )
                )
                val scoreViewModel: ScoreViewModel = viewModel(
                    factory = ScoreViewModelFactory(
                        repository = container.scheduleRepository,
                        sessionStore = container.academicSessionStore,
                        loginService = container.academicLoginService,
                        scoreParser = container.scoreParser
                    )
                )
                val directLoginViewModel: DirectLoginViewModel = viewModel(
                    factory = DirectLoginViewModelFactory(
                        loginService = container.academicLoginService,
                        sessionStore = container.academicSessionStore,
                        credentialStore = container.credentialStore,
                        scheduleRepository = container.scheduleRepository,
                        settingsStore = container.settingsStore,
                        apiProbeService = container.apiProbeService,
                        scheduleParser = container.academicScheduleParser,
                        examParser = container.examParser,
                        scoreParser = container.scoreParser
                    )
                )

                val scheduleUiState by scheduleViewModel.uiState.collectAsState()
                val scheduleBgBitmap = if (scheduleUiState.customBackgroundUri.isNotBlank()) {
                    container.backgroundStore.get(scheduleUiState.customBackgroundUri)
                } else null

                DisposableEffect(selectedItem) {
                    applySystemBarStyle()
                    onDispose { }
                }

                // Check for updates on launch
                DisposableEffect(Unit) {
                    scope.launch {
                        val info = container.updateChecker.check(BuildConfig.VERSION_NAME)
                        if (info != null && info.isNewer) {
                            showUpdateDialog = info
                        }
                    }
                    onDispose { }
                }

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet(
                            drawerContainerColor = Color(0xFF0B1622),
                            drawerContentColor = Color.White
                        ) {
                            DrawerHeader(onClose = { scope.launch { drawerState.close() } })
                            HorizontalDivider(color = Color(0xFF1E2A3D))
                            Spacer(modifier = Modifier.height(8.dp))
                            LazyColumn {
                                items(DrawerItem.entries.toList()) { item ->
                                    DrawerMenuItem(
                                        item = item,
                                        isSelected = selectedItem == item,
                                        onClick = {
                                            selectedItem = item
                                            scope.launch { drawerState.close() }
                                        }
                                    )
                                }
                            }
                        }
                    }
                ) {
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = {
                                    Text(
                                        selectedItem.title,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                },
                                navigationIcon = {
                                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                        Icon(
                                            imageVector = selectedItem.icon,
                                            contentDescription = "菜单",
                                            tint = Color(0xFF7DD3FC),
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = Color.Transparent
                                )
                            )
                        },
                        containerColor = Color(0xFF07111F)
                    ) { padding ->
                        Box(modifier = Modifier.padding(padding)) {
                            when (selectedItem) {
                                DrawerItem.Schedule -> ScheduleScreen(
                                    viewModel = scheduleViewModel,
                                    backgroundStore = container.backgroundStore,
                                    customBackgroundBitmap = scheduleBgBitmap,
                                    onImportClick = { selectedItem = DrawerItem.Import },
                                    onExamClick = { selectedItem = DrawerItem.Exam }
                                )
                                DrawerItem.Score -> ScoreScreen(viewModel = scoreViewModel)
                                DrawerItem.Exam -> ExamScreen(
                                    viewModel = examViewModel,
                                    onBack = { selectedItem = DrawerItem.Schedule }
                                )
                                DrawerItem.Import -> DirectLoginScreen(viewModel = directLoginViewModel)
                                DrawerItem.Settings -> SettingsPage(
                                    showWeekend = scheduleUiState.showWeekend,
                                    onShowWeekendChange = scheduleViewModel::setShowWeekend
                                )
                                DrawerItem.About -> AboutScreen(
                                    updateChecker = container.updateChecker,
                                    onShowUpdateDialog = { showUpdateDialog = it }
                                )
                            }
                        }
                    }
                }

                // Update dialog
                showUpdateDialog?.let { info ->
                    UpdateDialog(info = info, onDismiss = { showUpdateDialog = null })
                }
            }
        }
    }

    private fun applySystemBarStyle() {
        val lightFlag = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.decorView.systemUiVisibility =
            window.decorView.systemUiVisibility and lightFlag.inv()
        window.navigationBarColor = AndroidColor.rgb(17, 24, 39)
    }

    private fun backgroundTargetSize(): Pair<Int, Int> {
        val metrics = resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }
}

// ---- Drawer Components ----

@Composable
private fun DrawerHeader(onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("🎓 桂工课表", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("桂林理工大学", color = Color(0xFF8A93A3), fontSize = 13.sp)
            }
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = Color(0xFF8A93A3), modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun DrawerMenuItem(
    item: DrawerItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bgColor = if (isSelected) Color(0xFF7DD3FC).copy(alpha = 0.12f) else Color.Transparent
    val contentColor = if (isSelected) Color(0xFF7DD3FC) else Color(0xFFB0B8C4)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .background(bgColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(item.icon, contentDescription = item.title, tint = contentColor, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Text(item.title, color = contentColor, fontSize = 16.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

// ---- Settings Page ----

@Composable
private fun SettingsPage(
    showWeekend: Boolean,
    onShowWeekendChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B1622))
            .windowInsetsPadding(WindowInsets.statusBars)
            .navigationBarsPadding()
            .padding(24.dp)
    ) {
        Text("设置", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(modifier = Modifier.height(24.dp))
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF172033),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("显示周末", color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Switch(checked = showWeekend, onCheckedChange = onShowWeekendChange)
            }
        }
    }
}

// ---- Update Dialog ----

@Composable
private fun UpdateDialog(info: UpdateInfo, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (info.isNewer) "发现新版本 v${info.latestVersion}" else "已是最新版本")
        },
        text = {
            Column {
                if (info.isNewer) {
                    Text("当前版本: v${BuildConfig.VERSION_NAME}")
                    Spacer(modifier = Modifier.height(8.dp))
                    if (info.releaseNotes.isNotBlank()) {
                        Text(info.releaseNotes.take(500), fontSize = 13.sp, color = Color.Gray)
                    }
                } else {
                    Text("当前已是最新版本 v${BuildConfig.VERSION_NAME}")
                }
            }
        },
        confirmButton = {
            if (info.isNewer && info.downloadUrl.isNotBlank()) {
                TextButton(onClick = { uriHandler.openUri(info.downloadUrl); onDismiss() }) {
                    Text("前往下载")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("确定") } }
    )
}
