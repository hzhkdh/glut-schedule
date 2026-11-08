package com.glut.schedule.service.refresh

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.scheduledRefreshDataStore by preferencesDataStore(name = "scheduled_refresh")

/** 时刻使用本地午夜起的分钟数，运行记录与排程配置分开保存。 */
data class ScheduledRefreshConfig(
    val enabled: Boolean = false,
    val times: List<Int> = listOf(420, 1080),
    val lastAttempt: Long = 0,
    val lastSuccess: Long = 0,
    val status: String = "尚未执行",
    val plans: List<ScheduledRefreshPlan> = times.map { ScheduledRefreshPlan("legacy-$it", it, setOf(RefreshTaskId.SCHEDULE, RefreshTaskId.EXAMS)) },
    val runs: List<ScheduledRefreshRun> = emptyList(),
    val learningMigrationPending: Boolean = false,
    val revision: Long = 0
)

class ScheduledRefreshStore(context: Context) {
    private val homework = com.glut.schedule.service.chaoxing.HomeworkStore(context.applicationContext)
    private val dataStore = context.applicationContext.scheduledRefreshDataStore
    private val plansKey = stringPreferencesKey("plans_v2")
    private val runsKey = stringPreferencesKey("runs_v2")
    private val migrationKey = booleanPreferencesKey("learning_migration_seen")
    private val enabledKey = booleanPreferencesKey("enabled")
    private val timesKey = stringPreferencesKey("times")
    private val lastAttemptKey = longPreferencesKey("last_attempt")
    private val lastSuccessKey = longPreferencesKey("last_success")
    private val ownerKey = stringPreferencesKey("owner")
    private val statusKey = stringPreferencesKey("status")
    private val revisionKey = longPreferencesKey("configuration_revision")

    val config: Flow<ScheduledRefreshConfig> = dataStore.data.map { preferences ->
        val defaults = ScheduledRefreshConfig()
        // 只在旧值或损坏值不可用时回退；正常写入一律先严格校验。
        val storedParts = preferences[timesKey]?.split(',')
        val storedTimes = storedParts?.mapNotNull(String::toIntOrNull)
        val times = storedTimes?.takeIf { values ->
            values.size == storedParts?.size && validateRefreshTimes(values)
        }?.sorted() ?: defaults.times
        val rawPlans = preferences[plansKey]
        val storedPlans = rawPlans?.let(::decodePlans)?.takeIf(::validateRefreshPlans)
        val invalidPlans = rawPlans != null && storedPlans == null
        val plans = storedPlans ?: migrateLegacyRefreshPlans(times)
        ScheduledRefreshConfig(
            enabled = (preferences[enabledKey] ?: defaults.enabled) && !invalidPlans,
            times = plans.map { it.minuteOfDay },
            plans = plans,
            runs = decodeRuns(preferences[runsKey].orEmpty()),
            learningMigrationPending = homework.connected && !(preferences[migrationKey] ?: false),
            lastAttempt = preferences[lastAttemptKey] ?: defaults.lastAttempt,
            lastSuccess = preferences[lastSuccessKey] ?: defaults.lastSuccess,
            status = if (invalidPlans) "计划配置不可用，请重新编辑保存" else preferences[statusKey] ?: defaults.status,
            revision = preferences[revisionKey] ?: 0
        )
    }

    /** 只保存身份摘要；换号时关闭旧配置并清除运行记录，时刻偏好保留。 */
    private fun ownerDigest(identity: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }

    suspend fun bindOwner(identity: String) {
        val digest = ownerDigest(identity)
        dataStore.edit { preferences ->
            if (preferences[ownerKey] != digest) {
                preferences[ownerKey] = digest
                preferences[revisionKey] = (preferences[revisionKey] ?: 0) + 1
                // 统一入口跨来源使用：换教务账号仅清理摘要，不关闭学习通计划。
                preferences.remove(lastAttemptKey)
                preferences.remove(lastSuccessKey)
                preferences.remove(statusKey)
            }
        }
    }

    /** 重置应用时恢复全部默认配置。 */
    suspend fun clearAll() { dataStore.edit { it.clear() } }

