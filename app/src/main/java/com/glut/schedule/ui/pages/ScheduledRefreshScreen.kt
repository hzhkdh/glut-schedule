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
@Composable
fun ScheduledRefreshScreen(campus: CampusType = CampusType.GUILIN) {
    val context = LocalContext.current
    val store = remember(context) { ScheduledRefreshStore(context) }
    val config by store.config.collectAsStateWithLifecycle(initialValue = ScheduledRefreshConfig())
    val workFlow = remember(context) { WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(UNIQUE_SCHEDULED_REFRESH) }
    val workInfos by workFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var saving by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ScheduledRefreshPlan?>(null) }
    var showRuns by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(Instant.now()) }
    val zone = ZoneId.systemDefault()
    val formatter = remember { DateTimeFormatter.ofPattern("MM月dd日 HH:mm", Locale.CHINA) }
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
            showRuns -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("运行记录", color = RefreshText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    RefreshAction("返回", onClick = { showRuns = false })
                }
                if (config.runs.isEmpty()) RefreshGroup { Text("还没有运行记录，计划执行后会显示各项结果。", color = RefreshSecondary, modifier = Modifier.padding(16.dp), fontSize = 13.sp) }
                config.runs.take(20).forEach { run ->
                    RefreshGroup {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(timestamp(run.startedAt), color = RefreshText, fontWeight = FontWeight.Medium)
                            run.results.forEach { result ->
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(result.taskId.title, modifier = Modifier.weight(1f), color = RefreshText, fontSize = 14.sp)
                                        Text(result.status.label(), color = if (result.status == RefreshTaskStatus.SUCCESS) Color(0xFF32815D) else RefreshSecondary, fontSize = 12.sp)
                                    }
                                    if (result.message.isNotBlank()) Text(result.message, color = RefreshSecondary, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
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
                Text("每日计划", color = RefreshSecondary, fontSize = 13.sp)
                RefreshGroup {
                    Column {
                        config.plans.sortedBy { it.minuteOfDay }.forEachIndexed { index, plan ->
                            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = RefreshLine)
                            RefreshPlanSummary(plan, !saving) { editing = plan }
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = RefreshLine)
                        TextButton(enabled = !saving && config.plans.size < 8,
                            onClick = { editing = ScheduledRefreshPlan(UUID.randomUUID().toString(), 420, emptySet()) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = ButtonDefaults.textButtonColors(contentColor = RefreshBlue)) {
                            Text(if (config.plans.size < 8) "＋ 添加计划" else "已达到8个计划上限")
                        }
                    }
                }
                RefreshGroup {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(config.status, modifier = Modifier.weight(1f), color = RefreshText, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            RefreshAction("查看详情 ›", onClick = { showRuns = true })
                        }
                        Text("最近运行　${timestamp(config.lastAttempt)}", color = RefreshSecondary, fontSize = 12.sp)
                        // 以系统登记记录为准，不能把尚未执行的计划自行跳到下一时刻。
                        val work = workInfos.firstOrNull { !it.state.isFinished }
                        val plannedAt = work?.nextScheduleTimeMillis?.takeIf { it in 1 until Long.MAX_VALUE }
                        val next = when {
                            !config.enabled -> "未开启"
                            work?.state == WorkInfo.State.RUNNING -> "正在执行"
                            plannedAt == null -> "正在安排或暂无可执行项目"
                            plannedAt <= now.toEpochMilli() -> "等待系统执行"
                            else -> timestamp(plannedAt)
                        }
                        Text("下次计划　$next", color = RefreshSecondary, fontSize = 12.sp)
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("系统可能因省电或网络延迟执行。", color = RefreshSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    RefreshAction(if (showHelp) "收起说明" else "运行说明 ›", onClick = { showHelp = !showHelp })
                }
                if (showHelp) Text("关闭后保留全部计划。每个时刻可独立选择内容，相邻时刻至少间隔15分钟。需要验证码或重新登录时跳过对应项目，其他项目继续执行。强行停止应用后，请重新打开应用恢复调度。", color = RefreshSecondary, fontSize = 12.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(12.dp))
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
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
    var selected by remember(plan.id) { mutableStateOf(plan.taskIds) }
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
            RefreshTaskId.entries.groupBy { it.group }.forEach { (group, tasks) ->
                val groupUnsupported = tasks.none { refreshTaskSupported(it, campus) }
                Text(group, color = RefreshSecondary, fontSize = 12.sp)
                // 独立登录的说明放在组头，避免每个项目重复一遍撑长列表。
                if (groupUnsupported || group == "财务" || group == "体测") {
                    Text(
                        if (groupUnsupported) "当前来源暂不支持此组后台刷新" else "成绩或账务查询使用独立登录状态，失效后跳过",
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
                    !validateRefreshTimes(otherPlans.map { it.minuteOfDay } + minute) -> "最多8个计划，相邻时刻至少间隔15分钟（含跨午夜），时间不能重复"
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
