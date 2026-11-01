package com.glut.schedule.service.chaoxing

import org.jsoup.Jsoup

data class HomeworkEntry(val title: String, val status: String, val deadlineMillis: Long?)

// 学习通的合法空页面没有 ul，仅在任务列表容器内显示明确的“暂无作业”。
// 限定容器和文字，避免把错误页中偶然出现的空状态文案当作成功。
fun hasRecognizableHomeworkList(html: String): Boolean {
    val document = Jsoup.parse(html)
    if (document.selectFirst("ul") != null) return true
    return document.select(".task-list .has-content .null-data").any { it.text().trim() == "暂无作业" }
}

// 学习通列表只提供剩余时间，转换后的时间精度受原始倒计时精度限制。
fun parseRemainingMinutes(text: String): Long? {
    if (!text.contains("剩余")) return null
    val parts = listOf("天" to 1440L, "小时" to 60L, "分" to 1L)
    val matches = parts.mapNotNull { (unit, factor) ->
        Regex("(\\d+)\\s*$unit").find(text)?.groupValues?.get(1)?.toLongOrNull()?.times(factor)
    }
    return if (matches.isEmpty()) null else matches.sum()
}

fun parseHomeworkList(html: String, nowMillis: Long): List<HomeworkEntry> {
    val document = Jsoup.parse(html)
    return document.select("ul li[aria-label]").mapNotNull { item ->
        val label = item.attr("aria-label")
        val title = label.substringBeforeLast(';', label).trim()
        if (title.isEmpty()) return@mapNotNull null
        val status = if (';' in label) label.substringAfterLast(';').trim() else ""
        val remaining = parseRemainingMinutes(item.selectFirst(".time")?.text().orEmpty())
        HomeworkEntry(title, status, remaining?.let { nowMillis + it * 60_000L })
    }
}
