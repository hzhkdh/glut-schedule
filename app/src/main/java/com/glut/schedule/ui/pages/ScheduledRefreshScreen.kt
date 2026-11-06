package com.glut.schedule.ui.pages

import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glut.schedule.service.refresh.ScheduledRefreshConfig
import com.glut.schedule.service.refresh.ScheduledRefreshStore
import androidx.work.WorkManager
import androidx.work.WorkInfo
import com.glut.schedule.service.refresh.UNIQUE_SCHEDULED_REFRESH
import com.glut.schedule.service.refresh.validateRefreshTimes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 设置仅持久化用户意图；统一由应用层监听变化并排程，避免页面重复创建任务。 */
@Composable
fun ScheduledRefreshScreen() {
    val context = LocalContext.current
    val store = remember(context) { ScheduledRefreshStore(context) }
    val config by store.config.collectAsStateWithLifecycle(initialValue = ScheduledRefreshConfig())
    val registeredWork = remember(context) {
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(UNIQUE_SCHEDULED_REFRESH)
    }
    val workInfos by registeredWork.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var saving by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(Instant.now()) }
    val primary = Color(0xFF141821)
    val secondary = Color(0xFF667085)
    val zone = ZoneId.systemDefault()
    val dateFormat = remember { DateTimeFormatter.ofPattern("MM月dd日 HH:mm", Locale.CHINA) }

    // 定期更新当前时间，用于区分将来计划和因系统约束尚未执行的过期计划。
    LaunchedEffect(Unit) {
        while (true) {
            now = Instant.now()
            delay(30_000)
        }
    }

    fun feedback(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun persist(action: suspend () -> Unit) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                snackbar.showSnackbar("保存失败，请稍后重试")
            } finally {
                saving = false
            }
        }
    }

    fun saveTimes(times: List<Int>) {
        when {
            times.isEmpty() -> feedback("请至少保留一个刷新时间")
            times.size > 8 -> feedback("最多设置 8 个刷新时间")
            times.distinct().size != times.size -> feedback("该时间已存在，请选择其他时间")
            !validateRefreshTimes(times) -> feedback("相邻时间至少间隔 15 分钟，包括跨午夜的时间")
            else -> persist { store.setTimes(times.sorted()) }
        }
    }

    fun pickTime(previous: Int?) {
        val initial = previous ?: 7 * 60
        TimePickerDialog(
            context,
            { _, hour, minute ->
                // 先替换原项再统一校验，重复或过近的时间不会进入持久化配置。
                val updated = if (previous == null) config.times else config.times.filterNot { it == previous }
                saveTimes(updated + (hour * 60 + minute))
            },
            initial / 60,
            initial % 60,
            true
        ).show()
    }

    fun formatTimestamp(value: Long): String =
        if (value > 0) dateFormat.format(Instant.ofEpochMilli(value).atZone(zone)) else "尚无记录"

    Box(Modifier.fillMaxSize().background(Color(0xFFF6F4EF))) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Surface(Modifier.fillMaxWidth(), color = Color(0xFFFFFEFB), shape = RoundedCornerShape(14.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("定时刷新", color = primary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text("按每天的固定时间更新教务数据", color = secondary, fontSize = 12.sp)
                    }
                    Switch(
                        checked = config.enabled,
                        enabled = !saving,
                        onCheckedChange = { enabled -> persist { store.setEnabled(enabled) } }
                    )
                }
            }
            Text("每天刷新时间", color = secondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Surface(Modifier.fillMaxWidth(), color = Color(0xFFFFFEFB), shape = RoundedCornerShape(14.dp)) {
                Column {
                    // 关闭只暂停排程，时间仍可编辑并保留，下次开启即可继续使用。
                    config.times.sorted().forEachIndexed { index, minute ->
                        if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Color(0xFFEDE8DE))
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60),
                                color = primary,
                                fontSize = 20.sp,
                                modifier = Modifier.weight(1f).clickable(enabled = !saving) { pickTime(minute) }.padding(vertical = 16.dp)
                            )
                            TextButton(enabled = !saving, onClick = { pickTime(minute) }) { Text("编辑") }
                            TextButton(enabled = !saving && config.times.size > 1, onClick = { saveTimes(config.times - minute) }) { Text("删除") }
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Color(0xFFEDE8DE))
                    TextButton(
                        enabled = !saving && config.times.size < 8,
                        onClick = { pickTime(null) },
                        modifier = Modifier.fillMaxWidth().padding(4.dp)
                    ) { Text(if (config.times.size >= 8) "已达到 8 个时间上限" else "添加时间") }
                }
            }
            Text("最多 8 个时间，相邻时间至少间隔 15 分钟（含跨午夜）。关闭后仍保留以上时间。", color = secondary, fontSize = 12.sp)
            Text("运行情况", color = secondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Surface(Modifier.fillMaxWidth(), color = Color(0xFFFFFEFB), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("最近结果：${config.status}", color = primary, fontSize = 14.sp)
                    Text("最近运行：${formatTimestamp(config.lastAttempt)}", color = secondary, fontSize = 13.sp)
                    Text("最近成功：${formatTimestamp(config.lastSuccess)}", color = secondary, fontSize = 13.sp)
                    // 以系统登记记录为准，不能自行把尚未执行的计划跳到下一时刻。
                    val work = workInfos.firstOrNull { !it.state.isFinished }
                    val plannedAt = work?.nextScheduleTimeMillis?.takeIf { it in 1 until Long.MAX_VALUE }
                    val next = when {
                        !config.enabled -> "未开启"
                        work?.state == WorkInfo.State.RUNNING -> "正在执行"
                        plannedAt == null -> "尚未安排，请确认已导入桂林本科课表"
                        plannedAt <= now.toEpochMilli() -> "已到计划时间，等待系统执行"
                        else -> formatTimestamp(plannedAt)
                    }
                    Text("下次计划：$next", color = primary, fontSize = 14.sp)
                }
            }
            Text(
                "系统可能因省电策略或网络状态延后执行，计划时间不保证准点。强行停止应用后，需重新打开应用才能恢复定时刷新。",
                color = secondary,
                fontSize = 12.sp
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}