    suspend fun setEnabled(enabled: Boolean) {
        // 关闭只改变开关，保留用户已设置的全部时刻。
        dataStore.edit {
            if ((it[enabledKey] ?: false) != enabled) it[revisionKey] = (it[revisionKey] ?: 0) + 1
            it[enabledKey] = enabled
        }
    }

    suspend fun setPlans(plans: List<ScheduledRefreshPlan>) {
        require(validateRefreshPlans(plans)) { "请选择有效时间及至少一项刷新内容" }
        dataStore.edit {
            val encoded = encodePlans(plans.sortedBy { p -> p.minuteOfDay })
            if (it[plansKey] != encoded) it[revisionKey] = (it[revisionKey] ?: 0) + 1
            it[plansKey] = encoded
        }
    }

    suspend fun dismissLearningMigration() { dataStore.edit { it[migrationKey] = true } }

    suspend fun recordRun(run: ScheduledRefreshRun) {
        dataStore.edit { prefs ->
            // 使用稳定运行标识原位替换，执行中和完成后的记录始终属于同一轮。
            val history = (listOf(run) + decodeRuns(prefs[runsKey].orEmpty()).filterNot { it.id == run.id }).take(20)
            prefs[runsKey] = encodeRuns(history)
            prefs[lastAttemptKey] = run.startedAt
            val summary = scheduledRefreshRunSummary(run)
            prefs[statusKey] = "${summary.title}，${summary.successCount}/${summary.totalCount} 项成功"
            if (summary.status == ScheduledRefreshRunStatus.FULL_SUCCESS) prefs[lastSuccessKey] = run.finishedAt
        }
    }

    /** 查询 WorkManager 期间若任务已写入新进度，不覆盖较新的生命周期。 */
    suspend fun markInterruptedIfUnchanged(run: ScheduledRefreshRun, stopReason: Int? = null) {
        dataStore.edit { prefs ->
            val history = decodeRuns(prefs[runsKey].orEmpty())
            if (history.firstOrNull { it.id == run.id } != run) return@edit
            val interrupted = run.copy(state = ScheduledRefreshRunState.INTERRUPTED, activeTask = null,
                reason = "系统结束了后台任务，等待检查恢复条件", stopReason = stopReason)
            prefs[runsKey] = encodeRuns(history.map { if (it.id == run.id) interrupted else it })
            if (history.firstOrNull()?.id == run.id) {
                val summary = scheduledRefreshRunSummary(interrupted)
                prefs[statusKey] = "${summary.title}，${summary.successCount}/${summary.totalCount} 项成功"
            }
        }
    }

    suspend fun setTimes(times: List<Int>) {
        val sorted = times.sorted()
        require(validateRefreshTimes(sorted)) { "请选择 1 至 3 个不重复且间隔至少 15 分钟的有效时刻" }
        val old = config.first()
        setPlans(sorted.map { time -> old.plans.firstOrNull { it.minuteOfDay == time }
            ?: ScheduledRefreshPlan(java.util.UUID.randomUUID().toString(), time, setOf(RefreshTaskId.SCHEDULE, RefreshTaskId.EXAMS)) })
    }

    suspend fun recordAttempt(at: Long, expectedOwner: String? = null) {
        dataStore.edit {
            if (expectedOwner != null && it[ownerKey] != ownerDigest(expectedOwner)) return@edit
            it[lastAttemptKey] = at
            it[statusKey] = "正在执行所选刷新任务"
        }
    }

    suspend fun recordResult(at: Long, success: Boolean, status: String, expectedOwner: String? = null) {
        dataStore.edit { preferences ->
            // 身份摘要在同一事务中比较，旧任务不能污染新账号的运行记录。
            if (expectedOwner != null && preferences[ownerKey] != ownerDigest(expectedOwner)) return@edit
            // 失败不抹去上次成功时间，界面可以同时展示最近尝试和最近成功。
            if (success) preferences[lastSuccessKey] = at
            preferences[statusKey] = status
        }
    }
}

