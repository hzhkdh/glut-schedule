package com.glut.schedule.service.chaoxing

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
 * 列表不提供本人参与标记，因此状态保守标为“待确认”，不误归入未交。
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
        HomeworkEntry(title, "待确认", null, taskId = id, bbsId = bbsId)
    }
}

/** 结构暂不支持而不是同步失败：保留该类旧缓存，但不能影响整门课的成功时间。 */
class LearningTaskUnsupportedException(message: String) : IllegalStateException(message)

/** 当前仅验证了空考试结构；非空页面拒绝猜测，保留上次缓存。 */
fun parseLearningExamList(html: String): List<HomeworkEntry> {
    val document = Jsoup.parse(html)
    val empty = document.select(".exam-list .null-data,.exam-list .no-data,.examList .null-data,.examList .no-data,#examList .null-data,#examList .no-data")
        .any { it.text().trim() in listOf("暂无考试", "暂无考试任务") }
    if (!empty) throw LearningTaskUnsupportedException("考试列表结构暂不支持")
    return emptyList()
}

/** 只展示教师正文，删除输入控件及脚本，详情中不会出现提交或开始考试操作。 */
fun sanitizeLearningTaskDetail(content: String): String = Jsoup.clean(content, "https://groupweb.chaoxing.com/",
    org.jsoup.safety.Safelist.basicWithImages().addProtocols("a", "href", "https").addProtocols("img", "src", "https"))
