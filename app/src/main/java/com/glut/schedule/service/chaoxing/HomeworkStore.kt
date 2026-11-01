package com.glut.schedule.service.chaoxing

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class CachedHomework(val courseId: String, val classId: String, val courseName: String, val title: String, val status: String, val deadline: Long?)

class HomeworkStore(context: Context) {
    private val preferences = context.getSharedPreferences("chaoxing_homework", Context.MODE_PRIVATE)
    fun read(): List<CachedHomework> = runCatching {
        val array = JSONArray(preferences.getString("items", "[]"))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            CachedHomework(o.getString("courseId"), o.getString("classId"), o.getString("courseName"),
                o.getString("title"), o.getString("status"), if (o.isNull("deadline")) null else o.getLong("deadline"))
        }
    }.getOrDefault(emptyList())
    val updatedAt: Long get() = preferences.getLong("updatedAt", 0L)
    val failureCount: Int get() = preferences.getInt("failureCount", 0)
    val failureSummary: String get() = preferences.getString("failureSummary", "").orEmpty()
    val lastError: String get() = preferences.getString("lastError", "").orEmpty()
    fun saveError(error: String) { preferences.edit().putString("lastError", error).apply() }

    @Synchronized fun merge(result: HomeworkSyncResult) {
        // 查询失败的课程沿用旧缓存；成功课程的结果整体替换。
        val failed = result.failed.map { it.id to it.classId }.toSet()
        val merged = read().filter { (it.courseId to it.classId) in failed } + result.courses.flatMap { group ->
            group.homework.map { CachedHomework(group.course.id, group.course.classId, group.course.title, it.title, it.status, it.deadlineMillis) }
        }
        val array = JSONArray()
        merged.forEach { row -> array.put(JSONObject().put("courseId", row.courseId).put("classId", row.classId)
            .put("courseName", row.courseName).put("title", row.title).put("status", row.status)
            .put("deadline", row.deadline ?: JSONObject.NULL)) }
        preferences.edit().putString("items", array.toString()).putLong("updatedAt", System.currentTimeMillis())
            .putInt("failureCount", result.failed.size)
            .putString("failureSummary", result.failureReasons.entries.joinToString("；") { "${it.key}：${it.value} 门" })
            .putString("lastError", "").apply()
    }
    fun clear() { preferences.edit().clear().apply() }
}
