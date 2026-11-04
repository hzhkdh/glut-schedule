package com.glut.schedule.ui.pages

import android.Manifest
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
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
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.ui.viewinterop.AndroidView
import com.glut.schedule.service.chaoxing.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
@OptIn(ExperimentalLayoutApi::class)
private fun ChaoxingHomeworkContent() {
    val context = LocalContext.current
    val store = remember { HomeworkStore(context) }
    var login by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf(store.read()) }
    var updated by remember { mutableLongStateOf(store.updatedAt) }
    var failures by remember { mutableIntStateOf(store.failureCount) }
    var failureSummary by remember { mutableStateOf(store.failureSummary) }
    var error by remember { mutableStateOf(store.lastError) }
    var syncing by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var loginError by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    var selectedStatus by rememberSaveable { mutableStateOf("未交") }
    var selectedSubject by rememberSaveable { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var completedLogin by remember { mutableStateOf(false) }
    val visibleRows = filterHomework(rows, selectedStatus, selectedSubject)
    LaunchedEffect(rows) {
        if (selectedStatus.isNotEmpty() && selectedStatus != "未交" && rows.none { it.status == selectedStatus }) selectedStatus = "未交"
        if (selectedSubject.isNotEmpty() && rows.none { it.subjectKey() == selectedSubject }) selectedSubject = ""
    }
    BackHandler(enabled = login) { login = false }
    LaunchedEffect(login) { if (login) completedLogin = false }
    val scope = rememberCoroutineScope()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // 每次进入或恢复菜单时补检查；持久化时间戳使重建页面不会反复全量刷新。
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        scope.launch {
            if (store.connected) {
                scheduleHomeworkSync(context)
                if (homeworkRefreshDue(System.currentTimeMillis(), store.successAt, store.attemptAt)) {
                    refreshHomework(context)
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            rows = store.read(); updated = store.updatedAt; failures = store.failureCount; error = store.lastError
            failureSummary = store.failureSummary
            syncing = withContext(Dispatchers.IO) {
                listOf("chaoxing_manual", "chaoxing_sync").any { name ->
                    runCatching { WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()
                        .any { it.state == WorkInfo.State.RUNNING } }.getOrDefault(false)
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
                    Text("登录后查看作业和截止时间", style = MaterialTheme.typography.bodyMedium)
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
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFEFB)),
                shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Color(0xFFE4E2DD))) {
              Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.MenuBook, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (syncing) "正在同步作业…" else if (failures > 0) "学习通部分更新" else if (updated > 0L) "学习通已连接" else "等待同步", fontWeight = FontWeight.SemiBold)
                    Text(if (updated == 0L) "尚未同步" else "更新于 ${SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(updated))}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    refreshHomework(context)
                }, enabled = !syncing) {
                    if (syncing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.Refresh, "刷新作业", tint = MaterialTheme.colorScheme.primary)
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Outlined.MoreVert, "更多操作") }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(text = { Text("重新登录") }, onClick = {
                            menuExpanded = false
                            scope.launch {
                                // 重登录前结束旧账号工作并清掉详情，避免跨账号泄漏缓存。
                                disconnectChaoxing(context)
                                rows = emptyList(); updated = 0; failures = 0; error = ""
                                loginError = ""; login = true
                            }
                        })
                        DropdownMenuItem(text = { Text("退出并清除作业") }, onClick = { menuExpanded = false; confirmDisconnect = true })
                    }
                }
              }
            }
            if (failures > 0) {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().background(Color(0xFFFFF1DD), RoundedCornerShape(12.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.Error, contentDescription = null, tint = Color(0xFFE77C13), modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f)) {
                        Text("有 $failures 门课程未能更新，保留已有缓存", color = Color(0xFF925311), style = MaterialTheme.typography.bodySmall)
                        if (failureSummary.isNotBlank()) Text(failureSummary, color = Color(0xFF925311), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("作业列表", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("${visibleRows.size} / ${rows.size} 项", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // 两个筛选框等宽且位置固定，长科目名只省略显示，不参与改变锚点宽度。
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeworkDropdown("状态", if (selectedStatus.isEmpty()) "全部状态" else selectedStatus,
                    listOf("" to "全部状态") + (listOf("未交") + rows.map { it.status }).distinct().sorted().map { it to it.ifBlank { "状态未知" } },
                    selectedStatus, Modifier.weight(1f)) { selectedStatus = it }
                val subjects = rows.distinctBy { it.subjectKey() }
                HomeworkDropdown("科目", subjects.find { it.subjectKey() == selectedSubject }?.courseName ?: "全部科目",
                    listOf("" to "全部科目") + subjects.map { it.subjectKey() to (it.courseName + if (subjects.count { s -> s.courseName == it.courseName } > 1) "（班级 ${it.classId}）" else "") },
                    selectedSubject, Modifier.weight(1f)) { selectedSubject = it }
            }
            if (visibleRows.isEmpty()) Text(if(rows.isEmpty()) "暂无已同步的作业" else "没有符合条件的作业，请调整筛选")
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(visibleRows.sortedWith(compareBy<CachedHomework> { homeworkState(it.status) != HomeworkState.TODO }.thenBy { it.deadline ?: Long.MAX_VALUE })) { row ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFEFB)),
                        shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Color(0xFFE4E2DD))) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(row.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                // 状态色只承担提示作用，保留原始状态文字避免误解。
                                val badgeBg = when(row.status) { "未交" -> Color(0xFFFFEAEA); "已完成", "已批阅" -> Color(0xFFE8F5E9); "待批阅" -> Color(0xFFEAF1FF); "待互评" -> Color(0xFFFFF1DD); else -> Color(0xFFF0F0ED) }
                                val badgeFg = when(row.status) { "未交" -> Color(0xFFB3261E); "已完成", "已批阅" -> Color(0xFF26713A); "待批阅" -> Color(0xFF3264BB); "待互评" -> Color(0xFF99520A); else -> Color(0xFF667085) }
                                Text(row.status.ifBlank { "状态未知" }, style = MaterialTheme.typography.labelMedium, color = badgeFg,
                                    modifier = Modifier.background(badgeBg, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 5.dp))
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(row.courseName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Outlined.Event, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(row.deadline?.let { "截止 ${SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(it))}" } ?: "暂无截止时间",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HomeworkDetailContent(row, store)
                            remainingHomeworkTime(row.deadline, row.status, now)?.let { remaining ->
                                Text(remaining, style = MaterialTheme.typography.bodySmall,
                                    color = if (row.deadline!! - now <= 86_400_000L) Color(0xFFB3261E) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmDisconnect) AlertDialog(onDismissRequest = { confirmDisconnect = false },
        title = { Text("退出学习通？") }, text = { Text("将清除本地作业并停止同步和提醒。") },
        confirmButton = { TextButton(onClick = {
            confirmDisconnect = false
            scope.launch { disconnectChaoxing(context); rows = emptyList(); updated = 0; failures = 0; error = ""; selectedStatus = "未交"; selectedSubject = "" }
        }) { Text("退出") } }, dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("取消") } })
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun HomeworkDropdown(title: String, label: String, options: List<Pair<String, String>>, selected: String,
    modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        Surface(modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).heightIn(min = 64.dp),
            color = Color(0xFFFFFEFB), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color(0xFFE4E2DD))) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            }
        }
        // 锚定菜单与框同宽；菜单过长时内部滚动，避免因内容宽度变化左右重定位。
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 280.dp), containerColor = Color(0xFFFFFEFB), tonalElevation = 0.dp) {
            options.forEach { (key, text) -> DropdownMenuItem(
                text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
                trailingIcon = { if (key == selected) Icon(Icons.Filled.Check, "已选中", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.background(if (key == selected) Color(0xFFEBF2FF) else Color.Transparent),
                onClick = { expanded = false; onSelect(key) }) }
        }
    }
}