/** JSON 只保存计划及固定结果文案，不保存账号、Cookie 或远端正文。 */
internal fun encodePlans(plans: List<ScheduledRefreshPlan>): String = JSONArray().apply {
    plans.forEach { put(JSONObject().put("id", it.id).put("minute", it.minuteOfDay)
        .put("tasks", JSONArray(it.taskIds.map { task -> task.name }))) }
}.toString()
internal fun decodePlans(raw: String): List<ScheduledRefreshPlan> = runCatching {
    val array = JSONArray(raw)
    (0 until array.length()).map { i -> val p = array.getJSONObject(i); val tasks = p.getJSONArray("tasks")
        ScheduledRefreshPlan(p.getString("id"), p.getInt("minute"), (0 until tasks.length()).mapNotNull {
            index -> RefreshTaskId.entries.firstOrNull { it.name == tasks.getString(index) } }.toSet()) }
}.getOrDefault(emptyList())
internal fun encodeRuns(runs: List<ScheduledRefreshRun>): String = JSONArray().apply {
    runs.forEach { run -> put(JSONObject().put("id", run.id).put("plan", run.planId).put("start", run.startedAt)
        .put("end", run.finishedAt).put("minute", run.minuteOfDay).put("state", run.state.name)
        .put("tasks", JSONArray(run.taskIds.map { it.name })).put("work", run.workId)
        .put("scheduledAt", run.scheduledAt).put("attempt", run.attempt).put("duration", run.durationMillis)
        .put("reason", run.reason).put("stopReason", run.stopReason).put("configuration", run.configurationKey)
        .put("dependencies", JSONArray().apply { run.dependencyResults.forEach { r ->
            put(JSONObject().put("task", r.taskId.name).put("status", r.status.name).put("message", r.message)) } })
        .put("active", run.activeTask?.name).put("results", JSONArray().apply { run.results.forEach { r ->
            put(JSONObject().put("task", r.taskId.name).put("status", r.status.name).put("message", r.message)
                .put("retryable", r.retryable)) } })) }
}.toString()
internal fun decodeRuns(raw: String): List<ScheduledRefreshRun> = runCatching {
    val array = JSONArray(raw)
    (0 until array.length()).take(20).mapNotNull { i -> runCatching {
        val r = array.getJSONObject(i); val results = r.getJSONArray("results")
        val decodedResults = (0 until results.length()).map { n -> val item = results.getJSONObject(n)
                ScheduledRefreshTaskResult(RefreshTaskId.valueOf(item.getString("task")),
                    RefreshTaskStatus.valueOf(item.getString("status")), item.getString("message"), item.optBoolean("retryable")) }
        val tasks = r.optJSONArray("tasks")
        ScheduledRefreshRun(r.getString("id"), r.getString("plan"), r.getLong("start"), r.getLong("end"),
            decodedResults, minuteOfDay = if (r.has("minute") && !r.isNull("minute")) r.getInt("minute") else null,
            taskIds = tasks?.let { values -> (0 until values.length()).mapNotNull { n ->
                RefreshTaskId.entries.firstOrNull { it.name == values.getString(n) } }.toSet()
            } ?: decodedResults.map { it.taskId }.toSet(),
            state = ScheduledRefreshRunState.valueOf(r.optString("state", ScheduledRefreshRunState.FINISHED.name)),
            workId = if (r.isNull("work")) null else r.optString("work").takeIf { it.isNotBlank() },
            activeTask = RefreshTaskId.entries.firstOrNull { it.name == r.optString("active") },
            scheduledAt = r.optLong("scheduledAt"), attempt = r.optInt("attempt", 1),
            durationMillis = r.optLong("duration"), reason = r.optString("reason"),
            stopReason = if (r.has("stopReason") && !r.isNull("stopReason")) r.getInt("stopReason") else null,
            configurationKey = r.optString("configuration"),
            dependencyResults = r.optJSONArray("dependencies")?.let { deps ->
                (0 until deps.length()).map { n -> val item = deps.getJSONObject(n)
                    ScheduledRefreshTaskResult(RefreshTaskId.valueOf(item.getString("task")),
                        RefreshTaskStatus.valueOf(item.getString("status")), item.getString("message")) }
            }.orEmpty())
    }.getOrNull() }
}.getOrDefault(emptyList())

/** 旧计划只继承已启用的教务内容，不自动为用户勾选新来源。 */
internal fun migrateLegacyRefreshPlans(times: List<Int>): List<ScheduledRefreshPlan> = times.map {
    ScheduledRefreshPlan("legacy-$it", it, setOf(RefreshTaskId.SCHEDULE, RefreshTaskId.EXAMS))
}
