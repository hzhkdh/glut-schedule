package com.glut.schedule.ui.pages

import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.service.refresh.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

private val RefreshBg = Color(0xFFF6F4EF)
private val RefreshCard = Color(0xFFFFFEFB)
private val RefreshText = Color(0xFF141821)
private val RefreshSecondary = Color(0xFF667085)
private val RefreshBlue = Color(0xFF3F7DF6)
private val RefreshLine = Color(0xFFEDE8DE)

/** 页面只保存每日计划；统一由应用层观察配置并登记系统任务。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduledRefreshScreen(campus: CampusType = CampusType.GUILIN, onLoginRequired: ((RefreshTaskId) -> Unit)? = null) {
    val context = LocalContext.current
    val store = remember(context) { ScheduledRefreshStore(context) }
    val config by store.config.collectAsStateWithLifecycle(initialValue = ScheduledRefreshConfig())
    val workFlow = remember(context) { WorkManager.getInstance(context).getWorkInfosByTagFlow(SCHEDULED_REFRESH_TAG) }
    val workInfos by workFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var saving by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ScheduledRefreshPlan?>(null) }
    var showRuns by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(Instant.now()) }
    var batteryOptimizationIgnored by remember(context) { mutableStateOf(androidBatteryOptimizationIgnored(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    // 设置页返回时重新读取标准状态；厂商后台耗电、自启动状态始终留给用户在设置中确认。
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) batteryOptimizationIgnored = androidBatteryOptimizationIgnored(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val zone = ZoneId.systemDefault()
    val formatter = remember { DateTimeFormatter.ofPattern("MM月dd日 HH:mm", Locale.CHINA) }
    // 摘要和详情始终读取同一份记录，旧版的独立状态文字不再覆盖实际运行结果。
    val runs = config.runs.sortedByDescending { it.startedAt }.take(20)
    val latestRun = runs.firstOrNull()
    fun timestamp(value: Long) = if (value > 0) formatter.format(Instant.ofEpochMilli(value).atZone(zone)) else "尚无记录"
    LaunchedEffect(Unit) { while (true) { now = Instant.now(); delay(30_000) } }
    fun persist(action: suspend () -> Unit) {
        if (saving) return
        saving = true
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { snackbar.showSnackbar("保存失败，请稍后重试") }
            finally { saving = false }
        }
    }
    BackHandler(enabled = editing != null || showRuns) { editing = null; showRuns = false }
    Box(Modifier.fillMaxSize().background(RefreshBg)) {
        val draft = editing
        when {
            draft != null -> RefreshPlanEditor(
                draft, config.plans.filterNot { it.id == draft.id }, campus, saving,
                canDelete = config.plans.size > 1 && config.plans.any { it.id == draft.id },
                onCancel = { editing = null },
                onDelete = { persist { store.setPlans(config.plans.filterNot { it.id == draft.id }); editing = null } },
                onSave = { updated -> persist {
                    store.setPlans((config.plans.filterNot { it.id == updated.id } + updated).sortedBy { it.minuteOfDay })
                    editing = null
                } }
            )
            showRuns -> RefreshRunsPage(runs, config.lastAttempt, now, onBack = { showRuns = false }, onLoginRequired = onLoginRequired)
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                RefreshGroup {
                    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("开启定时刷新", color = RefreshText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text("按每日计划更新所选内容", color = RefreshSecondary, fontSize = 12.sp)
                        }
                        Switch(config.enabled, { enabled -> persist { store.setEnabled(enabled) } }, enabled = !saving,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = RefreshBlue, checkedThumbColor = Color.White,
                                checkedBorderColor = Color.Transparent,
                                uncheckedTrackColor = Color(0xFFD9DEE6), uncheckedThumbColor = Color.White,
                                uncheckedBorderColor = Color.Transparent
                            ))
                    }
                }
                if (config.learningMigrationPending) {
                    // 一次性迁移提示不再占用整张卡片，右侧操作仍保留48dp触摸区域。
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("学习通同步已并入每日计划", color = RefreshSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                            Text("编辑计划即可选择同步内容", color = RefreshSecondary, fontSize = 11.sp, lineHeight = 17.sp)
                        }
                        RefreshAction("知道了", enabled = !saving, onClick = { persist { store.dismissLearningMigration() } })
                    }
                }
                // 测试版旧配置可能超出新目录或数量限制，明确说明恢复方式，避免开关看似无响应。
                if (config.status == "计划配置不可用，请重新编辑保存") {
                    Text("原计划不符合当前规则，定时刷新已暂停。请编辑下方计划并保存，再开启刷新。",
                        color = Color(0xFFB4483D), fontSize = 12.sp, lineHeight = 19.sp)
                }
                Text("每日计划", color = RefreshSecondary, fontSize = 13.sp)
                RefreshGroup {
                    Column {
                        config.plans.sortedBy { it.minuteOfDay }.forEachIndexed { index, plan ->
                            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = RefreshLine)
                            RefreshPlanSummary(plan, !saving) { editing = plan }
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = RefreshLine)
                        TextButton(enabled = !saving && config.plans.size < MAX_SCHEDULED_REFRESH_PLANS,
                            onClick = { editing = ScheduledRefreshPlan(UUID.randomUUID().toString(), 420, emptySet()) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = ButtonDefaults.textButtonColors(contentColor = RefreshBlue)) {
                            Text(if (config.plans.size < MAX_SCHEDULED_REFRESH_PLANS) "＋ 添加计划" else "已达到3个计划上限")
                        }
                    }
                }
                Text("运行情况", color = RefreshSecondary, fontSize = 13.sp)
                RefreshGroup {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("上次刷新", modifier = Modifier.weight(1f), color = RefreshText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            val state = latestRun?.let { scheduledRefreshRunSummary(it) }
                            Text(state?.title ?: if (config.lastAttempt > 0) "旧版记录" else "尚未执行",
                                color = state?.let { refreshRunColor(it.status) } ?: RefreshSecondary, fontSize = 12.sp,
                                modifier = Modifier.background(Color(0xFFF1F3F6), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                        if (latestRun != null) {
                            Text(refreshRunCounts(latestRun, scheduledRefreshRunSummary(latestRun)), color = RefreshSecondary, fontSize = 12.sp)
                            RefreshSummaryRow("实际开始", timestamp(latestRun.startedAt))
                            RefreshSummaryRow("计划时刻", when {
                                latestRun.scheduledAt > 0 -> timestamp(latestRun.scheduledAt)
                                latestRun.minuteOfDay != null -> refreshMinuteText(latestRun.minuteOfDay)
                                else -> "旧版未记录"
                            })
                            if (latestRun.attempt > 1) RefreshSummaryRow("执行次数", "第 ${latestRun.attempt} 次")
                        } else if (config.lastAttempt > 0) {
                            RefreshSummaryRow("实际开始", timestamp(config.lastAttempt))
                        } else Text("计划执行后显示结果", color = RefreshSecondary, fontSize = 12.sp)
                        val pendingWorks = workInfos.filter { !it.state.isFinished }
                        val work = pendingWorks.firstOrNull { it.state == WorkInfo.State.RUNNING }
                            ?: pendingWorks.filter { it.nextScheduleTimeMillis in 1 until Long.MAX_VALUE }
                                .minByOrNull { it.nextScheduleTimeMillis }
                        val plannedAt = work?.nextScheduleTimeMillis?.takeIf { it in 1 until Long.MAX_VALUE }
                        val next = when {
                            !config.enabled -> "未开启"
                            work?.state == WorkInfo.State.RUNNING -> "正在执行"
                            plannedAt == null -> "正在安排或暂无可执行项目"
                            plannedAt <= now.toEpochMilli() -> "等待系统执行"
                            else -> timestamp(plannedAt)
                        }
                        HorizontalDivider(color = RefreshLine)
                        RefreshSummaryRow("下次计划", next, emphasize = true)
                        HorizontalDivider(color = RefreshLine)
                        RefreshSettingRow("查看运行记录", null) { showRuns = true }
                    }
                }
                Text("后台运行", color = RefreshSecondary, fontSize = 13.sp)
                RefreshGroup {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        RefreshSettingRow("后台耗电管理", "待确认") {
                            val opened = BackgroundRunSettings.openBatterySettings(context)
                            scope.launch { snackbar.showSnackbar(if (opened) "请找到本应用，检查后台耗电选项" else "无法打开设置，请手动检查电池选项") }
                        }
                        HorizontalDivider(color = RefreshLine)
                        RefreshSettingRow("自启动", "待确认") {
                            val opened = BackgroundRunSettings.openAppDetails(context)
                            scope.launch { snackbar.showSnackbar(if (opened) "请在本应用权限或系统设置中查找自启动" else "无法打开设置，请手动查找自启动") }
                        }
                        HorizontalDivider(color = RefreshLine)
                        Text("Android 电池优化：${when (batteryOptimizationIgnored) {
                            true -> "已豁免"
                            false -> "受限"
                            null -> "暂无法读取"
                        }}", color = RefreshSecondary, fontSize = 11.sp, modifier = Modifier.padding(vertical = 12.dp))
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("系统可能延后执行", color = RefreshSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    RefreshAction(if (showHelp) "收起说明" else "运行说明 ⓘ", onClick = { showHelp = !showHelp })
                }
                if (showHelp) RefreshGroup {
                    Text("允许后台耗电、自启动可能减少部分后台限制，开关需在系统设置中自行确认。Android 电池优化状态不代表厂商开关状态。\n\n关闭定时刷新会保留计划。中断后在原到期时间两小时内最多恢复两次，保留成功项目；需要重新登录时不反复认证。网络、省电或系统资源仍可能延后执行；强行停止应用后，请重新打开以恢复调度。",
                        color = RefreshSecondary, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(16.dp))
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

/** 只展示最近二十次，按实际开始日期归组；默认展开最近一次，避免长记录挤满屏幕。 */
@Composable
private fun RefreshRunsPage(
    runs: List<ScheduledRefreshRun>, legacyAttempt: Long, now: Instant,
    onBack: () -> Unit, onLoginRequired: ((RefreshTaskId) -> Unit)?
) {
    var expanded by remember(runs.firstOrNull()?.id) { mutableStateOf(setOfNotNull(runs.firstOrNull()?.id)) }
    val zone = ZoneId.systemDefault()
    val dateFormatter = remember { DateTimeFormatter.ofPattern("yyyy年MM月dd日", Locale.CHINA) }
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm:ss", Locale.CHINA) }
    val plannedFormatter = remember { DateTimeFormatter.ofPattern("MM月dd日 HH:mm", Locale.CHINA) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("运行记录", color = RefreshText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("最近20次 · 点击展开逐项结果", color = RefreshSecondary, fontSize = 12.sp)
            }
            RefreshAction("返回", onClick = onBack)
        }
        if (runs.isEmpty()) RefreshGroup {
            Text(if (legacyAttempt > 0) "旧版运行记录无逐项详情，新计划执行后将在这里显示。" else "还没有运行记录，计划执行后会显示各项结果。",
                color = RefreshSecondary, modifier = Modifier.padding(16.dp), fontSize = 13.sp, lineHeight = 20.sp)
        }
        runs.groupBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }.forEach { (date, dayRuns) ->
            Text(dateFormatter.format(date), color = RefreshSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            dayRuns.forEach { run ->
                val summary = scheduledRefreshRunSummary(run)
                val isExpanded = run.id in expanded
                RefreshGroup {
                    Column {
                        Column(Modifier.fillMaxWidth().clickable {
                            expanded = if (isExpanded) expanded - run.id else expanded + run.id
                        }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(timeFormatter.format(Instant.ofEpochMilli(run.startedAt).atZone(zone)), modifier = Modifier.weight(1f),
                                    color = RefreshText, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                Text(summary.title, color = refreshRunColor(summary.status), fontSize = 12.sp)
                                Text(if (isExpanded) "  ⌃" else "  ›", color = RefreshSecondary, fontSize = 15.sp)
                            }
                            Text("计划 ${if (run.scheduledAt > 0) plannedFormatter.format(Instant.ofEpochMilli(run.scheduledAt).atZone(zone)) else "旧版未记录"}${refreshAttemptText(run)}",
                                color = RefreshSecondary, fontSize = 12.sp)
                            Text(refreshRunDuration(run, now),
                                color = RefreshSecondary, fontSize = 12.sp)
                            if (run.reason.isNotBlank()) Text(run.reason, color = RefreshSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                            Text(refreshRunCounts(run, summary),
                                color = RefreshSecondary, fontSize = 12.sp)
                        }
                        if (isExpanded) {
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = RefreshLine)
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                // 原始代码只保存在诊断记录中；页面仅显示已知原因，未知值不猜测。
                                run.stopReason?.let(::refreshStopReasonLabel)?.let {
                                    Text("停止原因：$it", color = RefreshSecondary, fontSize = 12.sp)
                                }
                                val tasks = (run.taskIds + run.results.map { it.taskId }).sortedBy { it.ordinal }
                                if (tasks.isEmpty()) Text("旧版记录无逐项详情", color = RefreshSecondary, fontSize = 12.sp)
                                tasks.forEach { task ->
                                    val result = run.results.lastOrNull { it.taskId == task }
                                    val label = result?.status?.label() ?: when {
                                        run.state == ScheduledRefreshRunState.INTERRUPTED -> "已中断"
                                        run.activeTask == task -> "正在刷新"
                                        run.state == ScheduledRefreshRunState.RUNNING -> "等待刷新"
                                        else -> "无结果"
                                    }
                                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text(task.title, modifier = Modifier.weight(1f), color = RefreshText, fontSize = 14.sp)
                                            Text(label, color = if (result?.status == RefreshTaskStatus.SUCCESS) Color(0xFF32815D) else RefreshSecondary, fontSize = 12.sp)
                                        }
                                        // 失败原因采用简短说明，避免直接渲染接口响应、登录信息或堆栈。
                                        if (result != null && result.status != RefreshTaskStatus.SUCCESS) {
                                            Text(refreshFailureReason(result), color = RefreshSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                                            if (result.status == RefreshTaskStatus.LOGIN_REQUIRED && onLoginRequired != null) {
                                                RefreshAction(if (task == RefreshTaskId.CHAOXING) "前往学习通登录 ›" else "前往教务登录 ›",
                                                    onClick = { onLoginRequired(task) })
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
        Spacer(Modifier.height(12.dp))
    }
}

private fun refreshRunColor(status: ScheduledRefreshRunStatus) = when (status) {
    ScheduledRefreshRunStatus.RUNNING -> RefreshBlue
    ScheduledRefreshRunStatus.FULL_SUCCESS -> Color(0xFF32815D)
    ScheduledRefreshRunStatus.PARTIAL_SUCCESS, ScheduledRefreshRunStatus.LOGIN_REQUIRED -> Color(0xFF9B6D24)
    ScheduledRefreshRunStatus.FAILED -> Color(0xFFB4483D)
    ScheduledRefreshRunStatus.INTERRUPTED -> RefreshSecondary
}

/** 登录失效单独计数，避免把跳过登录验证的项目误报为普通刷新失败。 */
private fun refreshRunCounts(run: ScheduledRefreshRun, summary: ScheduledRefreshRunSummary): String {
    val failed = run.results.count { it.status == RefreshTaskStatus.FAILED }
    val login = run.results.count { it.status == RefreshTaskStatus.LOGIN_REQUIRED }
    val unsupported = run.results.count { it.status == RefreshTaskStatus.UNSUPPORTED }
    val unfinished = (summary.totalCount - summary.successCount).coerceAtLeast(0)
    return "${summary.successCount} 项已更新" +
        (if (unfinished > 0) " · $unfinished 项未完成" else "") +
        (if (failed > 0) " · $failed 项失败" else "") +
        (if (login > 0) " · $login 项需登录" else "") +
        (if (unsupported > 0) " · $unsupported 项不支持" else "")
}

private fun refreshRunDuration(run: ScheduledRefreshRun, now: Instant): String {
    val running = run.state == ScheduledRefreshRunState.RUNNING
    // 新记录只使用各次有效执行的累计快照，避免把退避、等待恢复的时间计入执行耗时。
    if (run.scheduledAt > 0 || run.durationMillis > 0) {
        val seconds = run.durationMillis.coerceAtLeast(0) / 1_000
        val elapsed = if (seconds < 60) "${seconds}秒" else "${seconds / 60}分${seconds % 60}秒"
        return "累计执行$elapsed${if (running) " · 结束后更新" else ""}"
    }
    val end = if (running) now.toEpochMilli() else run.finishedAt
    if (end <= 0) return "结束时间未记录"
    val seconds = ((end - run.startedAt).coerceAtLeast(0) / 1_000)
    val elapsed = if (seconds < 60) "${seconds}秒" else "${seconds / 60}分${seconds % 60}秒"
    return if (running) "已运行$elapsed" else "耗时$elapsed"
}

/** 首次执行保持简洁，仅恢复轮次显示次数；旧记录的默认值不会被误标成恢复。 */
private fun refreshAttemptText(run: ScheduledRefreshRun): String =
    if (run.attempt > 1) " · 第${run.attempt}次执行" else ""

/** 依据 WorkManager 常量解释停止原因，明确区分用户停止与应用自行取消。 */
private fun refreshStopReasonLabel(reason: Int): String? = when (reason) {
    WorkInfo.STOP_REASON_NOT_STOPPED -> null
    WorkInfo.STOP_REASON_TIMEOUT, WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT -> "系统超时"
    WorkInfo.STOP_REASON_QUOTA -> "后台配额限制"
    WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION, WorkInfo.STOP_REASON_APP_STANDBY -> "省电或待机限制"
    WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "网络条件改变"
    WorkInfo.STOP_REASON_USER -> "用户停止"
    WorkInfo.STOP_REASON_CANCELLED_BY_APP -> "应用取消"
    WorkInfo.STOP_REASON_PREEMPT -> "系统资源限制"
    WorkInfo.STOP_REASON_DEVICE_STATE -> "系统设备状态限制"
    WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW -> "电量条件改变"
    WorkInfo.STOP_REASON_CONSTRAINT_CHARGING -> "充电条件改变"
    WorkInfo.STOP_REASON_CONSTRAINT_DEVICE_IDLE -> "待机条件改变"
    WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW -> "存储空间条件改变"
    WorkInfo.STOP_REASON_SYSTEM_PROCESSING -> "系统处理需要"
    else -> "未知系统原因"
}

private fun refreshFailureReason(result: ScheduledRefreshTaskResult): String = when (result.status) {
    RefreshTaskStatus.LOGIN_REQUIRED -> if (result.taskId == RefreshTaskId.CHAOXING) "登录已失效，请前往学习通页面重新登录" else "需要重新登录或完成验证，请前往教务导入页面处理"
    RefreshTaskStatus.UNSUPPORTED -> "当前来源暂不支持此项后台刷新"
    RefreshTaskStatus.CANCELLED -> "运行已中断，可等待下次计划"
    RefreshTaskStatus.FAILED -> when {
        result.message.contains("网络") || result.message.contains("连接") || result.message.contains("超时") -> "网络连接失败或超时，下次计划将重试"
        result.message.contains("解析") || result.message.contains("格式") -> "返回内容暂时无法识别，请稍后到对应页面重试"
        else -> "刷新未完成，请稍后到对应页面重试"
    }
    RefreshTaskStatus.SUCCESS -> ""
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RefreshPlanSummary(plan: ScheduledRefreshPlan, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(refreshMinuteText(plan.minuteOfDay), color = RefreshText, fontSize = 26.sp, fontWeight = FontWeight.Medium)
            Text("每天", color = RefreshSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 10.dp))
            Text("编辑 ›", color = RefreshBlue, fontSize = 13.sp)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val tasks = plan.taskIds.sortedBy { it.ordinal }
            val labels = tasks.take(3).map { it.title } + if (tasks.size > 3) listOf("＋${tasks.size - 3}项") else emptyList()
            labels.forEach { label -> Text(label, color = Color(0xFF536B89), fontSize = 11.sp,
                modifier = Modifier.background(Color(0xFFEEF2F8), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 5.dp)) }
        }
    }
}

/** 草稿不直接写入配置，取消或无效选择不会改变原计划。 */
@Composable
private fun RefreshPlanEditor(plan: ScheduledRefreshPlan, otherPlans: List<ScheduledRefreshPlan>, campus: CampusType,
    saving: Boolean, canDelete: Boolean, onCancel: () -> Unit, onDelete: () -> Unit, onSave: (ScheduledRefreshPlan) -> Unit) {
    val context = LocalContext.current
    var minute by remember(plan.id) { mutableStateOf(plan.minuteOfDay) }
    // 财务、体测和公共数据不再参与定时计划，编辑旧计划时同步移除这些项目。
    var selected by remember(plan.id) { mutableStateOf(plan.taskIds.filter { it.schedulable }.toSet()) }
    var validation by remember(plan.id) { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("编辑计划", color = RefreshText, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            RefreshAction("取消", enabled = !saving, onClick = onCancel)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RefreshGroup {
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(enabled = !saving) {
                    TimePickerDialog(context, { _, hour, value -> minute = hour * 60 + value; validation = "" }, minute / 60, minute % 60, true).show()
                }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("每天", color = RefreshText, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text(refreshMinuteText(minute), color = RefreshText, fontSize = 26.sp, fontWeight = FontWeight.Medium)
                    Text("  ›", color = RefreshSecondary)
                }
            }
            Text("刷新内容 · ${selected.size}项已选", color = RefreshSecondary, fontSize = 13.sp)
            RefreshTaskId.entries.filter { it.schedulable }.groupBy { it.group }.forEach { (group, tasks) ->
                val groupUnsupported = tasks.none { refreshTaskSupported(it, campus) }
                Text(group, color = RefreshSecondary, fontSize = 12.sp)
                // 独立登录的说明放在组头，避免每个项目重复一遍撑长列表。
                if (groupUnsupported) {
                    Text(
                        "当前来源暂不支持教务后台刷新，仍可选择学习通",
                        color = RefreshSecondary, fontSize = 11.sp, lineHeight = 17.sp
                    )
                }
                RefreshGroup {
                    Column {
                        tasks.forEachIndexed { index, task ->
                            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = RefreshLine)
                            val supported = refreshTaskSupported(task, campus)
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .toggleable(value = task in selected, enabled = supported && !saving, role = Role.Checkbox) {
                                    selected = if (it) selected + task else selected - task; validation = ""
                                }.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(task.title, color = if (supported) RefreshText else Color(0xFF9AA1AB), fontSize = 14.sp)
                                    val note = when {
                                        !supported -> if (groupUnsupported) "" else "当前来源暂不支持后台刷新"
                                        task == RefreshTaskId.PROFESSIONAL_SCORE -> "关联更新考试成绩与教学计划"
                                        task == RefreshTaskId.COURSE_TIME_STATS -> "关联更新课表并重新统计"
                                        task == RefreshTaskId.SEMESTER_OVERVIEW -> "关联更新课表、调课与校历"
                                        task == RefreshTaskId.CHAOXING -> "使用学习通账号的登录状态"
                                        else -> ""
                                    }
                                    if (note.isNotEmpty()) Text(note, color = RefreshSecondary, fontSize = 11.sp, lineHeight = 17.sp)
                                }
                                Checkbox(task in selected, onCheckedChange = null, enabled = supported && !saving,
                                    colors = CheckboxDefaults.colors(checkedColor = RefreshBlue, checkmarkColor = Color.White))
                            }
                        }
                    }
                }
            }
            if (selected.any { !refreshTaskSupported(it, campus) }) Text("原计划中的不可用项目仍会保留，执行时按当前来源跳过。", color = RefreshSecondary, fontSize = 12.sp)
            if (canDelete) RefreshAction("删除此计划", enabled = !saving, onClick = onDelete, color = Color(0xFFB4483D))
            Spacer(Modifier.height(12.dp))
        }
        // 保存区独立于长任务列表，小屏与大字体下也不会遮挡末项。
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            if (validation.isNotBlank()) Text(validation, color = Color(0xFFB4483D), fontSize = 12.sp)
            Button(onClick = {
                validation = when {
                    selected.isEmpty() -> "请至少选择一个刷新项目"
                    !validateRefreshTimes(otherPlans.map { it.minuteOfDay } + minute) -> "最多3个计划，相邻时刻至少间隔15分钟（含跨午夜），时间不能重复"
                    else -> ""
                }
                if (validation.isEmpty()) onSave(plan.copy(minuteOfDay = minute, taskIds = selected))
            }, enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
                // 显式指定白色文字，避免系统深色主题把文字变为深色而降低对比度。
                colors = ButtonDefaults.buttonColors(containerColor = RefreshBlue, contentColor = Color.White)) {
                Text(if (saving) "正在保存…" else "保存计划", color = Color.White)
            }
        }
    }
}

@Composable
private fun RefreshGroup(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = RefreshCard, shape = RoundedCornerShape(14.dp), content = content)
}

