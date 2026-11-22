package com.glut.schedule.service.chaoxing

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// 新字段均有默认值，历史作业缓存和原有调用无需迁移即可继续读取。
data class CachedHomework(val courseId: String, val classId: String, val courseName: String, val title: String, val status: String, val deadline: Long?, val workId: String = "", val cpi: String = "", val kind: String = "homework", val taskId: String = workId, val bbsId: String = "")

class HomeworkStore(context: Context) {
    private val preferences = context.getSharedPreferences("chaoxing_homework", Context.MODE_PRIVATE)
    fun read(): List<CachedHomework> = runCatching {
        val array = JSONArray(preferences.getString("items", "[]"))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            CachedHomework(o.getString("courseId"), o.getString("classId"), o.getString("courseName"),
                o.getString("title"), o.getString("status"), if (o.isNull("deadline")) null else o.getLong("deadline"), o.optString("workId"), o.optString("cpi"), o.optString("kind", "homework"), o.optString("taskId", o.optString("workId")), o.optString("bbsId"))
        }
    }.getOrDefault(emptyList())
    val connected: Boolean get() = preferences.getBoolean("connected", updatedAt > 0L)
    fun markConnected() { preferences.edit().putBoolean("connected", true).apply() }
    // 尝试与成功分开计时，全部失败不能把旧作业标成刚更新。
    val attemptAt: Long get() = preferences.getLong("attemptAt", 0L)
    val successAt: Long get() = preferences.getLong("successAt", if (failureCount > 0) 0L else updatedAt)
    fun markAttempt() { preferences.edit().putLong("attemptAt", System.currentTimeMillis()).apply() }
    private fun detailKey(row: CachedHomework, homeworkVersion: Int = 4) =
        if (row.kind == "homework") "detail-requirements-v$homeworkVersion:${row.courseId}:${row.classId}:${row.workId}" else "task-detail:${row.taskKey()}"
    internal fun detail(row: CachedHomework): HomeworkDetails {
        val key = detailKey(row)
        preferences.getString(key, null)?.let { html ->
            val documents = runCatching {
                val values = JSONArray(preferences.getString("$key:documents", "[]"))
                (0 until values.length()).mapNotNull { index ->
                    val item = values.getJSONObject(index)
                    homeworkDocument(item.getString("id"), item.getString("name"), item.getString("type"), item.getBoolean("download"))
                }
            }.getOrDefault(emptyList())
            return HomeworkDetails(html, documents)
        }
        if (row.kind != "homework") return HomeworkDetails("")
        // 旧正文可离线显示，首次展开仍会重新请求以补上附件预览入口。
        preferences.getString(detailKey(row, 3), null)?.let { return HomeworkDetails(it) }
        val previous = preferences.getString(detailKey(row, 2), "").orEmpty()
        val legacyTypeOnly = Regex("""^[（(](?:其它|其他)[）)]$""")
        return HomeworkDetails(previous.takeUnless { legacyTypeOnly.matches(org.jsoup.Jsoup.parseBodyFragment(it).text()) }.orEmpty())
    }
    // 手动刷新详情的批次与账号会话代次独立，避免自动同步重复拉取正文。
    val detailRevision: Long get() = preferences.getLong("detailRevision", 0L)
    fun requestDetailRefresh() { preferences.edit().putBoolean("detailRefreshPending", true).apply() }
    fun finishDetailRefresh() = synchronized(detailLock) {
        if (preferences.getBoolean("detailRefreshPending", false)) preferences.edit()
            .putLong("detailRevision", detailRevision + 1).putBoolean("detailRefreshPending", false).apply()
    }
    fun detailIsStale(row: CachedHomework) = preferences.getLong(detailKey(row) + ":revision", -1L) < detailRevision
    val generation: Long get() = preferences.getLong("generation", 0L)
    internal fun saveDetail(row: CachedHomework, detail: HomeworkDetails, expectedGeneration: Long, expectedRevision: Long = detailRevision): Boolean = synchronized(detailLock) {
        if (generation != expectedGeneration || detailRevision != expectedRevision || !allows(row.subjectKey())) return@synchronized false
        val key = detailKey(row)
        preferences.edit().putString(key, detail.html).putLong("$key:revision", expectedRevision).apply {
            if (row.kind == "homework") {
                // 仅保存教师附件描述；预览地址、签名及原文件不写入详情缓存。
                val documents = detail.documents.map { item ->
                    JSONObject().put("id", item.objectId).put("name", item.name)
                        .put("type", item.format.extension).put("download", item.downloadAllowed)
                }
                putString("$key:documents", JSONArray(documents).toString())
                remove(detailKey(row, 3))
                remove(detailKey(row, 3) + ":revision")
                remove(detailKey(row, 2))
                remove(detailKey(row, 2) + ":revision")
            }
        }.apply()
        true
    }
    val updatedAt: Long get() = preferences.getLong("updatedAt", 0L)
    // null 表示始终全选，空集合表示明确不选任何科目。
    val selectedCourseKeys: Set<String>? get() = preferences.getStringSet("selectedCourses", null)?.toSet()
    fun selectCourses(keys: Set<String>?) = synchronized(detailLock) {
        val editor = preferences.edit()
        if (keys == null) editor.remove("selectedCourses") else editor.putStringSet("selectedCourses", keys.toSet())
        editor.apply()
    }
    fun allows(key: String): Boolean = selectedCourseKeys?.contains(key) ?: true
    fun catalog(): List<ChaoxingCourse> {
        val raw = preferences.getString("catalog", null)
        if (raw != null) runCatching {
            val entries = JSONArray(raw)
            return (0 until entries.length()).map { entries.getJSONObject(it).toCourse() }
        }
        return read().distinctBy { it.subjectKey() }.map { ChaoxingCourse(it.courseId, it.classId, it.cpi, it.courseName) }
    }
    private fun JSONObject.toCourse() = ChaoxingCourse(getString("id"), getString("classId"), "", getString("title"))
    private fun ChaoxingCourse.toCatalogEntry() = JSONObject().put("id", id).put("classId", classId).put("title", title)
    private fun allIssues(): List<FailedCourseTask> = runCatching {
        val entries = JSONArray(preferences.getString("issues", "[]"))
        (0 until entries.length()).map {
            val entry = entries.getJSONObject(it)
            FailedCourseTask(entry.toCourse(), entry.getString("kind"), entry.getString("reason"))
        }
    }.getOrDefault(emptyList())
    fun issues(): List<FailedCourseTask> = allIssues().filter { allows(it.course.subjectKey()) }
    val failureCount: Int get() = if (preferences.contains("issues")) issues().map { it.course.subjectKey() }.distinct().size
        else if (selectedCourseKeys == null) preferences.getInt("failureCount", 0) else 0
    val failureSummary: String get() = preferences.getString("failureSummary", "").orEmpty()
    val lastError: String get() = preferences.getString("lastError", "").orEmpty()
    fun saveError(error: String) { preferences.edit().putString("lastError", error).apply() }

    fun merge(result: HomeworkSyncResult) = synchronized(detailLock) {
        val known = result.catalog?.map { it.subjectKey() }?.toSet()
        val loaded = result.courses.filter { allows(it.course.subjectKey()) }
        val replaced = loaded.map { it.course.subjectKey() to it.kind }.toSet()
        // 未选或失败的任务保留缓存，只剔除权威目录已不存在的课程。
        val merged = read().filter { row ->
            (known == null || row.subjectKey() in known) && (row.subjectKey() to row.kind) !in replaced
        } + loaded.flatMap { group ->
            group.homework.map { CachedHomework(group.course.id, group.course.classId, group.course.title, it.title, it.status, it.deadlineMillis, it.workId, group.course.cpi, group.kind, it.taskId, it.bbsId) }
        }
        val issues = (allIssues().filter {
            (known == null || it.course.subjectKey() in known) && (it.course.subjectKey() to it.kind) !in replaced
        } + result.failedTasks.filter { allows(it.course.subjectKey()) }).associateBy { it.course.subjectKey() to it.kind }.values
        val visibleIssues = issues.filter { allows(it.course.subjectKey()) }
        val array = JSONArray()
        merged.forEach { row -> array.put(JSONObject().put("courseId", row.courseId).put("classId", row.classId)
            .put("courseName", row.courseName).put("title", row.title).put("status", row.status)
            .put("workId", row.workId).put("cpi", row.cpi).put("kind", row.kind).put("taskId", row.taskId).put("bbsId", row.bbsId).put("deadline", row.deadline ?: JSONObject.NULL)) }
        preferences.edit().putString("items", array.toString()).apply {
            result.catalog?.let { putString("catalog", JSONArray(it.map { course -> course.toCatalogEntry() }).toString()) }
            if (loaded.isNotEmpty() || visibleIssues.isEmpty()) putLong("updatedAt", System.currentTimeMillis())
            if (visibleIssues.isEmpty()) putLong("successAt", System.currentTimeMillis())
        }
            .putString("issues", JSONArray(issues.map { it.course.toCatalogEntry().put("kind", it.kind).put("reason", it.reason) }).toString())
            .putInt("failureCount", visibleIssues.map { it.course.subjectKey() }.distinct().size)
            .putString("failureSummary", result.failureReasons.entries.joinToString("；") { "${it.key}：${it.value} 门" })
            .putString("lastError", "").apply()
    }
    /** 同步与退出共用代次检查，旧账号的列表响应不能在退出后重新填入。 */
    fun mergeIfGeneration(result: HomeworkSyncResult, expected: Long): Boolean = synchronized(detailLock) {
        if (generation != expected) return@synchronized false
        merge(result)
        true
    }
    // clear 与详情写回共用进程锁和持久代次，阻止退出后的旧请求重新生成缓存。
    fun clear() = synchronized(detailLock) {
        val next = generation + 1L
        preferences.edit().clear().putLong("generation", next).apply()
    }
    private companion object { val detailLock = Any() }
}
