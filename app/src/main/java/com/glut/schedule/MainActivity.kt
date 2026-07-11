package com.glut.schedule

import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glut.schedule.data.model.NoticeInfo
import com.glut.schedule.data.model.hasUnreadNotices
import com.glut.schedule.service.NoticeChecker
import com.glut.schedule.service.UpdateChecker
import com.glut.schedule.service.UpdateInfo
import com.glut.schedule.ui.navigation.DrawerItem
import com.glut.schedule.ui.pages.AboutScreen
import com.glut.schedule.ui.pages.FaqScreen
import com.glut.schedule.ui.pages.SemesterOverviewScreen
import com.glut.schedule.ui.pages.SemesterOverviewViewModel
import com.glut.schedule.ui.pages.SemesterOverviewViewModelFactory
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
import com.glut.schedule.ui.pages.GradeExamScreen
import com.glut.schedule.ui.pages.GradeExamViewModel
import com.glut.schedule.ui.pages.GradeExamViewModelFactory
import com.glut.schedule.ui.pages.NoticeScreen
import com.glut.schedule.ui.pages.StudyPlanScreen
import com.glut.schedule.ui.pages.StudyPlanViewModel
import com.glut.schedule.ui.pages.StudyPlanViewModelFactory
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
                var showUpdateDialog by remember { mutableStateOf<UpdateDialogState?>(null) }
                var autoPopupUpdateVersion by remember { mutableStateOf<String?>(null) }
                var showNoticePopup by remember { mutableStateOf<NoticeInfo?>(null) }
                var noticePopupSessionDismissedIds by remember { mutableStateOf(emptySet<String>()) }
                var initialNoticeCheckFinished by remember { mutableStateOf(false) }
                var showResetConfirm by remember { mutableStateOf(false) }

                // 返回键：先关抽屉 → 再回到课表主页 → 最后退出
                BackHandler(enabled = true) {
                    when {
                        drawerState.isOpen -> scope.launch { drawerState.close() }
                        selectedItem != DrawerItem.Schedule -> selectedItem = DrawerItem.Schedule
                        else -> finish()
                    }
                }

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
                val gradeExamViewModel: GradeExamViewModel = viewModel(
                    factory = GradeExamViewModelFactory(
                        repository = container.scheduleRepository,
                        sessionStore = container.academicSessionStore,
                        loginService = container.academicLoginService,
                        gradeExamParser = container.gradeExamParser
                    )
                )
                val studyPlanViewModel: StudyPlanViewModel = viewModel(
                    factory = StudyPlanViewModelFactory(
                        repository = container.scheduleRepository,
                        sessionStore = container.academicSessionStore,
                        loginService = container.academicLoginService,
                        studyPlanParser = container.studyPlanParser
                    )
                )
                val semesterOverviewViewModel: SemesterOverviewViewModel = viewModel(
                    factory = SemesterOverviewViewModelFactory(
                        repository = container.scheduleRepository,
                        settingsStore = container.settingsStore,
                        sessionStore = container.academicSessionStore,
                        scheduleParser = container.academicScheduleParser,
                        loginService = container.academicLoginService
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
                        scoreParser = container.scoreParser,
                        gradeExamParser = container.gradeExamParser,
                        studyPlanParser = container.studyPlanParser
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

                // Red dot persists until user actually updates to the latest version
                val updateAvailableVersion by container.settingsStore.updateAvailableVersion.collectAsState(initial = "")
                val showUpdateDot = updateAvailableVersion.isNotBlank()
                    && UpdateChecker.compareVersions(updateAvailableVersion, BuildConfig.VERSION_NAME) > 0
                val cachedNoticesJson by container.settingsStore.cachedNoticesJson.collectAsState(initial = "")
                val readNoticeIds by container.settingsStore.readNoticeIds.collectAsState(initial = emptySet())
                val dismissedNoticePopupIds by container.settingsStore.dismissedNoticePopupIds.collectAsState(initial = emptySet())
                var notices by remember { mutableStateOf(NoticeChecker.parseNotices(cachedNoticesJson)) }
                val currentNoticeIds = notices.map { it.id }.toSet()
                val showNoticeDot = hasUnreadNotices(currentNoticeIds, readNoticeIds)

                LaunchedEffect(cachedNoticesJson) {
                    notices = NoticeChecker.parseNotices(cachedNoticesJson)
                }

                LaunchedEffect(Unit) {
                    val info = container.updateChecker.check(BuildConfig.VERSION_NAME)
                    if (info != null && info.isNewer) {
                        container.settingsStore.setUpdateAvailable(info.latestVersion)
                        val dismissedVersion = container.settingsStore.dismissedUpdatePopupVersion.first()
                        if (dismissedVersion != info.latestVersion) {
                            autoPopupUpdateVersion = info.latestVersion
                            showUpdateDialog = UpdateDialogState.Idle(info)
                        }
                    }

                    val result = container.noticeChecker.check()
                    if (result != null) {
                        notices = result.notices
                        container.settingsStore.setCachedNoticesJson(result.rawJson)
                    }
                    initialNoticeCheckFinished = true
                }

                LaunchedEffect(selectedItem, currentNoticeIds) {
                    if (selectedItem == DrawerItem.Notice) {
                        container.settingsStore.markNoticesRead(currentNoticeIds)
                    }
                }

                LaunchedEffect(
                    initialNoticeCheckFinished,
                    notices,
                    dismissedNoticePopupIds,
                    noticePopupSessionDismissedIds,
                    showUpdateDialog,
                    showNoticePopup
                ) {
                    if (initialNoticeCheckFinished && showUpdateDialog == null && showNoticePopup == null) {
                        val alreadyShownIds = dismissedNoticePopupIds + noticePopupSessionDismissedIds
                        val latestNotice = notices.firstOrNull()
                        if (latestNotice != null && latestNotice.id !in alreadyShownIds) {
                            showNoticePopup = latestNotice
                        }
                    }
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
                                    // 常用
                                    item {
                                        Text(
                                            "常用",
                                            color = Color(0xFF3F7DF6),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.padding(start = 24.dp, top = 4.dp, bottom = 4.dp)
                                        )
                                    }
                                    items(listOf(DrawerItem.Schedule, DrawerItem.Score, DrawerItem.Exam, DrawerItem.GradeExam, DrawerItem.StudyPlan, DrawerItem.SemesterOverview, DrawerItem.Import)) { item ->
                                        DrawerMenuItem(
                                            item = item,
                                            isSelected = selectedItem == item,
                                            showDot = item == DrawerItem.About && showUpdateDot,
                                            onClick = {
                                                selectedItem = item
                                                scope.launch { drawerState.close() }
                                            }
                                        )
                                    }
                                    // 其他
                                    item {
                                        Text(
                                            "其他",
                                            color = Color(0xFF3F7DF6),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp)
                                        )
                                    }
                                    items(listOf(DrawerItem.Settings, DrawerItem.Notice, DrawerItem.FAQ, DrawerItem.About)) { item ->
                                        DrawerMenuItem(
                                            item = item,
                                            isSelected = selectedItem == item,
                                            showDot = (item == DrawerItem.About && showUpdateDot)
                                                || (item == DrawerItem.Notice && showNoticeDot),
                                            onClick = {
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
                                            DrawerItem.GradeExam -> {
                                                val gradeExamState by gradeExamViewModel.uiState.collectAsState()
                                                IconButton(
                                                    onClick = gradeExamViewModel::refresh,
                                                    enabled = !gradeExamState.isRefreshing
                                                ) {
                                                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                                                }
                                            }
                                            DrawerItem.StudyPlan -> {
                                                val studyPlanState by studyPlanViewModel.uiState.collectAsState()
                                                IconButton(
                                                    onClick = studyPlanViewModel::refresh,
                                                    enabled = !studyPlanState.isRefreshing
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
                                DrawerItem.GradeExam -> GradeExamScreen(viewModel = gradeExamViewModel)
                                DrawerItem.StudyPlan -> StudyPlanScreen(viewModel = studyPlanViewModel)
                                DrawerItem.Exam -> ExamScreen(
                                    viewModel = examViewModel,
                                    onBack = { selectedItem = DrawerItem.Schedule }
                                )
                                DrawerItem.Import -> DirectLoginScreen(viewModel = directLoginViewModel)
                                DrawerItem.Settings -> SettingsPage(
                                    showWeekend = scheduleUiState.showWeekend,
                                    onShowWeekendChange = scheduleViewModel::setShowWeekend,
                                    showNoon = scheduleUiState.showNoon,
                                    onShowNoonChange = scheduleViewModel::setShowNoon,
                                    hasCustomBackground = scheduleUiState.customBackgroundUri.isNotBlank(),
                                    onPickBackground = { backgroundPicker.launch(arrayOf("image/*")) },
                                    onClearBackground = { scheduleViewModel.clearCustomBackground() },
                                    onReset = { showResetConfirm = true }
                                )
                                DrawerItem.Notice -> NoticeScreen(notices = notices)
                                DrawerItem.SemesterOverview -> SemesterOverviewScreen(viewModel = semesterOverviewViewModel)
                                DrawerItem.FAQ -> FaqScreen()
                                DrawerItem.About -> AboutScreen(
                                    updateChecker = container.updateChecker,
                                    updateAvailableVersion = updateAvailableVersion,
                                    onShowUpdateDialog = { info ->
                                        showUpdateDialog = UpdateDialogState.Idle(info)
                                    }
                                )
                            }
                        }
                    }
                }

                // Update dialog
                showUpdateDialog?.let { state ->
                    UpdateDialog(
                        state = state,
                        appUpdater = container.appUpdater,
                        onDismiss = {
                            autoPopupUpdateVersion?.let { version ->
                                scope.launch {
                                    container.settingsStore.markUpdatePopupDismissed(version)
                                }
                            }
                            autoPopupUpdateVersion = null
                            showUpdateDialog = null
                        },
                        onStateChange = { showUpdateDialog = it }
                    )
                }

                showNoticePopup?.let { popupNotice ->
                    NoticePopupDialog(
                        notice = popupNotice,
                        onDismiss = {
                            val ids = setOf(popupNotice.id)
                            noticePopupSessionDismissedIds = noticePopupSessionDismissedIds + ids
                            showNoticePopup = null
                            scope.launch {
                                container.settingsStore.markNoticePopupsDismissed(ids)
                            }
                        },
                        onOpenNotices = {
                            val ids = setOf(popupNotice.id)
                            noticePopupSessionDismissedIds = noticePopupSessionDismissedIds + ids
                            showNoticePopup = null
                            selectedItem = DrawerItem.Notice
                            scope.launch {
                                container.settingsStore.markNoticePopupsDismissed(ids)
                                container.settingsStore.markNoticesRead(currentNoticeIds)
                            }
                        }
                    )
                }

                if (showResetConfirm) {
                    AlertDialog(
                        onDismissRequest = { showResetConfirm = false },
                        title = { Text("重置应用") },
                        text = { Text("将清除所有数据（课表、成绩、考试、教学计划、设置、登录凭证），恢复到初次使用状态。此操作不可撤销，确定继续吗？") },
                        confirmButton = {
                            Text("确认重置", color = Color(0xFFDC2626),
                                modifier = Modifier.clickable {
                                    showResetConfirm = false
                                    directLoginViewModel.clearLoginState()
                                    scope.launch {
                                        container.scheduleRepository.clearAllData()
                                        container.settingsStore.clearAll()
                                        container.academicSessionStore.clearAll()
                                        container.credentialStore.clearCredentials()
                                    }
                                }.padding(8.dp))
                        },
                        dismissButton = {
                            Text("取消", modifier = Modifier.clickable { showResetConfirm = false }.padding(8.dp))
                        }
                    )
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
                Image(
                    painter = painterResource(R.drawable.glut_logo),
                    contentDescription = "校徽",
                    modifier = Modifier.size(38.dp)
                )
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
    showNoon: Boolean = false,
    onShowNoonChange: (Boolean) -> Unit = {},
    hasCustomBackground: Boolean = false,
    onPickBackground: () -> Unit = {},
    onClearBackground: () -> Unit = {},
    onReset: () -> Unit = {}
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
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = settingsCardBg,
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("显示中午", color = settingsPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Switch(checked = showNoon, onCheckedChange = onShowNoonChange)
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

            // Data management section
            Text("数据管理", color = settingsSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = settingsCardBg,
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onReset)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("重置应用", color = Color(0xFFDC2626), fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text("恢复初次使用状态", color = settingsSecondary, fontSize = 12.sp)
                }
            }
        }
    }
}

// ---- Notice Popup Dialog ----

@Composable
private fun NoticePopupDialog(
    notice: NoticeInfo,
    onDismiss: () -> Unit,
    onOpenNotices: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFFFFFBFE),
        titleContentColor = Color(0xFF1D1B20),
        textContentColor = Color(0xFF49454F),
        title = { Text("新通知", fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
            ) {
                item {
                    NoticePopupContent(notice = notice)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenNotices) {
                Text("查看通知")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("知道了")
            }
        }
    )
}

