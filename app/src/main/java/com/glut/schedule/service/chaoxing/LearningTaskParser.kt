package com.glut.schedule.service.chaoxing

import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/** 回复总数不是本人参与凭证，只有完整扫描后的阴性结果才能认定未讨论。 */
fun discussionParticipation(puid: String, authors: List<String>, complete: Boolean): String = when {
    puid.isBlank() -> "待确认"
    puid in authors -> "已讨论"
    complete -> "未讨论"
    else -> "待确认"
}

/**
 * 讨论列表页的教师标记在行属性里，接口 JSON 不含 isTeacher。
 * 静态列表仅在出现本人已回复标记时归为已讨论，其余保留待确认。
 */
fun parseDiscussionTopicList(html: String, bbsId: String): List<HomeworkEntry> {
    val document = Jsoup.parse(html)
    val rows = document.select(".dataBody_topic")
    if (rows.isEmpty()) {
        val text = document.text()
        check(text.contains("暂无讨论") || text.contains("暂无话题")) { "讨论列表无法识别" }
        return emptyList()
    }
    return rows.mapNotNull { row ->
        val id = row.attr("data-uuid").trim()
        check(id.isNotBlank()) { "讨论身份缺失" }
        if (row.attr("isteacher").lowercase() !in listOf("1", "true")) return@mapNotNull null
        val title = row.selectFirst(".topicli_title_text,.topicli_title,.topicli_link")?.text()?.trim()
            ?.ifBlank { "未命名讨论" } ?: "未命名讨论"
        val status = if (row.selectFirst(".topic_reply")?.text() == "已回复") "已讨论" else "待确认"
        HomeworkEntry(title, status, null, taskId = id, bbsId = bbsId)
    }
}

internal val discussionListPath = Regex("/course/topic/[\\w-]+/get(?:Top)?TopicList")
internal val discussionDetailPath = Regex("/course/topic/[\\w-]+/getTopic")

/** 已开始且有官方入口的作业，旧版会把这组状态显示为未交。 */
internal fun isReleasedHomeworkVariant(item: JSONObject, validLink: Boolean, now: Long): Boolean =
    validLink && item.optInt("stuStatus", -1) == 0 && item.optInt("taskStatus", -1) == 1 &&
        item.optInt("action", -1) == 1 && item.optInt("style", -1) == 1 && item.optLong("startTime") in 1..now

/** 核对讨论组和话题，详情仅展示官方返回的主题正文。 */
internal fun parseLearningDiscussionDetail(data: JSONObject, taskId: String, bbsId: String): String {
    val topic = data.optJSONObject("datas")
    require(data.opt("status") == true && topic != null && topic.optString("uuid") == taskId && topic.optString("bbsid") == bbsId &&
        (topic.opt("rtf_content") is String || topic.opt("text_content") is String)) {
        "讨论详情与当前话题不一致"
    }
    val rich = topic.optString("rtf_content").takeUnless { topic.isNull("rtf_content") }.orEmpty()
    if (rich.isNotBlank()) {
        val cleaned = sanitizeLearningTaskDetail(rich)
        val document = Jsoup.parseBodyFragment(cleaned)
        if (document.text().isNotBlank() || document.selectFirst("img") != null)
            return cleaned
    }
    val plain = topic.optString("text_content").takeUnless { topic.isNull("text_content") }.orEmpty()
    return if (plain.isBlank()) "<p>该话题暂无正文</p>" else plain.lines().joinToString("") {
        org.jsoup.nodes.Element("p").text(it).outerHtml()
    }
}

