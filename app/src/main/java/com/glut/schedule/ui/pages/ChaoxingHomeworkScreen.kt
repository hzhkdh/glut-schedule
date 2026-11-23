package com.glut.schedule.ui.pages

import android.Manifest
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.ui.Alignment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.glut.schedule.service.chaoxing.*
import com.glut.schedule.ui.requirements.HomeworkDocumentActions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import com.glut.schedule.service.refresh.ScheduledRefreshStore
import com.glut.schedule.service.refresh.ScheduledRefreshRunState
import com.glut.schedule.service.refresh.RefreshTaskId
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val DEFAULT_TASK_STATUS = "todo"
private const val DEFAULT_TASK_KIND = "homework"

@Composable
fun ChaoxingHomeworkScreen() {
    // 当前 App 的全局主题是深色；学习通独立使用浅色方案，覆盖弹窗和菜单的默认颜色。
    MaterialTheme(colorScheme = lightColorScheme(
        primary = Color(0xFF3F7DF6), onPrimary = Color.White,
        background = Color(0xFFF6F4EF), surface = Color(0xFFFFFEFB),
        onSurface = Color(0xFF141821), onBackground = Color(0xFF141821),
        onSurfaceVariant = Color(0xFF667085), outline = Color(0xFFE4E2DD)
    )) { ChaoxingHomeworkContent() }
}

