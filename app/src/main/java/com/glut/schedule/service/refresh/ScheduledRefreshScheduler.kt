package com.glut.schedule.service.refresh

import android.content.Context
import androidx.work.Data
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.glut.schedule.ScheduleApplication
import com.glut.schedule.service.chaoxing.HomeworkStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

const val UNIQUE_SCHEDULED_REFRESH = "academic_scheduled_refresh"
const val MAX_SCHEDULED_REFRESH_PLANS = 3
internal const val SCHEDULED_REFRESH_TAG = "scheduled_refresh_occurrence_v3"
private val schedulingMutex = Mutex()
internal suspend fun <T> withScheduledRefreshSchedulingLock(block: suspend () -> T): T = schedulingMutex.withLock { block() }

/** 包括午夜两侧在内，每两个相邻时刻至少相隔 WorkManager 要求的 15 分钟。 */
fun validateRefreshTimes(times: List<Int>): Boolean {
    if (times.size !in 1..MAX_SCHEDULED_REFRESH_PLANS || times.any { it !in 0..1439 } || times.distinct().size != times.size) return false
    val sorted = times.sorted()
    if (sorted.zipWithNext().any { (first, second) -> second - first < 15 }) return false
    return sorted.first() + 1440 - sorted.last() >= 15
}

/** 严格选择未来的本地固定时刻；同一时刻已到达时直接寻找下一个。 */
fun nextRefreshInstant(times: List<Int>, now: Instant, zone: ZoneId): Instant {
    require(validateRefreshTimes(times)) { "定时刷新时刻无效" }
    var date = now.atZone(zone).toLocalDate()
    while (true) {
        val candidates = times.map { minute ->
            // atZone 将夏令时缺口向后平移；重叠时使用较早偏移，每个本地时刻每天只运行一次。
            date.atTime(LocalTime.of(minute / 60, minute % 60)).atZone(zone).toInstant()
        }
        candidates.filter { it.isAfter(now) }.minOrNull()?.let { return it }
        date = date.plusDays(1)
    }
}

/** 每计划保留一个持久任务；KEEP 不会把已到期但等待网络的任务推到明天。 */
suspend fun reconcileScheduledRefresh(
    context: Context,
    config: ScheduledRefreshConfig,
    supported: Boolean,
    now: Instant = Instant.now()
) = schedulingMutex.withLock {
    // 等待排程锁时可能发生配置编辑，按锁内最新值撤销旧任务。
    val current = ScheduledRefreshStore(context).config.first()
    val manager = WorkManager.getInstance(context)
    val legacy = manager.getWorkInfosForUniqueWork(UNIQUE_SCHEDULED_REFRESH).get()
        .firstOrNull { !it.state.isFinished }
    // 旧周期任务只在迁移时取消；其已到期时间仍用于安排同轮，不丢弃待执行的今天。
    if (legacy != null) manager.cancelUniqueWork(UNIQUE_SCHEDULED_REFRESH).result.get()
    val active = manager.getWorkInfosByTag(SCHEDULED_REFRESH_TAG).get().filter { !it.state.isFinished }
    if (!current.enabled || !supported) {
        active.forEach { manager.cancelWorkById(it.id).result.get() }
        return@withLock
    }
    val source = scheduledRefreshSourceKey(context)
    val keys = current.plans.associateWith { scheduledRefreshConfigurationKey(current, it, source) }
    active.filter { work -> keys.values.none { "configuration:$it" in work.tags } }
        .forEach { manager.cancelWorkById(it.id).result.get() }
    val legacyDue = legacy?.nextScheduleTimeMillis?.takeIf { it > 0 && it <= now.toEpochMilli() &&
        now.toEpochMilli() - it <= SCHEDULED_REFRESH_RECOVERY_WINDOW &&
        // 已开始的旧轮次缺少配置身份，宁可明确中断，也不重复其已成功的数据写回。
        current.runs.none { run -> run.workId == legacy.id.toString() && run.startedAt >= it } }
    for ((plan, key) in keys) {
        val pending = active.any { "configuration:$key" in it.tags }
        if (pending) continue
        val resume = current.runs.firstOrNull { canResumeScheduledRun(it, plan, key, now.toEpochMilli()) }
        val due = resume?.scheduledAt ?: legacyDue?.takeIf {
            latestScheduledPlan(current.plans, Instant.ofEpochMilli(it), ZoneId.systemDefault()).first.id == plan.id
        } ?: nextRefreshInstant(listOf(plan.minuteOfDay), now, ZoneId.systemDefault()).toEpochMilli()
        enqueueScheduledOccurrence(context, plan, key, due, resume?.id, resumeAttempt = resume?.attempt ?: 0)
    }
}

/** 只散列来源身份和设置代次，不写入账号、Cookie 或凭证。 */
internal fun refreshIdentityDigest(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

internal suspend fun scheduledRefreshSourceKey(context: Context): String {
    val c = (context.applicationContext as ScheduleApplication).appContainer
    val session = c.academicSessionStore.profileSession.first()
    val homework = HomeworkStore(context)
    return refreshIdentityDigest("${session.studentNo}|${session.base}|${c.settingsStore.campusType.first()}|" +
        "${c.academicSessionStore.sessionAuthMethod.first()}|${homework.generation}")
}

/** 学习通退出会递增持久代次，来源变化即时撤销旧检查点与排队任务。 */
internal fun scheduledRefreshLearningIdentity(context: Context) = callbackFlow {
    val preferences = context.getSharedPreferences("chaoxing_homework", Context.MODE_PRIVATE)
    // connected 的旧值可由首次成功缓存推导，不能把正常同步误判成换号。
    fun identity(): String = HomeworkStore(context).generation.toString()
    val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == "generation") trySend(identity())
    }
    preferences.registerOnSharedPreferenceChangeListener(listener)
    trySend(identity())
    awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
}

