package com.glut.schedule

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glut.schedule.data.model.NoticeInfo
import java.io.File
import com.glut.schedule.data.model.CourseColorMapper
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.hasUnreadNotices
import com.glut.schedule.service.NoticeChecker
import com.glut.schedule.service.UpdateChecker
import com.glut.schedule.service.UpdateInfo
import com.glut.schedule.ui.navigation.DrawerItem
import com.glut.schedule.ui.navigation.campusDrawerItems
import com.glut.schedule.ui.navigation.otherDrawerItems
import com.glut.schedule.ui.navigation.prepareDrawerSelection
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
import com.glut.schedule.ui.pages.FitnessScoreScreen
import com.glut.schedule.ui.pages.FitnessScoreViewModel
import com.glut.schedule.ui.pages.FitnessScoreViewModelFactory
import com.glut.schedule.ui.pages.FinanceScreen
import com.glut.schedule.ui.pages.FinanceViewModel
import com.glut.schedule.ui.pages.FinanceViewModelFactory
import com.glut.schedule.ui.pages.FinanceViewModelRegistry
import com.glut.schedule.ui.pages.CampusImageScreen
import com.glut.schedule.ui.pages.CampusImageViewModel
import com.glut.schedule.ui.pages.CampusImageViewModelFactory
import com.glut.schedule.ui.pages.ScheduleScreen
import com.glut.schedule.ui.pages.ClassPeriodSettingsScreen
import com.glut.schedule.ui.pages.ScheduleViewModel
import com.glut.schedule.ui.pages.ScheduleViewModelFactory
import com.glut.schedule.ui.components.ScheduleBackgroundStore
import com.glut.schedule.ui.components.BuiltInScheduleBackground
import com.glut.schedule.ui.pages.ScoreScreen
import com.glut.schedule.ui.pages.ScoreViewModel
import com.glut.schedule.ui.pages.ScoreViewModelFactory
import com.glut.schedule.ui.pages.ProfessionalScoreScreen
import com.glut.schedule.ui.pages.ProfessionalScoreViewModel
import com.glut.schedule.ui.pages.ProfessionalScoreViewModelFactory
import com.glut.schedule.ui.pages.GradeExamScreen
import com.glut.schedule.ui.pages.GradeExamViewModel
import com.glut.schedule.ui.pages.GradeExamViewModelFactory
import com.glut.schedule.ui.pages.NoticeScreen
import com.glut.schedule.ui.pages.StudyPlanScreen
import com.glut.schedule.ui.pages.StudyPlanViewModel
import com.glut.schedule.ui.pages.StudyPlanViewModelFactory
import com.glut.schedule.ui.theme.GlutScheduleTheme
import com.glut.schedule.data.settings.CampusType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private enum class SettingsSubPage(val title: String) {
    ROOT("设置"),
    COURSE_COLORS("课程卡片颜色"),
    CLASS_PERIODS("上课时间"),
    BUILT_IN_BACKGROUNDS("内置背景")
}

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
                var settingsSubPage by remember { mutableStateOf(SettingsSubPage.ROOT) }
                var drawerGestureBlocked by remember { mutableStateOf(false) }

                // 返回键：先关抽屉 → 再回到课表主页 → 最后退出
                BackHandler(enabled = true) {
                    when {
                        drawerState.isOpen -> scope.launch { drawerState.close() }
                        settingsSubPage != SettingsSubPage.ROOT -> settingsSubPage = SettingsSubPage.ROOT
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
                        semesterImportService = container.academicSemesterImportService
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
                val professionalScoreViewModel: ProfessionalScoreViewModel = viewModel(
                    factory = ProfessionalScoreViewModelFactory(
                        repository = container.scheduleRepository,
                        settingsStore = container.settingsStore,
                        sessionStore = container.academicSessionStore,
                        loginService = container.academicLoginService,
                        scoreParser = container.scoreParser,
                        studyPlanParser = container.studyPlanParser
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
                        semesterImportService = container.academicSemesterImportService,
                        scheduleParser = container.academicScheduleParser,
                        examParser = container.examParser,
                        scoreParser = container.scoreParser,
                        gradeExamParser = container.gradeExamParser,
                        studyPlanParser = container.studyPlanParser
                    )
                )
                val fitnessScoreViewModel: FitnessScoreViewModel = viewModel(
                    factory = FitnessScoreViewModelFactory(
                        service = container.fitnessApiService,
                        store = container.fitnessStore,
                        parser = container.fitnessParser
                    )
                )
                val campusType by container.settingsStore.campusType.collectAsStateWithLifecycle(initialValue = CampusType.GUILIN)
                val campusInfoViewModel: CampusImageViewModel? =
                    if (selectedItem == DrawerItem.CampusInfo) {
                        viewModel(
                            key = "campus-info",
                            factory = CampusImageViewModelFactory(container.campusImageService)
                        )
                    } else null
                val financeViewModels = remember { FinanceViewModelRegistry() }
                val financeViewModel: FinanceViewModel? = if (selectedItem == DrawerItem.Finance) {
                    financeViewModels.register(viewModel<FinanceViewModel>(
                        key = "finance-${campusType.name}",
                        factory = FinanceViewModelFactory(
                            gateway = container.financeApiService,
                            store = container.financeStore,
                            campus = campusType
                        )
                    ))
                } else null

                LaunchedEffect(campusType) {
                    if (
                        campusType != CampusType.GUILIN &&
                        selectedItem == DrawerItem.CampusInfo
                    ) {
                        selectedItem = DrawerItem.Schedule
                    }
                }

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
                val updateAvailableVersion by container.settingsStore.updateAvailableVersion.collectAsStateWithLifecycle(initialValue = "")
                val showUpdateDot = updateAvailableVersion.isNotBlank()
                    && UpdateChecker.compareVersions(updateAvailableVersion, BuildConfig.VERSION_NAME) > 0
                val cachedNoticesJson by container.settingsStore.cachedNoticesJson.collectAsStateWithLifecycle(initialValue = "")
                val readNoticeIds by container.settingsStore.readNoticeIds.collectAsStateWithLifecycle(initialValue = emptySet())
                val dismissedNoticePopupIds by container.settingsStore.dismissedNoticePopupIds.collectAsStateWithLifecycle(initialValue = emptySet())
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
                    gesturesEnabled = !drawerGestureBlocked,
                    drawerContent = {
                        ModalDrawerSheet(
                            modifier = Modifier.fillMaxWidth(0.75f),
                            drawerContainerColor = Color(0xFFE8E4D6),
                            drawerContentColor = Color(0xFF141821)
                        ) {
                            DrawerHeader()
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
items(listOf(DrawerItem.Schedule, DrawerItem.Exam, DrawerItem.StudyPlan, DrawerItem.SemesterOverview, DrawerItem.Import)) { item ->
                                        DrawerMenuItem(
                                            item = item,
                                            isSelected = selectedItem == item,
                                            showDot = item == DrawerItem.About && showUpdateDot,
                                            onClick = {
                                                selectedItem = item
                                                settingsSubPage = SettingsSubPage.ROOT
                                                scope.launch { drawerState.close() }
                                            }
                                        )
                                    }
                                    // 成绩
                                    item {
                                        Text(
                                            "成绩",
                                            color = Color(0xFF3F7DF6),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp)
                                        )
                                    }
                                    items(listOf(DrawerItem.Score, DrawerItem.ProfessionalScore, DrawerItem.GradeExam, DrawerItem.FitnessScore)) { item ->
                                        DrawerMenuItem(
                                            item = item,
                                            isSelected = selectedItem == item,
                                            onClick = {
                                                selectedItem = prepareDrawerSelection(
                                                    current = selectedItem,
                                                    target = item,
                                                    onProfessionalScoreEntered = professionalScoreViewModel::resetAcademicYearSelection
                                                )
                                                settingsSubPage = SettingsSubPage.ROOT
                                                scope.launch { drawerState.close() }
                                            }
                                        )
                                    }
                                    // 校园
                                    item {
                                        Text(
                                            "校园",
                                            color = Color(0xFF3F7DF6),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp)
                                        )
                                    }
                                    items(campusDrawerItems(campusType)) { item ->
                                        DrawerMenuItem(
                                            item = item,
                                            isSelected = selectedItem == item,
                                            onClick = {
                                                selectedItem = item
                                                settingsSubPage = SettingsSubPage.ROOT
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
                                    items(otherDrawerItems) { item ->
                                        DrawerMenuItem(
                                            item = item,
                                            isSelected = selectedItem == item,
                                            showDot = (item == DrawerItem.About && showUpdateDot)
                                                || (item == DrawerItem.Notice && showNoticeDot),
                                            onClick = {
                                                selectedItem = item
                                                settingsSubPage = SettingsSubPage.ROOT
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
                                            if (selectedItem == DrawerItem.Settings) settingsSubPage.title else selectedItem.title,
                                            color = Color(0xFF141821),
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    },
                                    navigationIcon = {
                                        IconButton(onClick = {
                                            if (settingsSubPage != SettingsSubPage.ROOT) settingsSubPage = SettingsSubPage.ROOT
                                            else scope.launch { drawerState.open() }
                                        }) {
                                            Icon(
                                                imageVector = if (settingsSubPage != SettingsSubPage.ROOT) Icons.AutoMirrored.Outlined.ArrowBack else Icons.Outlined.Menu,
                                                contentDescription = if (settingsSubPage != SettingsSubPage.ROOT) "返回" else "菜单",
                                                tint = Color(0xFF141821),
                                                modifier = Modifier.size(26.dp)
                                            )
                                        }
                                    },
                                    actions = {
                                        when (selectedItem) {
                                            DrawerItem.Exam -> {
                                                val examState by examViewModel.uiState.collectAsStateWithLifecycle()
                                                IconButton(
                                                    onClick = examViewModel::refreshExams,
                                                    enabled = !examState.isRefreshing
                                                ) {
                                                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                                                }
                                            }
                                            DrawerItem.Score -> {
                                                val scoreState by scoreViewModel.uiState.collectAsStateWithLifecycle()
                                                IconButton(
                                                    onClick = scoreViewModel::refreshScores,
                                                    enabled = !scoreState.isRefreshing
                                                ) {
                                                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                                                }
                                            }
                                            DrawerItem.ProfessionalScore -> {
                                                val professionalScoreState by professionalScoreViewModel.uiState.collectAsStateWithLifecycle()
                                                IconButton(
                                                    onClick = professionalScoreViewModel::refreshData,
                                                    enabled = !professionalScoreState.isRefreshing
                                                ) {
                                                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                                                }
                                            }
                                            DrawerItem.GradeExam -> {
                                                val gradeExamState by gradeExamViewModel.uiState.collectAsStateWithLifecycle()
                                                IconButton(
                                                    onClick = gradeExamViewModel::refresh,
                                                    enabled = !gradeExamState.isRefreshing
                                                ) {
                                                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                                                }
                                            }
                                            DrawerItem.FitnessScore -> {
                                                val fitnessState by fitnessScoreViewModel.uiState.collectAsStateWithLifecycle()
                                                IconButton(
                                                    onClick = fitnessScoreViewModel::refresh,
                                                    enabled = !fitnessState.isRefreshing
                                                ) {
                                                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                                                }
                                            }
                                            DrawerItem.CampusInfo -> campusInfoViewModel?.let { viewModel ->
                                                val campusImageState by viewModel.uiState.collectAsStateWithLifecycle()
                                                if (campusImageState.selectedType.isRemote) {
                                                    IconButton(
                                                        onClick = viewModel::refreshCurrent,
                                                        enabled = !campusImageState.isLoading
                                                    ) {
                                                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新校园信息")
                                                    }
                                                }
                                            }
                                            DrawerItem.Finance -> {
                                                financeViewModel?.let { viewModel ->
                                                    val financeState by viewModel.uiState.collectAsStateWithLifecycle()
                                                    IconButton(onClick = viewModel::toggleMoneyVisibility) {
                                                        Icon(
                                                            if (financeState.moneyVisible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                                                            contentDescription = if (financeState.moneyVisible) "隐藏金额" else "显示金额"
                                                        )
                                                    }
                                                    IconButton(
                                                        onClick = viewModel::refresh,
                                                        enabled = !financeState.isRefreshing && !financeState.campusUnsupported
                                                    ) {
                                                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                                                    }
                                                }
                                            }
                                            DrawerItem.StudyPlan -> {
                                                val studyPlanState by studyPlanViewModel.uiState.collectAsStateWithLifecycle()
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
                                DrawerItem.Schedule -> ScheduleDestination(
                                    viewModel = scheduleViewModel,
                                    backgroundStore = container.backgroundStore,
                                    onImportClick = { selectedItem = DrawerItem.Import },
                                    onExamClick = { selectedItem = DrawerItem.Exam },
                                    onDrawerOpen = { scope.launch { drawerState.open() } }
                                )
                                DrawerItem.Score -> ScoreScreen(viewModel = scoreViewModel)
                                DrawerItem.ProfessionalScore -> ProfessionalScoreScreen(viewModel = professionalScoreViewModel)
                                DrawerItem.GradeExam -> GradeExamScreen(viewModel = gradeExamViewModel)
                                DrawerItem.FitnessScore -> FitnessScoreScreen(
                                    viewModel = fitnessScoreViewModel,
                                    onTableGestureActive = { drawerGestureBlocked = it }
                                )
                                DrawerItem.CampusInfo -> campusInfoViewModel?.let {
                                    CampusImageScreen(
                                        viewModel = it,
                                        onImageGestureActive = { active -> drawerGestureBlocked = active }
                                    )
                                }
                                DrawerItem.Finance -> financeViewModel?.let {
                                    FinanceScreen(
                                        viewModel = it,
                                        onTableGestureActive = { drawerGestureBlocked = it }
                                    )
                                }
                                DrawerItem.StudyPlan -> StudyPlanScreen(viewModel = studyPlanViewModel)
                                DrawerItem.Exam -> ExamScreen(
                                    viewModel = examViewModel,
                                    onBack = { selectedItem = DrawerItem.Schedule }
                                )
                                DrawerItem.Import -> DirectLoginScreen(viewModel = directLoginViewModel)
                                DrawerItem.Settings -> ScheduleSettingsDestination(
                                    viewModel = scheduleViewModel,
                                    subPage = settingsSubPage,
                                    onPickBackground = { backgroundPicker.launch(arrayOf("image/*")) },
                                    onSubPageChange = { settingsSubPage = it },
                                    onReset = { showResetConfirm = true }
                                )
                                DrawerItem.Notice -> NoticeScreen(notices = notices)
                                DrawerItem.SemesterOverview -> SemesterOverviewScreen(viewModel = semesterOverviewViewModel)
                                DrawerItem.FAQ -> FaqScreen()
                                DrawerItem.About -> {
                                    val shareCtx = LocalContext.current
                                    AboutScreen(
                                        updateChecker = container.updateChecker,
                                        updateAvailableVersion = updateAvailableVersion,
                                        onShowUpdateDialog = { info ->
                                            showUpdateDialog = UpdateDialogState.Idle(info)
                                        },
                                        onShare = { shareApk(shareCtx) }
                                    )
                                }
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
                                    fitnessScoreViewModel.clearData()
                                    financeViewModels.clearAll()
                                    container.financeStore.clearAll()
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
private fun ScheduleDestination(
    viewModel: ScheduleViewModel,
    backgroundStore: ScheduleBackgroundStore,
    onImportClick: () -> Unit,
    onExamClick: () -> Unit,
    onDrawerOpen: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val backgroundBitmap = if (uiState.customBackgroundUri.isNotBlank()) {
        backgroundStore.get(uiState.customBackgroundUri)
    } else {
        null
    }
    ScheduleScreen(
        viewModel = viewModel,
        uiState = uiState,
        backgroundStore = backgroundStore,
        customBackgroundBitmap = backgroundBitmap,
        onImportClick = onImportClick,
        onExamClick = onExamClick,
        onDrawerOpen = onDrawerOpen
    )
}

@Composable
private fun ScheduleSettingsDestination(
    viewModel: ScheduleViewModel,
    subPage: SettingsSubPage,
    onPickBackground: () -> Unit,
    onSubPageChange: (SettingsSubPage) -> Unit,
    onReset: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    when (subPage) {
        SettingsSubPage.COURSE_COLORS -> CourseColorsPage(
                courses = uiState.courses,
                overrides = uiState.courseColorOverrides,
                onSetColor = viewModel::setCourseColorOverride,
                onRemoveColor = viewModel::removeCourseColorOverride,
                onResetAll = viewModel::clearCourseColorOverrides
            )
        SettingsSubPage.CLASS_PERIODS -> ClassPeriodSettingsScreen(
            campusType = uiState.campusType,
            periods = uiState.classPeriods,
            guilinSubCampus = uiState.guilinSubCampus,
            onSetPeriods = viewModel::setClassPeriods,
            onResetPeriods = viewModel::resetClassPeriods,
            onSetGuilinSubCampus = viewModel::setGuilinSubCampus,
            onSaved = { onSubPageChange(SettingsSubPage.ROOT) }
        )
        SettingsSubPage.BUILT_IN_BACKGROUNDS -> BuiltInBackgroundsPage(
            selectedBackground = BuiltInScheduleBackground.fromStorageValue(uiState.customBackgroundUri)
                ?: if (uiState.customBackgroundUri.isBlank()) BuiltInScheduleBackground.STARRY else null,
            onSelectBackground = { background ->
                // 内置背景使用稳定标识持久化，默认星空以空值兼容旧设置。
                viewModel.setCustomBackgroundUri(background.storageValue)
                onSubPageChange(SettingsSubPage.ROOT)
            }
        )
        SettingsSubPage.ROOT -> SettingsPage(
                showWeekend = uiState.showWeekend,
                onShowWeekendChange = viewModel::setShowWeekend,
                showNoon = uiState.showNoon,
                onShowNoonChange = viewModel::setShowNoon,
                hasCustomBackground = uiState.customBackgroundUri.isNotBlank(),
                onPickBackground = onPickBackground,
                onClearBackground = viewModel::clearCustomBackground,
                onBuiltInBackgrounds = { onSubPageChange(SettingsSubPage.BUILT_IN_BACKGROUNDS) },
                onCourseColors = { onSubPageChange(SettingsSubPage.COURSE_COLORS) },
                onClassPeriods = { onSubPageChange(SettingsSubPage.CLASS_PERIODS) },
                onReset = onReset
            )
    }
}

@Composable
private fun DrawerHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.brand_menu_logo),
                    contentDescription = "桂系一站式标志",
                    modifier = Modifier.size(38.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("桂系一站式", color = Color(0xFF141821), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("简单 高效 纯粹", color = Color(0xFF667085), fontSize = 13.sp)
                }
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
    onBuiltInBackgrounds: () -> Unit = {},
    onCourseColors: () -> Unit = {},
    onClassPeriods: () -> Unit = {},
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
            .verticalScroll(rememberScrollState())
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

            Text("课表外观", color = settingsSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = settingsCardBg,
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onCourseColors)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("课程卡片颜色", color = settingsPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = settingsSecondary, modifier = Modifier.size(20.dp))
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = settingsCardBg,
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onClassPeriods)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("上课时间", color = settingsPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = settingsSecondary, modifier = Modifier.size(20.dp))
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
                            .clickable(onClick = onBuiltInBackgrounds)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("内置背景", color = settingsPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Icon(
                            Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            tint = settingsSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    HorizontalDivider(color = Color(0xFFEDE8DE))
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

@Composable
private fun BuiltInBackgroundsPage(
    selectedBackground: BuiltInScheduleBackground?,
    onSelectBackground: (BuiltInScheduleBackground) -> Unit
) {
    val settingsBg = Color(0xFFF6F4EF)
    val settingsPrimary = Color(0xFF141821)
    val settingsSecondary = Color(0xFF667085)
    val settingsCardBg = Color(0xFFFFFEFB)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(settingsBg)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        BuiltInBackgroundsCard(
            background = BuiltInScheduleBackground.STARRY,
            selected = selectedBackground == BuiltInScheduleBackground.STARRY,
            onClick = { onSelectBackground(BuiltInScheduleBackground.STARRY) },
            cardColor = settingsCardBg,
            textColor = settingsPrimary
        )
        BuiltInBackgroundsCard(
            background = BuiltInScheduleBackground.FLOWER,
            selected = selectedBackground == BuiltInScheduleBackground.FLOWER,
            onClick = { onSelectBackground(BuiltInScheduleBackground.FLOWER) },
            cardColor = settingsCardBg,
            textColor = settingsPrimary
        )
    }
}

@Composable
private fun BuiltInBackgroundsCard(
    background: BuiltInScheduleBackground,
    selected: Boolean,
    onClick: () -> Unit,
    cardColor: Color,
    textColor: Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = cardColor,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = if (selected) 2.dp else 0.dp
    ) {
        Column(modifier = Modifier.clickable(onClick = onClick)) {
            Image(
                painter = painterResource(background.drawableRes),
                contentDescription = "${background.displayName}背景预览",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                contentScale = ContentScale.Crop
            )
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(background.displayName, color = textColor, fontSize = 16.sp, modifier = Modifier.weight(1f))
                if (selected) {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = "已选择",
                        tint = Color(0xFF2563EB),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CourseColorsPage(
    courses: List<ScheduleCourse>,
    overrides: Map<String, String>,
    onSetColor: (String, String) -> Unit,
    onRemoveColor: (String) -> Unit,
    onResetAll: () -> Unit
) {
    val background = Color(0xFFF6F4EF)
    val card = Color(0xFFFFFEFB)
    val entries = remember(courses, overrides) {
        courses.distinctBy { CourseColorMapper.colorKey(it.id, it.title) }
    }
    var selectedCourse by remember { mutableStateOf<ScheduleCourse?>(null) }
    var showAdvanced by remember { mutableStateOf(false) }

    if (entries.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().background(background), contentAlignment = Alignment.Center) {
            Text("暂未导入课表", color = Color(0xFF667085), fontSize = 15.sp)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("已导入课程", color = Color(0xFF667085), fontSize = 13.sp, fontWeight = FontWeight.Medium) }
        items(entries, key = { it.id }) { course ->
            val key = CourseColorMapper.colorKey(course.id, course.title)
            Surface(
                modifier = Modifier.fillMaxWidth().clickable {
                    selectedCourse = course
                    showAdvanced = false
                },
                color = card,
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.size(24.dp),
                        color = Color(AndroidColor.parseColor(course.colorHex)),
                        shape = RoundedCornerShape(8.dp)
                    ) {}
                    Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(course.title, color = Color(0xFF141821), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (overrides.containsKey(key)) "已自定义" else "自动配色", color = Color(0xFF98A2B3), fontSize = 12.sp)
                    }
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = Color(0xFF98A2B3), modifier = Modifier.size(20.dp))
                }
            }
        }
        item {
            TextButton(modifier = Modifier.fillMaxWidth(), onClick = onResetAll) {
                Text("恢复全部默认颜色", color = Color(0xFFDC2626))
            }
        }
    }

    selectedCourse?.let { course ->
        if (showAdvanced) {
            AdvancedColorSheet(
                initialColor = course.colorHex,
                onDismiss = {
                    showAdvanced = false
                    selectedCourse = null
                },
                onConfirm = { color ->
                    onSetColor(CourseColorMapper.colorKey(course.id, course.title), color)
                    selectedCourse = null
                }
            )
        } else {
            PresetColorSheet(
                course = course,
                onDismiss = { selectedCourse = null },
                onSelect = { color ->
                    onSetColor(CourseColorMapper.colorKey(course.id, course.title), color)
                    selectedCourse = null
                },
                onAdvanced = { showAdvanced = true },
                onRestore = {
                    onRemoveColor(CourseColorMapper.colorKey(course.id, course.title))
                    selectedCourse = null
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetColorSheet(
    course: ScheduleCourse,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onAdvanced: () -> Unit,
    onRestore: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFFFFFEFB),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Column {
                Text(course.title, color = Color(0xFF141821), fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("选择课程卡片颜色", color = Color(0xFF667085), fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            }
            CourseColorMapper.presetPalette.chunked(5).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    row.forEach { color ->
                        Surface(
                            modifier = Modifier.size(52.dp).clickable { onSelect(color) },
                            color = Color(AndroidColor.parseColor(color)),
                            shape = RoundedCornerShape(16.dp)
                        ) {}
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onRestore) { Text("恢复自动配色", color = Color(0xFF667085), fontSize = 15.sp) }
                TextButton(onClick = onAdvanced) { Text("高级调色", color = Color(0xFF3F7DF6), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedColorSheet(
    initialColor: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val initialHsv = remember(initialColor) { hexToHsv(initialColor) }
    var hue by remember(initialColor) { mutableStateOf(initialHsv[0]) }
    var saturation by remember(initialColor) { mutableStateOf(initialHsv[1]) }
    var value by remember(initialColor) { mutableStateOf(initialHsv[2]) }
    var hexField by remember(initialColor) { mutableStateOf(TextFieldValue(initialColor, TextRange(0))) }

    fun updateFromHsv(nextHue: Float = hue, nextSaturation: Float = saturation, nextValue: Float = value) {
        hue = nextHue
        saturation = nextSaturation
        value = nextValue
        val nextHex = hsvToHex(hue, saturation, value)
        hexField = TextFieldValue(nextHex, TextRange(nextHex.length))
    }

    var planeSize by remember { mutableStateOf(IntSize.Zero) }
    var hueTrackSize by remember { mutableStateOf(IntSize.Zero) }
    val normalizedColor = CourseColorMapper.normalizeHexColor(hexField.text)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scrollState = rememberScrollState()
    val hexBringIntoViewRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val planeThumbSizePx = with(density) { 20.dp.toPx() }
    val hueThumbSizePx = with(density) { 22.dp.toPx() }

    fun updatePlane(position: Offset) {
        if (planeSize.width == 0 || planeSize.height == 0) return
        updateFromHsv(
            nextSaturation = (position.x / planeSize.width).coerceIn(0f, 1f),
            nextValue = (1f - position.y / planeSize.height).coerceIn(0f, 1f)
        )
    }

    fun updateHue(position: Offset) {
        if (hueTrackSize.width == 0) return
        updateFromHsv(nextHue = (position.x / hueTrackSize.width * 360f).coerceIn(0f, 360f))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFFFFFEFB),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .verticalScroll(scrollState)
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("高级调色", color = Color(0xFF141821), fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("嵌入式系统", color = Color(0xFF667085), fontSize = 13.sp)
            }
            Surface(
                modifier = Modifier.fillMaxWidth().height(52.dp),
                color = Color(AndroidColor.parseColor(normalizedColor ?: "#3B82F6")),
                shape = RoundedCornerShape(16.dp)
            ) {}
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(248.dp)
                    .background(
                        Brush.horizontalGradient(listOf(Color.White, Color(AndroidColor.parseColor(hsvToHex(hue, 1f, 1f))))),
                        RoundedCornerShape(16.dp)
                    )
                    .onSizeChanged { planeSize = it }
                    .pointerInput(planeSize, hue) {
                        detectDragGestures(
                            onDragStart = ::updatePlane,
                            onDrag = { change, _ -> updatePlane(change.position) }
                        )
                    }
            ) {
                Box(modifier = Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)), RoundedCornerShape(16.dp)))
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (saturation * maxOf(0f, planeSize.width - planeThumbSizePx)).toInt(),
                                ((1f - value) * maxOf(0f, planeSize.height - planeThumbSizePx)).toInt()
                            )
                        }
                        .size(20.dp)
                        .border(2.dp, Color.White, CircleShape)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("色相", color = Color(0xFF344054), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("拖动切换基础颜色", color = Color(0xFF98A2B3), fontSize = 13.sp)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .background(
                        Brush.horizontalGradient(listOf(
                            Color(0xFFF04438), Color(0xFFF79009), Color(0xFFFDE272),
                            Color(0xFF12B76A), Color(0xFF2E90FA), Color(0xFF9E77ED),
                            Color(0xFFEE46BC), Color(0xFFF04438)
                        )),
                        RoundedCornerShape(14.dp)
                    )
                    .onSizeChanged { hueTrackSize = it }
                    .pointerInput(hueTrackSize) {
                        detectDragGestures(
                            onDragStart = ::updateHue,
                            onDrag = { change, _ -> updateHue(change.position) }
                        )
                    }
            ) {
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (hue / 360f * maxOf(0f, hueTrackSize.width - hueThumbSizePx)).toInt(),
                                ((hueTrackSize.height - hueThumbSizePx) / 2f).toInt()
                            )
                        }
                        .size(22.dp)
                        .background(Color.White, CircleShape)
                        .border(2.dp, Color(0xFF667085), CircleShape)
                )
            }
            OutlinedTextField(
                value = hexField,
                onValueChange = { input ->
                    hexField = input
                    CourseColorMapper.normalizeHexColor(input.text)?.let { color ->
                        val hsv = hexToHsv(color)
                        hue = hsv[0]
                        saturation = hsv[1]
                        value = hsv[2]
                    }
                },
                label = { Text("HEX 颜色") },
                placeholder = { Text("#154173") },
                singleLine = true,
                isError = hexField.text.isNotBlank() && normalizedColor == null,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color(0xFF141821)),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color(0xFF141821),
                    unfocusedTextColor = Color(0xFF141821),
                    focusedBorderColor = Color(0xFF3F7DF6),
                    unfocusedBorderColor = Color(0xFF98A2B3),
                    cursorColor = Color(0xFF3F7DF6)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(hexBringIntoViewRequester)
                    .onFocusEvent { focusState ->
                        if (focusState.isFocused) scope.launch { hexBringIntoViewRequester.bringIntoView() }
                    }
            )
            Text("支持 #RRGGBB 或 RRGGBB；每组十六进制取值为 00 到 FF。", color = Color(0xFF667085), fontSize = 12.sp)
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(enabled = normalizedColor != null, onClick = { normalizedColor?.let(onConfirm) }) { Text("完成") }
            }
        }
    }
}

private fun hexToHsv(hex: String): FloatArray {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(AndroidColor.parseColor(CourseColorMapper.normalizeHexColor(hex) ?: "#3B82F6"), hsv)
    return hsv
}

private fun hsvToHex(hue: Float, saturation: Float, value: Float): String {
    return "#%06X".format(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value)) and 0xFFFFFF)
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
        properties = DialogProperties(dismissOnClickOutside = false),
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
                properties = DialogProperties(dismissOnClickOutside = false),
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
                properties = DialogProperties(dismissOnClickOutside = false),
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
                properties = DialogProperties(dismissOnClickOutside = false),
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
                properties = DialogProperties(dismissOnClickOutside = false),
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

/** 复制已安装的 APK 到缓存目录并通过系统分享面板发送给 QQ/微信等 */
private fun shareApk(context: Context) {
    try {
        val src = File(context.packageCodePath)
        // 清理缓存目录中所有旧版本分享 APK，避免文件名冲突产生后缀
        context.cacheDir.listFiles()?.filter {
            it.name.startsWith("桂系一站式_v") && it.name.endsWith(".apk")
        }?.forEach { it.delete() }
        val dest = File(context.cacheDir, "桂系一站式_v${BuildConfig.VERSION_NAME}.apk")
        src.copyTo(dest, overwrite = true)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            dest
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(
                Intent.EXTRA_TEXT,
                "桂系一站式 — 查课表、成绩、考试安排超方便！"
            )
        }
        // 使用 createChooser 强制每次都弹出选择器，避免用户选择"始终"后锁定单一平台
        context.startActivity(Intent.createChooser(shareIntent, "分享到"))
    } catch (_: Exception) {
        // 分享失败静默忽略（如 FileProvider 配置异常等边缘情况）
    }
}
