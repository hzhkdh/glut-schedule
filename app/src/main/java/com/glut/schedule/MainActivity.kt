package com.glut.schedule

import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
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
            @OptIn(ExperimentalMaterial3Api::class)
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

                val context = androidx.compose.ui.platform.LocalContext.current
                val backgroundPicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri: android.net.Uri? ->
                    uri ?: return@rememberLauncherForActivityResult
                    val uriText = uri.toString()
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    scope.launch {
                        val metrics = resources.displayMetrics
                        val loaded = container.backgroundStore.preload(
                            uri = uriText,
                            targetWidth = metrics.widthPixels,
                            targetHeight = metrics.heightPixels
                        )
                        if (loaded) {
                            scheduleViewModel.setCustomBackgroundUri(uriText)
                        }
                    }
                }

                val isSchedulePage = selectedItem == DrawerItem.Schedule

                DisposableEffect(selectedItem) {
                    applySystemBarStyle(lightIcons = !isSchedulePage)
                    onDispose { }
                }

                // Check for updates on launch + persist for red dot
                val updateAvailableVersion by container.settingsStore.updateAvailableVersion.collectAsState(initial = "")
                val updateSeenVersion by container.settingsStore.updateSeenVersion.collectAsState(initial = "")
                val showUpdateDot = updateAvailableVersion.isNotBlank()
                    && updateAvailableVersion != BuildConfig.VERSION_NAME
                    && updateAvailableVersion != updateSeenVersion

                DisposableEffect(Unit) {
                    scope.launch {
                        val info = container.updateChecker.check(BuildConfig.VERSION_NAME)
                        if (info != null && info.isNewer) {
                            container.settingsStore.setUpdateAvailable(info.latestVersion)
                        }
                    }
                    onDispose { }
                }

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet(
                            modifier = Modifier.fillMaxWidth(0.75f),
                            drawerContainerColor = Color(0xFFE8E4D6),
                            drawerContentColor = Color(0xFF141821)
                        ) {
                            DrawerHeader(onClose = { scope.launch { drawerState.close() } })
                            HorizontalDivider(color = Color(0xFFDDE2EA))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .background(Color(0xFFF6F4EF))
                            ) {
                                Spacer(modifier = Modifier.height(8.dp))
                                LazyColumn {
                                    items(DrawerItem.entries.toList()) { item ->
                                        DrawerMenuItem(
                                            item = item,
                                            isSelected = selectedItem == item,
                                            showDot = item == DrawerItem.About && showUpdateDot,
                                            onClick = {
                                                if (item == DrawerItem.About && showUpdateDot) {
                                                    scope.launch {
                                                        container.settingsStore.markUpdateSeen(updateAvailableVersion)
                                                    }
                                                }
                                                selectedItem = item
                                                scope.launch { drawerState.close() }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                ) {
                    Scaffold(
                        topBar = {
                            // 课程表页面不显示单独的 TopAppBar，菜单按钮集成在 ScheduleHeader 中
                            if (!isSchedulePage) {
                                TopAppBar(
                                    title = {
                                        Text(
                                            selectedItem.title,
                                            color = Color(0xFF141821),
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    },
                                    navigationIcon = {
                                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                            Icon(
                                                imageVector = Icons.Outlined.Menu,
                                                contentDescription = "菜单",
                                                tint = Color(0xFF141821),
                                                modifier = Modifier.size(26.dp)
                                            )
                                        }
                                    },
                                    actions = {
                                        when (selectedItem) {
                                            DrawerItem.Exam -> {
                                                val examState by examViewModel.uiState.collectAsState()
                                                IconButton(
                                                    onClick = examViewModel::refreshExams,
                                                    enabled = !examState.isRefreshing
                                                ) {
                                                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                                                }
                                            }
                                            DrawerItem.Score -> {
                                                val scoreState by scoreViewModel.uiState.collectAsState()
                                                IconButton(
                                                    onClick = scoreViewModel::refreshScores,
                                                    enabled = !scoreState.isRefreshing
                                                ) {
                                                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                                                }
                                            }
                                            else -> {}
                                        }
                                    },
                                    colors = TopAppBarDefaults.topAppBarColors(
                                        containerColor = Color(0xFFE8E4D6)
                                    )
                                )
                            }
                        },
                        // 课程表页面不消耗系统栏空间，让背景铺满全屏
                        contentWindowInsets = if (isSchedulePage) WindowInsets(0) else ScaffoldDefaults.contentWindowInsets,
                        containerColor = if (isSchedulePage) Color(0xFF07111F) else Color(0xFFF6F4EF)
                    ) { padding ->
                        Box(modifier = Modifier.padding(padding)) {
                            when (selectedItem) {
                                DrawerItem.Schedule -> ScheduleScreen(
                                    viewModel = scheduleViewModel,
                                    backgroundStore = container.backgroundStore,
                                    customBackgroundBitmap = scheduleBgBitmap,
                                    onImportClick = { selectedItem = DrawerItem.Import },
                                    onExamClick = { selectedItem = DrawerItem.Exam },
                                    onDrawerOpen = { scope.launch { drawerState.open() } }
                                )
                                DrawerItem.Score -> ScoreScreen(viewModel = scoreViewModel)
                                DrawerItem.Exam -> ExamScreen(
                                    viewModel = examViewModel,
                                    onBack = { selectedItem = DrawerItem.Schedule }
                                )
                                DrawerItem.Import -> DirectLoginScreen(viewModel = directLoginViewModel)
                                DrawerItem.Settings -> SettingsPage(
                                    showWeekend = scheduleUiState.showWeekend,
                                    onShowWeekendChange = scheduleViewModel::setShowWeekend,
                                    hasCustomBackground = scheduleUiState.customBackgroundUri.isNotBlank(),
                                    onPickBackground = { backgroundPicker.launch(arrayOf("image/*")) },
                                    onClearBackground = { scheduleViewModel.clearCustomBackground() }
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

    private fun applySystemBarStyle(lightIcons: Boolean) {
        val lightFlag = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        window.statusBarColor = AndroidColor.TRANSPARENT
        val visibility = window.decorView.systemUiVisibility
        window.decorView.systemUiVisibility = if (lightIcons) {
            visibility or lightFlag   // dark icons for light page backgrounds
        } else {
            visibility and lightFlag.inv()  // white icons for dark page backgrounds
        }
        window.navigationBarColor = if (lightIcons) {
            AndroidColor.rgb(0xF6, 0xF4, 0xEF)  // matches menu page F6F4EF
        } else {
            AndroidColor.rgb(17, 24, 39)  // matches schedule dark background
        }
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
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📅", fontSize = 32.sp)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("桂工课表", color = Color(0xFF141821), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("简单 高效 纯粹", color = Color(0xFF667085), fontSize = 13.sp)
                }
            }
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = Color(0xFF667085), modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun DrawerMenuItem(
    item: DrawerItem,
    isSelected: Boolean,
    showDot: Boolean = false,
    onClick: () -> Unit
) {
    val bgColor = if (isSelected) Color(0xFF3F7DF6).copy(alpha = 0.10f) else Color.Transparent
    val contentColor = if (isSelected) Color(0xFF3F7DF6) else Color(0xFF667085)

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
        Text(item.title, color = contentColor, fontSize = 16.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (showDot) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(Color(0xFFDC2626), RoundedCornerShape(4.dp))
            )
        }
    }
}

// ---- Settings Page ----

@Composable
private fun SettingsPage(
    showWeekend: Boolean,
    onShowWeekendChange: (Boolean) -> Unit,
    hasCustomBackground: Boolean = false,
    onPickBackground: () -> Unit = {},
    onClearBackground: () -> Unit = {}
) {
    val settingsBg = Color(0xFFF6F4EF)
    val settingsPrimary = Color(0xFF141821)
    val settingsSecondary = Color(0xFF667085)
    val settingsCardBg = Color(0xFFFFFEFB)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(settingsBg)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Display section
            Text("显示", color = settingsSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = settingsCardBg,
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("显示周末", color = settingsPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Switch(checked = showWeekend, onCheckedChange = onShowWeekendChange)
                }
            }

            // Background section
            Text("背景", color = settingsSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = settingsCardBg,
                shape = RoundedCornerShape(14.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onPickBackground)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("选择背景图片", color = settingsPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Icon(
                            Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            tint = settingsSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    if (hasCustomBackground) {
                        HorizontalDivider(color = Color(0xFFEDE8DE))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onClearBackground)
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("恢复默认背景", color = Color(0xFFDC2626), fontSize = 15.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
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
