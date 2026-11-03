package com.glut.schedule.service.chaoxing

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class CachedHomework(val courseId: String, val classId: String, val courseName: String, val title: String, val status: String, val deadline: Long?, val workId: String = "", val cpi: String = "")

class HomeworkStore(context: Context) {
    private val preferences = context.getSharedPreferences("chaoxing_homework", Context.MODE_PRIVATE)
    fun read(): List<CachedHomework> = runCatching {
        val array = JSONArray(preferences.getString("items", "[]"))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            CachedHomework(o.getString("courseId"), o.getString("classId"), o.getString("courseName"),
                o.getString("title"), o.getString("status"), if (o.isNull("deadline")) null else o.getLong("deadline"), o.optString("workId"), o.optString("cpi"))
        }
    }.getOrDefault(emptyList())
    val connected: Boolean get() = preferences.getBoolean("connected", updatedAt > 0L)
    fun markConnected() { preferences.edit().putBoolean("connected", true).apply() }
    // 尝试与成功分开计时，全部失败不能把旧作业标成刚更新。
    val attemptAt: Long get() = preferences.getLong("attemptAt", 0L)
    val successAt: Long get() = preferences.getLong("successAt", if (failureCount > 0) 0L else updatedAt)
    fun markAttempt() { preferences.edit().putLong("attemptAt", System.currentTimeMillis()).apply() }
    private fun detailKey(row: CachedHomework) = "detail-requirements-v2:${row.courseId}:${row.classId}:${row.workId}"
    fun detail(row: CachedHomework): String = preferences.getString(detailKey(row), "").orEmpty()
    val generation: Long get() = preferences.getLong("generation", 0L)
    fun saveDetail(row: CachedHomework, html: String, expectedGeneration: Long): Boolean = synchronized(detailLock) {
        if (generation != expectedGeneration) return@synchronized false
        preferences.edit().putString(detailKey(row), html).apply()
        true
    }
    val updatedAt: Long get() = preferences.getLong("updatedAt", 0L)
    val failureCount: Int get() = preferences.getInt("failureCount", 0)
    val failureSummary: String get() = preferences.getString("failureSummary", "").orEmpty()
    val lastError: String get() = preferences.getString("lastError", "").orEmpty()
    fun saveError(error: String) { preferences.edit().putString("lastError", error).apply() }

    @Synchronized fun merge(result: HomeworkSyncResult) {
        // 查询失败的课程沿用旧缓存；成功课程的结果整体替换。
        val failed = result.failed.map { it.id to it.classId }.toSet()
        val merged = read().filter { (it.courseId to it.classId) in failed } + result.courses.flatMap { group ->
            group.homework.map { CachedHomework(group.course.id, group.course.classId, group.course.title, it.title, it.status, it.deadlineMillis, it.workId, group.course.cpi) }
        }
        val array = JSONArray()
        merged.forEach { row -> array.put(JSONObject().put("courseId", row.courseId).put("classId", row.classId)
            .put("courseName", row.courseName).put("title", row.title).put("status", row.status)
            .put("workId", row.workId).put("cpi", row.cpi).put("deadline", row.deadline ?: JSONObject.NULL)) }
        preferences.edit().putString("items", array.toString()).apply {
            if (result.courses.isNotEmpty() || result.failed.isEmpty()) putLong("updatedAt", System.currentTimeMillis())
            if (result.failed.isEmpty()) putLong("successAt", System.currentTimeMillis())
        }
            .putInt("failureCount", result.failed.size)
            .putString("failureSummary", result.failureReasons.entries.joinToString("；") { "${it.key}：${it.value} 门" })
            .putString("lastError", "").apply()
    }
    // clear 与详情写回共用进程锁和持久代次，阻止退出后的旧请求重新生成缓存。
    fun clear() = synchronized(detailLock) {
        val next = generation + 1L
        preferences.edit().clear().putLong("generation", next).apply()
    }
    private companion object { val detailLock = Any() }
}