/** 标签和值保持两侧对齐，长日期和大字体自然换行，不用空格模拟列宽。 */
@Composable
private fun RefreshSummaryRow(label: String, value: String, emphasize: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = RefreshSecondary, fontSize = 12.sp, modifier = Modifier.weight(0.8f))
        Text(value, color = if (emphasize) RefreshText else RefreshSecondary, fontSize = 12.sp,
            fontWeight = if (emphasize) FontWeight.Medium else FontWeight.Normal,
            textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1.5f))
    }
}

/** 设置和记录入口使用同一整行触摸区域，右侧状态与箭头统一对齐。 */
@Composable
private fun RefreshSettingRow(label: String, status: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = if (status == null) RefreshBlue else RefreshText, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (status != null) Text(status, color = RefreshSecondary, fontSize = 12.sp)
        Text("›", color = RefreshSecondary, fontSize = 18.sp)
    }
}

@Composable
private fun RefreshAction(label: String, enabled: Boolean = true, color: Color = RefreshBlue, onClick: () -> Unit) {
    TextButton(onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.textButtonColors(contentColor = color)) {
        Text(label, fontSize = 13.sp)
    }
}

private fun refreshMinuteText(minute: Int) = String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)
private fun RefreshTaskStatus.label() = when (this) {
    RefreshTaskStatus.SUCCESS -> "成功"
    RefreshTaskStatus.FAILED -> "失败"
    RefreshTaskStatus.LOGIN_REQUIRED -> "需登录"
    RefreshTaskStatus.UNSUPPORTED -> "不支持"
    RefreshTaskStatus.CANCELLED -> "已取消"
}
