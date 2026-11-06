package com.glut.schedule.service.refresh

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.scheduledRefreshDataStore by preferencesDataStore(name = "scheduled_refresh")

/** 时刻使用本地午夜起的分钟数，运行记录与排程配置分开保存。 */
data class ScheduledRefreshConfig(
    val enabled: Boolean = false,
    val times: List<Int> = listOf(420, 1080),
    val lastAttempt: Long = 0,
    val lastSuccess: Long = 0,
    val status: String = "尚未执行"
)

class ScheduledRefreshStore(context: Context) {
    private val dataStore = context.applicationContext.scheduledRefreshDataStore
    private val enabledKey = booleanPreferencesKey("enabled")
    private val timesKey = stringPreferencesKey("times")
    private val lastAttemptKey = longPreferencesKey("last_attempt")
    private val lastSuccessKey = longPreferencesKey("last_success")
    private val ownerKey = stringPreferencesKey("owner")
    private val statusKey = stringPreferencesKey("status")

    val config: Flow<ScheduledRefreshConfig> = dataStore.data.map { preferences ->
        val defaults = ScheduledRefreshConfig()
        // 只在旧值或损坏值不可用时回退；正常写入一律先严格校验。
        val storedParts = preferences[timesKey]?.split(',')
        val storedTimes = storedParts?.mapNotNull(String::toIntOrNull)
        val times = storedTimes?.takeIf { values ->
            values.size == storedParts?.size && validateRefreshTimes(values)
        }?.sorted() ?: defaults.times
        ScheduledRefreshConfig(
            enabled = preferences[enabledKey] ?: defaults.enabled,
            times = times,
            lastAttempt = preferences[lastAttemptKey] ?: defaults.lastAttempt,
            lastSuccess = preferences[lastSuccessKey] ?: defaults.lastSuccess,
            status = preferences[statusKey] ?: defaults.status
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
                preferences[enabledKey] = false
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
        dataStore.edit { it[enabledKey] = enabled }
    }

    suspend fun setTimes(times: List<Int>) {
        val sorted = times.sorted()
        require(validateRefreshTimes(sorted)) { "请选择 1 至 8 个不重复且间隔至少 15 分钟的有效时刻" }
        dataStore.edit { it[timesKey] = sorted.joinToString(",") }
    }

    suspend fun recordAttempt(at: Long, expectedOwner: String? = null) {
        dataStore.edit {
            if (expectedOwner != null && it[ownerKey] != ownerDigest(expectedOwner)) return@edit
            it[lastAttemptKey] = at
            it[statusKey] = "正在同步课表与考试"
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