internal fun scheduledRefreshConfigurationKey(config: ScheduledRefreshConfig, plan: ScheduledRefreshPlan,
    source: String): String = refreshIdentityDigest("${config.revision}|${plan.id}|${plan.minuteOfDay}|" +
    "${plan.taskIds.sortedBy { it.ordinal }}|$source|${ZoneId.systemDefault()}")

internal fun scheduledRefreshWorkName(planId: String): String = "$SCHEDULED_REFRESH_TAG-${refreshIdentityDigest(planId)}"

/** 正常后继追加到当前工作之后，恢复仍带同一个 runId 和 scheduledAt。 */
internal suspend fun enqueueScheduledOccurrence(context: Context, plan: ScheduledRefreshPlan, key: String,
    scheduledAt: Long, runId: String? = null, append: Boolean = false, resumeAttempt: Int = 0) {
    val manager = WorkManager.getInstance(context)
    val occurrence = scheduledOccurrenceTag(key, scheduledAt)
    val requestTag = "$occurrence:request:${runId ?: "initial"}:$resumeAttempt"
    val existing = manager.getWorkInfosByTag(occurrence).get()
    val recorded = ScheduledRefreshStore(context).config.first().runs.any {
        it.configurationKey == key && it.scheduledAt == scheduledAt }
    if (!shouldEnqueueScheduledOccurrence(runId != null, recorded,
            existing.map { ScheduledOccurrenceWorkSnapshot(it.tags, it.state.isFinished) }, requestTag)) return
    val request = OneTimeWorkRequestBuilder<ScheduledRefreshWorker>()
        .setInputData(Data.Builder().putString("planId", plan.id).putString("configurationKey", key)
            .putLong("scheduledAt", scheduledAt).putString("resumeRunId", runId).build())
        .addTag(SCHEDULED_REFRESH_TAG).addTag("configuration:$key")
        .addTag(occurrence).addTag(requestTag)
        // 重跑旧 Worker 时，已完成后继的身份也必须仍可查，避免同一到期轮次重复追加。
        .keepResultsForAtLeast(3, TimeUnit.DAYS)
        .setInitialDelay((scheduledAt - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
        .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
    manager.enqueueUniqueWork(scheduledRefreshWorkName(plan.id),
        if (append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, request).result.get()
}

internal fun scheduledOccurrenceTag(key: String, scheduledAt: Long): String = "occurrence:$key:$scheduledAt"

internal data class ScheduledOccurrenceWorkSnapshot(val tags: Set<String>, val finished: Boolean)

/** 普通后继按到期轮次幂等；恢复只允许原任务终止后登记一个指定代次的恢复请求。 */
internal fun shouldEnqueueScheduledOccurrence(recovery: Boolean, recorded: Boolean,
    works: List<ScheduledOccurrenceWorkSnapshot>, requestTag: String): Boolean =
    if (!recovery) !recorded && works.isEmpty()
    else works.none { !it.finished || requestTag in it.tags }

/** 长时间离线后只接续最新两小时内的当前时段，不展开全天历史补跑。 */
internal fun nextScheduledOccurrenceAfterRun(plan: ScheduledRefreshPlan, now: Instant, zone: ZoneId,
    previousScheduledAt: Long, knownOccurrences: Set<Long>): Long {
    val latest = latestScheduledPlan(listOf(plan), now, zone).second.toEpochMilli()
    return if (latest > previousScheduledAt && latest !in knownOccurrences &&
        now.toEpochMilli() - latest <= SCHEDULED_REFRESH_RECOVERY_WINDOW) latest
    else nextRefreshInstant(listOf(plan.minuteOfDay), now, zone).toEpochMilli()
}

/** 仅迁移旧任务时向前定位原到期计划，不把一次启动展开成全天补跑。 */
internal fun latestScheduledPlan(plans: List<ScheduledRefreshPlan>, now: Instant, zone: ZoneId): Pair<ScheduledRefreshPlan, Instant> =
    plans.flatMap { plan -> (0L..1L).map { days -> plan to now.atZone(zone).toLocalDate().minusDays(days)
        .atTime(LocalTime.of(plan.minuteOfDay / 60, plan.minuteOfDay % 60)).atZone(zone).toInstant() } }
        .filter { !it.second.isAfter(now) }.maxBy { it.second }

/** 同时返回原计划与执行时刻，夏令时缺口平移后不能用时钟分钟反查计划。 */
internal fun nextScheduledPlan(plans: List<ScheduledRefreshPlan>, now: Instant, zone: ZoneId): Pair<ScheduledRefreshPlan, Instant> {
    require(validateRefreshPlans(plans))
    return plans.map { it to nextRefreshInstant(listOf(it.minuteOfDay), now, zone) }.minBy { it.second }
}