/** 仅提取课程配置字面量，按官网的班级参数和分页游标读取讨论。 */
internal fun loadLearningDiscussions(
    html: String, source: HttpUrl, courseId: String,
    requestPage: (HttpUrl, String) -> JSONObject
): List<HomeworkEntry> {
    val document = Jsoup.parse(html)
    val validId = Regex("[\\w-]+")
    val numeric = Regex("\\d+")
    val script = document.select("script").map { it.data() }
        .firstOrNull { Regex("""\b(?:window\.)?obj\s*=\s*\{""").containsMatchIn(it) }
    val urlBbsId = source.queryParameter("bbsid")
    if (script == null) {
        val rows = parseDiscussionTopicList(html, "")
        if (rows.isEmpty()) return emptyList()
        val bbsId = document.selectFirst("input#bbsid,input[name=bbsid]")?.attr("value").orEmpty().ifBlank { urlBbsId.orEmpty() }
        check(bbsId.matches(validId)) { "讨论入口无法识别" }
        return rows.map { it.copy(bbsId = bbsId) }.distinctBy { it.taskId }
    }
    fun field(name: String): String? = Regex(
        """(?:^|[,{])\s*["']?$name["']?\s*:\s*(?:"([\w,-]*)"|'([\w,-]*)'|(\d+)\b)"""
    ).find(script)?.let { match -> (1..3).firstNotNullOfOrNull { match.groups[it]?.value } }
    val managerArray = Regex("""(?:^|[,{])\s*["']?managers["']?\s*:\s*(\[(?:[^"\[\]]|"(?:\\.|[^"\\])*")*\])""")
        .find(script)?.groupValues?.get(1) ?: error("讨论教师名单无法识别")
    val managers = JSONArray(managerArray)
    val teachers = (0 until managers.length()).map { managers.getJSONObject(it).optString("puid") }.toSet()
    val bbsId = field("bbsid") ?: urlBbsId.orEmpty()
    check(bbsId.matches(validId) && (urlBbsId == null || urlBbsId == bbsId) &&
        field("courseId") == courseId && field("isManager") == "0" && teachers.all(numeric::matches)) { "讨论课程配置无法识别" }
    val classTag = field("classTag") ?: error("讨论班级范围无法识别")
    check(classTag.isEmpty() || classTag == "0" || classTag.matches(Regex("classId\\d+(,classId\\d+)*")))
    val tags = if (classTag.isEmpty() || classTag == "0") "" else "classId0000001,$classTag,courseId$courseId"
    fun getPage(action: String, params: Map<String, String> = emptyMap()): JSONObject {
        val url = source.newBuilder().encodedPath("/course/topic/$bbsId/$action").query(null)
            .addQueryParameter("courseId", courseId).addQueryParameter("folder_uuid", field("folder_uuid").orEmpty())
            .addQueryParameter("isSetTop", "")
        for (key in listOf("learnSilverStartTime", "learnSilverEndTime")) {
            url.addQueryParameter(key, field(key) ?: source.queryParameter(key).orEmpty())
        }
        params.forEach { (key, value) -> url.addQueryParameter(key, value) }
        return requestPage(url.build(), tags)
    }
    val rows = linkedMapOf<String, HomeworkEntry>()
    fun add(result: JSONObject): Int {
        check(result.opt("status") == true) { "讨论查询未完成" }
        val items = result.getJSONArray("datas")
        for (index in 0 until items.length()) {
            val row = items.getJSONObject(index)
            val author = row.optString("create_puid")
            check(author.matches(numeric)) { "讨论发布者无法识别" }
            if (author !in teachers) continue
            val id = row.optString("uuid")
            check(id.matches(validId)) { "讨论身份缺失" }
            // 官网学生页将缺省或零值视为未回复，异常值保留待确认。
            val time = row.optString("topicReplyTime").toLongOrNull()
            val status = when {
                time != null && time > 0 -> "已讨论"
                time == 0L || row.isNull("topicReplyTime") -> "未讨论"
                else -> "待确认"
            }
            rows[id] = HomeworkEntry(row.optString("title").ifBlank { "未命名讨论" }, status, null, taskId = id, bbsId = bbsId)
        }
        return items.length()
    }
    add(getPage("getTopTopicList"))
    var lastAuxValue = ""
    var lastReplyTime: String? = null
    for (page in 1..300) {
        val params = mutableMapOf("page" to page.toString(), "pageSize" to "20", "kw" to "",
            "selectedStartTime" to "", "selectedEndTime" to "", "selectedType" to "0", "lastAuxValue" to lastAuxValue)
        lastReplyTime?.let { params["last_reply_time"] = it }
        val result = getPage("getTopicList", params)
        val count = add(result)
        val paging = result.optJSONObject("poff")
        if (paging?.optString("lastPage") == "1") return rows.values.toList()
        val nextReplyTime = paging?.optString("lastValue").orEmpty()
        val nextAux = paging?.optString("lastAuxValue").orEmpty()
        check(count > 0 && nextReplyTime.matches(numeric) &&
            (nextReplyTime != lastReplyTime || nextAux != lastAuxValue)) { "讨论分页未完成" }
        lastReplyTime = nextReplyTime
        lastAuxValue = nextAux
    }
    error("讨论分页超限")
}

/** 未知结构由同步层尝试新版，仍失败时保留该类旧缓存。 */
class LearningTaskUnsupportedException(message: String) : IllegalStateException(message)

/** 从旧版已知考试行提取关系 ID，不调用行上的查看函数。 */
fun parseLearningExamList(html: String): List<HomeworkEntry> {
    val document = Jsoup.parse(html)
    val rows = document.select(".task-list .bottomList li")
    if (rows.isNotEmpty()) return rows.map { row ->
        val handler = row.selectFirst("[onclick]")?.attr("onclick").orEmpty()
        val taskId = Regex("""^\s*viewExamAnswer\(\s*['"]?(\d+)['"]?\s*,""").find(handler)?.groupValues?.get(1)
        val title = row.selectFirst(".right-content .overHidden2")?.text()?.trim()
        val original = row.selectFirst(".right-content .status")?.text()?.trim()
        if (taskId == null || title.isNullOrBlank() || original == null) throw LearningTaskUnsupportedException("考试列表结构暂不支持")
        val status = if (original == "未完成") "未开始" else original.ifBlank { "待确认" }
        HomeworkEntry(title, status, null, taskId = taskId)
    }.distinctBy { it.taskId }
    val empty = document.select(".exam-list .null-data,.exam-list .no-data,.examList .null-data,.examList .no-data,#examList .null-data,#examList .no-data")
        .any { it.text().trim() in listOf("暂无考试", "暂无考试任务") }
    if (!empty) throw LearningTaskUnsupportedException("考试列表结构暂不支持")
    return emptyList()
}

/** 只展示教师正文，删除输入控件及脚本，详情中不会出现提交或开始考试操作。 */
fun sanitizeLearningTaskDetail(content: String): String = Jsoup.clean(content, "https://groupweb.chaoxing.com/",
    org.jsoup.safety.Safelist.basicWithImages().addProtocols("a", "href", "https").addProtocols("img", "src", "https"))