@Composable
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
private fun ChaoxingHomeworkContent() {
    val context = LocalContext.current
    val store = remember { HomeworkStore(context) }
    val scheduledStore = remember { ScheduledRefreshStore(context) }
    var login by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf(store.read()) }
    var updated by remember { mutableLongStateOf(store.updatedAt) }
    var failures by remember { mutableIntStateOf(store.failureCount) }
    var failureSummary by remember { mutableStateOf(store.failureSummary) }
    var issues by remember { mutableStateOf(store.issues()) }
    var catalog by remember { mutableStateOf(store.catalog()) }
    var selectedCourses by remember { mutableStateOf(store.selectedCourseKeys) }
    var panel by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(store.lastError) }
    var syncing by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var loginError by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    var selectedStatus by rememberSaveable { mutableStateOf(DEFAULT_TASK_STATUS) }
    var detailRevision by remember { mutableLongStateOf(store.detailRevision) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var completedLogin by remember { mutableStateOf(false) }
    var selectedKind by rememberSaveable { mutableStateOf(DEFAULT_TASK_KIND) }
    val scopedRows = rows.filter { selectedCourses?.contains(it.subjectKey()) ?: true }
    val visibleRows = filterHomework(scopedRows, selectedStatus, "", selectedKind)
    BackHandler(enabled = login) { login = false }
    LaunchedEffect(login) { if (login) completedLogin = false }
    val scope = rememberCoroutineScope()
    val relogin: () -> Unit = {
        menuExpanded = false
        panel = ""
        scope.launch {
            disconnectChaoxing(context)
            rows = emptyList(); catalog = emptyList(); selectedCourses = null
            updated = 0; failures = 0; issues = emptyList(); error = ""
            loginError = ""; login = true
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // 进入和恢复页面只读取缓存；整表同步仅由每日计划、手动操作或首次登录触发。
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            detailRevision = store.detailRevision
            rows = store.read(); updated = store.updatedAt; failures = store.failureCount; error = store.lastError
            failureSummary = store.failureSummary
            issues = store.issues()
            catalog = store.catalog()
            selectedCourses = store.selectedCourseKeys
            syncing = withContext(Dispatchers.IO) {
                val manager = WorkManager.getInstance(context)
                val manualRunning = listOf("chaoxing_manual", "chaoxing_sync").any { name ->
                    runCatching { manager.getWorkInfosForUniqueWork(name).get()
                        .any { it.state == WorkInfo.State.RUNNING } }.getOrDefault(false)
                }
                val scopeRunning = runCatching { manager.getWorkInfosByTag(CHAOXING_SCOPE_SYNC_TAG).get()
                    .any { it.state == WorkInfo.State.RUNNING } }.getOrDefault(false)
                // 统一计划可能只刷新教务，只有实际执行学习通项目时才显示列表同步状态。
                manualRunning || scopeRunning || scheduledStore.config.first().runs.any {
                    it.state == ScheduledRefreshRunState.RUNNING && it.activeTask == RefreshTaskId.CHAOXING
                }
            }
            delay(2000)
        }
    }
    if (login) {
        Column(Modifier.fillMaxSize()) {
            if (loginError.isNotEmpty()) Text(loginError, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
            AndroidView(factory = { ctx -> WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        val uri = android.net.Uri.parse(url)
                        // 仅隐藏已确认的客服入口，CSS 对之后动态插入的同名节点也生效。
                        if (uri.host == "passport2.chaoxing.com") view.evaluateJavascript(
                            "(()=>{if(!document.getElementById('app-hide-support')){const s=document.createElement('style');s.id='app-hide-support';s.textContent='#kf{display:none!important}';document.head.appendChild(s)}})()", null)
                        if (uri.host == "i.mooc.chaoxing.com" && uri.path == "/space/index" && !checking && !completedLogin) {
                            checking = true
                            CookieManager.getInstance().flush()
                            scope.launch {
                                val valid = withContext(Dispatchers.IO) { runCatching { ChaoxingClient().verifyLogin() }.getOrDefault(false) }
                                checking = false
                                if (!login || completedLogin) return@launch
                                if (valid) {
                                    completedLogin = true; login = false; loginError = ""; store.markConnected()
                                    // 登录成功才重置当前筛选，取消登录时保留原选择。
                                    selectedStatus = DEFAULT_TASK_STATUS; selectedKind = DEFAULT_TASK_KIND
                                    if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    scheduleHomeworkSync(context); refreshHomework(context)
                                } else loginError = "未能确认登录状态，请检查网络后重新登录"
                            }
                        }
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val uri = request.url
                        // 学习通登录成功后可能回跳明文地址，限定可信域名升级为 HTTPS。
                        if (request.isForMainFrame && uri.scheme == "http" &&
                            (uri.host == "chaoxing.com" || uri.host?.endsWith(".chaoxing.com") == true)) {
                            view.loadUrl(uri.buildUpon().scheme("https").build().toString())
                            return true
                        }
                        return false
                    }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) loginError = "页面加载失败，请检查网络后重新进入登录"
                    }
                }
                // 直接打开官方登录页，并让登录后的回跳地址保持 HTTPS。
                loadUrl("https://passport2.chaoxing.com/login?fid=&refer=https%3A%2F%2Fi.mooc.chaoxing.com%2Fspace%2Findex%3Fws%3D1")
            } }, modifier = Modifier.weight(1f).fillMaxWidth())
        }
    } else if (!store.connected && updated == 0L && rows.isEmpty() && !syncing) {
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), contentAlignment = Alignment.Center) {
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFEFB)), border = BorderStroke(1.dp, Color(0xFFE4E2DD))) {
                Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(72.dp).background(Color(0xFFEBF2FF), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                    }
                    Spacer(Modifier.height(20.dp))
                    Text("连接学习通", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text("登录后查看作业、讨论和考试", style = MaterialTheme.typography.bodyMedium)
                    if (error.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { loginError = ""; login = true }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("登录学习通") }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            // 单行常驻工具栏，长文案在弹层内查看，列表不为告警让出多行空间。
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val density = LocalDensity.current
                val measurer = rememberTextMeasurer()
                val buttonStyle = MaterialTheme.typography.labelLarge
                val countStyle = MaterialTheme.typography.labelSmall
                fun textWidth(label: String, style: androidx.compose.ui.text.TextStyle) =
                    with(density) { measurer.measure(label, style, maxLines = 1, softWrap = false).size.width.toDp() }
                val filterLabel = chaoxingStatusLabel(selectedStatus) + " · " + chaoxingKindLabel(selectedKind)
                val status = when {
                    syncing -> "同步中"
                    failures > 0 -> "部分更新"
                    error.isNotBlank() -> "同步提示"
                    updated > 0 -> "已更新"
                    else -> "待同步"
                }
                val statusColor = if (!syncing && (failures > 0 || error.isNotBlank())) Color(0xFF925311) else MaterialTheme.colorScheme.primary
                val fullLabel = "筛选，$filterLabel"
                val statusDescription = if (failures > 0 && !syncing) "$failures 门未更新" else status
                val count = visibleRows.size.toString()+" 项"
                val countWidth = textWidth(count, countStyle) + 8.dp
                val statusWidth = (textWidth(status, buttonStyle) + 8.dp).coerceAtLeast(48.dp)
                // 先给刷新和更多保留触控区。大字体下同步状态可以改用带说明的图标。
                val actionWidth = 48.dp
                val filterExtras = 32.dp
                val filterMinWidth = (textWidth("筛选", buttonStyle) + filterExtras).coerceAtLeast(actionWidth)
                val showStatusText = maxWidth >= actionWidth * 2 + countWidth + filterMinWidth + statusWidth
                val filterWidth = maxWidth - actionWidth * 2 - countWidth - (if (showStatusText) statusWidth else actionWidth)
                val filterText = if (textWidth(filterLabel, buttonStyle) + filterExtras <= filterWidth) filterLabel else "筛选"
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { panel = "filter" }, modifier = Modifier
                        .width((textWidth(filterText, buttonStyle) + filterExtras).coerceAtMost(filterWidth.coerceAtLeast(0.dp))).semantics {
                        contentDescription = fullLabel
                    }, shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.textButtonColors(containerColor = Color(0xFFE8EDF8), contentColor = Color(0xFF284477)),
                        contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Icon(Icons.Outlined.Tune, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(filterText, maxLines = 1)
                    }
                    Text(count, style = countStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp))
                    Spacer(Modifier.weight(1f))
                    if (showStatusText) TextButton(onClick = { panel = "status" }, modifier = Modifier.width(statusWidth).semantics {
                        contentDescription = "同步详情 · $statusDescription"
                    },
                        contentPadding = PaddingValues(horizontal = 2.dp)) {
                        Text(status, maxLines = 1, color = statusColor)
                    } else IconButton(onClick = { panel = "status" }, modifier = Modifier.width(actionWidth)) {
                        Icon(Icons.Outlined.Info, "同步详情 · $statusDescription", tint = statusColor)
                    }
                    IconButton(onClick = {
                        if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        store.requestDetailRefresh()
                        refreshHomework(context)
                    }, enabled = !syncing && selectedCourses?.isEmpty() != true) {
                        if (syncing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.Refresh, "刷新任务和详情", tint = MaterialTheme.colorScheme.primary)
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Outlined.MoreVert, "更多操作") }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(text = { Text("重新登录") }, onClick = relogin)
                            DropdownMenuItem(text = { Text("退出并清除任务") }, onClick = { menuExpanded = false; confirmDisconnect = true })
                        }
                    }
                }
            }
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            if (visibleRows.isEmpty()) Text(when {
                selectedCourses?.isEmpty() == true -> "请选择要同步的科目"
                scopedRows.isEmpty() -> "暂无已同步的任务"
                else -> "没有符合条件的任务，请调整筛选"
            }, modifier = Modifier.padding(12.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(visibleRows.sortedWith(compareBy<CachedHomework> { homeworkState(it.status) != HomeworkState.TODO }.thenBy { it.deadline ?: Long.MAX_VALUE }), key = { it.taskKey() }) { row ->
                    // 仅未完成作业提供外部入口；跳转状态按任务保存，不触发同步或改变筛选。
                    val canOpenHomework = row.kind == "homework" && homeworkState(row.status) == HomeworkState.TODO
                    var openingHomework by remember(row.taskKey()) { mutableStateOf(false) }
                    var attemptedOpen by remember(row.taskKey()) { mutableStateOf(false) }
                    var jumpError by remember(row.taskKey()) { mutableStateOf<String?>(null) }
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFEFB)),
                        shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Color(0xFFE4E2DD))) {
                        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 6.dp)) {
                            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(row.title, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                // 状态色只承担提示作用，保留原始状态文字避免误解。
                                val badgeBg = when(row.status) { "未交", "未讨论" -> Color(0xFFFFEAEA); "已完成", "已批阅", "已讨论" -> Color(0xFFE8F5E9); "待批阅" -> Color(0xFFEAF1FF); "待互评" -> Color(0xFFFFF1DD); else -> Color(0xFFF0F0ED) }
                                val badgeFg = when(row.status) { "未交", "未讨论" -> Color(0xFFB3261E); "已完成", "已批阅", "已讨论" -> Color(0xFF26713A); "待批阅" -> Color(0xFF3264BB); "待互评" -> Color(0xFF99520A); else -> Color(0xFF667085) }
                                Text(row.status.ifBlank { "待确认" }, style = MaterialTheme.typography.labelMedium, color = badgeFg,
                                    modifier = Modifier.background(badgeBg, RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 3.dp))
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("${row.kindLabel()} · ${row.courseName}", fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            // 截止时间和倒计时优先同排，放大系统字体后仍可按组换行。
                            FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Icon(Icons.Outlined.Event, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(row.deadline?.let { "截止 ${SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(it))}" } ?: "暂无截止时间",
                                        fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                homeworkCountdown(row.deadline, row.status, now)?.let { countdown ->
                                    val fg = when (countdown.tone) {
                                        "urgent" -> Color(0xFFB3261E)
                                        "warning" -> Color(0xFF925311)
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    val label = if (countdown.label.isNotEmpty()) "${countdown.label} · ${countdown.text}" else countdown.text
                                    Text(label, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp)
                                }
                            }
                            HomeworkDetailContent(row, store, detailRevision) {
                                if (canOpenHomework) {
                                    TextButton(enabled = !openingHomework, onClick = {
                                        openingHomework = true
                                        attemptedOpen = true
                                        jumpError = null
                                        val sessionGeneration = store.generation
                                        scope.launch {
                                            try {
                                                jumpError = openChaoxingHomework(context, row, store, sessionGeneration)
                                            } finally {
                                                openingHomework = false
                                            }
                                        }
                                    }) { Text(if (openingHomework) "正在打开…" else "去学习通完成 ↗") }
                                    if (attemptedOpen && !openingHomework) {
                                        Text("使用学习通当前登录账号打开", style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        jumpError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                                        // 系统受理 Intent 无法确认最终页面，保留作业列表备用入口。
                                        TextButton(onClick = { jumpError = openChaoxingHomeworkList(context) }) {
                                            Text(if (jumpError == null) "未能打开？前往作业列表" else "打开学习通作业列表 ↗")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (panel == "filter") HomeworkFilterSheet(
        catalog, selectedCourses, selectedStatus, selectedKind, onDismiss = { panel = "" }
    ) { courses, status, kind ->
        val previous = selectedCourses
        val added = catalog.map { it.subjectKey() }.filter { key ->
            (courses == null || key in courses) && previous != null && key !in previous
        }.toSet()
        selectedCourses = courses; selectedStatus = status; selectedKind = kind; panel = ""
        store.selectCourses(courses)
        issues = store.issues(); failures = store.failureCount
        if (added.isNotEmpty()) refreshSelectedHomework(context, courses = added)
        if (previous != courses) scope.launch(Dispatchers.IO) { scheduleHomeworkReminders(context, store.read()) }
    }
    if (panel == "status") ModalBottomSheet(onDismissRequest = { panel = "" }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("同步详情", style = MaterialTheme.typography.titleLarge)
            val time = if (updated > 0) SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(updated)) else "尚未同步"
            Text(if (syncing) "正在同步已选科目" else "最近更新 · $time", style = MaterialTheme.typography.bodySmall)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            if (issues.isNotEmpty()) LazyColumn(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .48f).dp)) {
                items(issues) { issue ->
                    val hasCache = rows.any { it.subjectKey() == issue.course.subjectKey() && it.kind == issue.kind }
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(issue.course.title + " · " + chaoxingKindLabel(issue.kind), fontWeight = FontWeight.Medium)
                        Text(issue.reason + if (hasCache) " · 已保留缓存" else " · 暂无缓存", style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            } else if (failures > 0 && failureSummary.isNotBlank()) Text(failureSummary)
            when {
                error.contains("登录已失效") -> TextButton(onClick = relogin) { Text("重新登录") }
                issues.isNotEmpty() -> TextButton(onClick = { panel = ""; refreshSelectedHomework(context, failedOnly = true) }, enabled = !syncing) { Text("重试未更新项") }
                failures > 0 || error.isNotBlank() -> TextButton(onClick = { panel = ""; refreshHomework(context) }, enabled = !syncing) { Text("重新同步") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    if (confirmDisconnect) AlertDialog(onDismissRequest = { confirmDisconnect = false },
        title = { Text("退出学习通？") }, text = { Text("将清除本地任务并停止同步和提醒。") },
        confirmButton = { TextButton(onClick = {
            confirmDisconnect = false
            scope.launch {
                disconnectChaoxing(context); rows = emptyList(); catalog = emptyList(); selectedCourses = null
                updated = 0; failures = 0; issues = emptyList(); error = ""; selectedStatus = DEFAULT_TASK_STATUS; selectedKind = DEFAULT_TASK_KIND
            }
        }) { Text("退出") } }, dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("取消") } })
}

private fun chaoxingStatusLabel(status: String) = when (status) {
    "todo" -> "未完成"
    "done" -> "已完成"
    else -> "全部状态"
}

private fun chaoxingKindLabel(kind: String) = when (kind) {
    "homework" -> "作业"
    "discussion" -> "讨论"
    "exam" -> "考试"
    else -> "全部类型"
}

@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
private fun HomeworkFilterSheet(
    catalog: List<ChaoxingCourse>, selected: Set<String>?, status: String, kind: String, onDismiss: () -> Unit,
    onApply: (Set<String>?, String, String) -> Unit
) {
    var draft by remember { mutableStateOf(selected) }
    var draftStatus by remember { mutableStateOf(status) }
    var draftKind by remember { mutableStateOf(kind) }
    var query by remember { mutableStateOf("") }
    val keys = catalog.map { it.subjectKey() }.toSet()
    val labels = catalog.groupingBy { it.title }.eachCount()
    val filtered = catalog.filter { it.title.contains(query.trim(), ignoreCase = true) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 列表到达边缘后也不能把剩余的滚动手势交给筛选面板。
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = null
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp, end = 20.dp)) {
            Text("筛选任务", style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (option in listOf("todo", "done", "")) {
                    FilterChip(selected = draftStatus == option, onClick = { draftStatus = option }, label = { Text(chaoxingStatusLabel(option)) })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (option in listOf("", "homework", "discussion", "exam")) {
                    FilterChip(selected = draftKind == option, onClick = { draftKind = option }, label = { Text(chaoxingKindLabel(option)) })
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("科目 · "+(draft?.intersect(keys)?.size ?: catalog.size)+" 门", modifier = Modifier.weight(1f))
                TextButton(onClick = { draft = null }) { Text("全选") }
                TextButton(onClick = { draft = emptySet() }) { Text("清空") }
            }
            OutlinedTextField(query, { query = it }, label = { Text("搜索科目") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            // 长名单在弹层中滚动，应用和取消保持可访问。
            LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = (LocalConfiguration.current.screenHeightDp * .35f).dp)) {
                items(filtered, key = { it.subjectKey() }) { course ->
                    val key = course.subjectKey()
                    val checked = draft?.contains(key) ?: true
                    val label = course.title + if ((labels[course.title] ?: 0) > 1) "（班级 "+course.classId+"）" else ""
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                        val current = draft ?: keys
                        draft = if (checked) current - key else current + key
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(horizontal = 10.dp))
                        Text(label)
                    }
                }
                if (filtered.isEmpty()) item { Text(if (catalog.isEmpty()) "同步后可选择科目" else "未找到科目", modifier = Modifier.padding(12.dp)) }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Button(onClick = { onApply(draft, draftStatus, draftKind) }) { Text("应用筛选") }
            }
        }
    }
}

/** 详情仅按需下载；失败时保留缓存，所有网络和解析工作移出主线程。 */
@Composable
private fun HomeworkDetailContent(
    row: CachedHomework,
    store: HomeworkStore,
    revision: Long,
    expandedActions: @Composable () -> Unit
) {
    var expanded by remember(row.taskKey()) { mutableStateOf(false) }
    var detail by remember(row.taskKey()) { mutableStateOf(HomeworkDetails("")) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var generation by remember(row.taskKey()) { mutableIntStateOf(0) }
    var completedGeneration by remember(row.taskKey()) { mutableIntStateOf(-1) }
    LaunchedEffect(expanded, generation, revision) {
        // 收起只是隐藏：同一次展示周期已成功加载的详情不因展开而重新请求。
        if (!expanded || completedGeneration == generation && !store.detailIsStale(row)) return@LaunchedEffect
        val sessionGeneration = store.generation
        detail = store.detail(row)
        if (!store.detailIsStale(row) && generation == 0) {
            completedGeneration = generation
            return@LaunchedEffect
        }
        loading = true; error = ""
        try {
            val fetched = fetchHomeworkDetail(row, store, sessionGeneration)
            if (!store.saveDetail(row, fetched, sessionGeneration, revision)) return@LaunchedEffect
            detail = fetched
            completedGeneration = generation
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            val reason = (e as? HomeworkDetailException)?.message ?: "详情加载失败，请刷新列表或重新登录后重试"
            error = if (detail.html.isBlank() && detail.documents.isEmpty()) reason else "$reason（显示缓存详情）"
        } finally { loading = false }
    }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (expanded) "收起详情" else "查看详情", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (!expanded) Icon(Icons.Outlined.ChevronRight, null, modifier = Modifier.size(16.dp))
        }
    }
    if (expanded) {
        expandedActions()
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) {
            Text(error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { generation++ }) { Text("重试") }
        }
        if (detail.html.isNotBlank()) {
            // 详情随内容展开，整张任务列表统一滚动。
            com.glut.schedule.ui.requirements.HomeworkRequirementsView(detail.html, Modifier.fillMaxWidth())
        }
        if (detail.documents.isNotEmpty()) {
            HomeworkDocumentActions(row, store, detail.documents)
        }
    }
}