@Composable
private fun NoticePopupContent(
    notice: NoticeInfo
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = notice.title,
                color = Color(0xFF1D1B20),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            NoticePopupLevelBadge(level = notice.level)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = notice.publishedAt.toString(),
            color = Color(0xFF6F6A72),
            fontSize = 12.sp
        )
        if (notice.content.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = notice.content,
                color = Color(0xFF3D3940),
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
private fun NoticePopupLevelBadge(level: String) {
    val (container, content) = when (level.lowercase()) {
        "important" -> Color(0xFFFEE2E2) to Color(0xFFDC2626)
        "warning" -> Color(0xFFFFF3D8) to Color(0xFFD97706)
        "update" -> Color(0xFFE8F0FE) to Color(0xFF245CA6)
        else -> Color(0xFFEEF2F7) to Color(0xFF667085)
    }
    val label = when (level.lowercase()) {
        "important" -> "重要"
        "warning" -> "提醒"
        "update" -> "更新"
        else -> "通知"
    }

    Text(
        text = label,
        color = content,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .background(container, RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp)
    )
}

// ---- Update Dialog ----

private sealed class UpdateDialogState {
    data class Idle(val info: UpdateInfo) : UpdateDialogState()
    data class Downloading(
        val info: UpdateInfo,
        val progress: Float,
        val downloadedMB: String,
        val totalMB: String
    ) : UpdateDialogState()
    data class DownloadFailed(val info: UpdateInfo, val message: String) : UpdateDialogState()
    data class Done(val info: UpdateInfo, val apkFile: java.io.File) : UpdateDialogState()
}

@Composable
private fun UpdateDialog(
    state: UpdateDialogState,
    appUpdater: com.glut.schedule.service.AppUpdater,
    onDismiss: () -> Unit,
    onStateChange: (UpdateDialogState?) -> Unit
) {
    val scope = rememberCoroutineScope()

    fun startDownload(info: UpdateInfo) {
        scope.launch {
            onStateChange(
                UpdateDialogState.Downloading(
                    info = info,
                    progress = 0f,
                    downloadedMB = "0",
                    totalMB = "..."
                )
            )
            try {
                val apkFile = appUpdater.downloadApk(info.apkDownloadUrl) { downloaded, total ->
                    val progress = if (total > 0) downloaded.toFloat() / total else 0f
                    val dMB = "%.1f".format(downloaded / 1_000_000.0)
                    val tMB = if (total > 0) "%.1f".format(total / 1_000_000.0) else "?"
                    onStateChange(
                        UpdateDialogState.Downloading(
                            info = info,
                            progress = progress,
                            downloadedMB = dMB,
                            totalMB = tMB
                        )
                    )
                }
                onStateChange(UpdateDialogState.Done(info, apkFile))
            } catch (e: Exception) {
                onStateChange(
                    UpdateDialogState.DownloadFailed(
                        info = info,
                        message = e.message ?: "下载失败，请检查网络后重试"
                    )
                )
            }
        }
    }

    when (state) {
        is UpdateDialogState.Idle -> {
            AlertDialog(
                onDismissRequest = { if (!state.info.isForceUpdate) onDismiss() },
                containerColor = Color(0xFFFFFBFE),
                titleContentColor = Color(0xFF1D1B20),
                textContentColor = Color(0xFF49454F),
                title = {
                    Text(
                        if (state.info.isNewer) "发现新版本 v${state.info.latestVersion}"
                        else "已是最新版本"
                    )
                },
                text = {
                    Column {
                        if (state.info.isNewer) {
                            Text("当前版本: v${BuildConfig.VERSION_NAME}")
                            Spacer(modifier = Modifier.height(8.dp))
                            if (state.info.releaseNotes.isNotBlank()) {
                                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                                    item {
                                        Text(
                                            state.info.releaseNotes,
                                            fontSize = 14.sp,
                                            lineHeight = 20.sp,
                                            color = Color(0xFF3D3940)
                                        )
                                    }
                                }
                            }
                        } else {
                            Text("当前已是最新版本 v${BuildConfig.VERSION_NAME}")
                        }
                    }
                },
                confirmButton = {
                    if (state.info.isNewer && state.info.apkDownloadUrl.isNotBlank()) {
                        TextButton(onClick = { startDownload(state.info) }) {
                            Text("立即更新")
                        }
                    }
                },
                dismissButton = {
                    if (!state.info.isForceUpdate) {
                        TextButton(onClick = onDismiss) { Text("取消") }
                    }
                }
            )
        }

        is UpdateDialogState.Downloading -> {
            AlertDialog(
                onDismissRequest = {
                    if (!state.info.isForceUpdate) {
                        onDismiss()
                        onStateChange(null)
                    }
                },
                containerColor = Color(0xFFFFFBFE),
                titleContentColor = Color(0xFF1D1B20),
                textContentColor = Color(0xFF49454F),
                title = { Text("正在下载 v${state.info.latestVersion}") },
                text = {
                    Column {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            progress = { state.progress },
                            color = Color(0xFF3F7DF6),
                            trackColor = Color(0xFF3F7DF6).copy(alpha = 0.12f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "${state.downloadedMB} MB / ${state.totalMB} MB  (${(state.progress * 100).toInt()}%)",
                            fontSize = 13.sp,
                            color = Color(0xFF667085)
                        )
                    }
                },
                confirmButton = {},
                dismissButton = {
                    if (!state.info.isForceUpdate) {
                        TextButton(onClick = { onDismiss(); onStateChange(null) }) {
                            Text("取消下载")
                        }
                    }
                }
            )
        }

        is UpdateDialogState.DownloadFailed -> {
            AlertDialog(
                onDismissRequest = { if (!state.info.isForceUpdate) onDismiss() },
                containerColor = Color(0xFFFFFBFE),
                titleContentColor = Color(0xFF1D1B20),
                textContentColor = Color(0xFF49454F),
                title = { Text("下载失败") },
                text = { Text(state.message, color = Color(0xFF3D3940)) },
                confirmButton = {
                    TextButton(onClick = { startDownload(state.info) }) {
                        Text("重试")
                    }
                },
                dismissButton = {
                    if (!state.info.isForceUpdate) {
                        TextButton(onClick = onDismiss) { Text("取消") }
                    }
                }
            )
        }

        is UpdateDialogState.Done -> {
            AlertDialog(
                onDismissRequest = { if (!state.info.isForceUpdate) onDismiss() },
                containerColor = Color(0xFFFFFBFE),
                titleContentColor = Color(0xFF1D1B20),
                textContentColor = Color(0xFF49454F),
                title = { Text("下载完成") },
                text = { Text("v${state.info.latestVersion} 已下载，是否立即安装？") },
                confirmButton = {
                    TextButton(onClick = {
                        appUpdater.installApk(state.apkFile)
                        onDismiss()
                    }) {
                        Text("立即安装")
                    }
                },
                dismissButton = {
                    if (!state.info.isForceUpdate) {
                        TextButton(onClick = onDismiss) { Text("稍后") }
                    }
                }
            )
        }
    }
}