/** 详情仅按需下载；失败时保留缓存，所有网络和解析工作移出主线程。 */
@Composable
private fun HomeworkDetailContent(row: CachedHomework, store: HomeworkStore) {
    var expanded by remember(row.courseId, row.classId, row.workId) { mutableStateOf(false) }
    var html by remember(row.courseId, row.classId, row.workId) { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var generation by remember(row.courseId, row.classId, row.workId) { mutableIntStateOf(0) }
    var completedGeneration by remember(row.courseId, row.classId, row.workId) { mutableIntStateOf(-1) }
    LaunchedEffect(expanded, generation) {
        // 收起只是隐藏：同一次展示周期已成功加载的详情不因展开而重新请求。
        if (!expanded || completedGeneration == generation) return@LaunchedEffect
        val sessionGeneration = store.generation
        html = store.detail(row)
        loading = true; error = ""
        try {
            val detail = fetchHomeworkDetail(row, store, sessionGeneration)
            if (!store.saveDetail(row, detail, sessionGeneration)) return@LaunchedEffect
            html = detail
            completedGeneration = generation
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            val reason = (e as? HomeworkDetailException)?.message ?: "详情加载失败，请刷新列表或重新登录后重试"
            error = if (html.isBlank()) reason else "$reason（显示缓存详情）"
        } finally { loading = false }
    }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起详情" else "展开详情") }
    if (expanded) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!loading && error.isBlank() && completedGeneration >= 0) {
            TextButton(onClick = { generation++ }) { Text("刷新详情") }
        }
        if (error.isNotBlank()) {
            Text(error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { generation++ }) { Text("重试") }
        }
        if (html.isNotBlank()) {
            // 详情随内容展开，整张作业列表统一滚动。
            com.glut.schedule.ui.requirements.HomeworkRequirementsView(html, Modifier.fillMaxWidth())
        }
    }
}
