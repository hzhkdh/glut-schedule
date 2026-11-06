package com.glut.schedule.service.chaoxing

import org.jsoup.Jsoup

data class HomeworkEntry(val title: String, val status: String, val deadlineMillis: Long?, val workId: String = "", val taskId: String = workId, val bbsId: String = "")

// 学习通的合法空页面没有 ul，仅在任务列表容器内显示明确的“暂无作业”。
// 限定容器和文字，避免把错误页中偶然出现的空状态文案当作成功。
fun hasRecognizableHomeworkList(html: String): Boolean {
    val document = Jsoup.parse(html)
    if (document.selectFirst("ul li[aria-label]") != null) return true
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
        HomeworkEntry(title, status, remaining?.let { nowMillis + it * 60_000L },
            runCatching { java.net.URI(item.attr("data")).rawQuery.orEmpty().split('&')
                .firstOrNull { it.startsWith("workId=") }?.substringAfter('=').orEmpty() }.getOrDefault(""))
    }
}

/** 成功缓存超过半小时才补刷新；失败至少让开五分钟，避免进出菜单反复请求。 */
fun homeworkRefreshDue(now: Long, successAt: Long, attemptAt: Long): Boolean =
    (successAt <= 0 || now - successAt >= 30 * 60_000L) &&
        (attemptAt <= 0 || now - attemptAt >= 5 * 60_000L)

/** 优先显示独立说明；没有说明时只保留教师正文要求，剥掉题号、题型和空题。 */
fun parseHomeworkDetail(html: String): String {
    val document = Jsoup.parse(html)
    val descriptions = document.select(".borderBox .pcon").filter { element ->
        element.parents().none { it.hasClass("questionLi") }
    }
    val recognized = descriptions.isNotEmpty() || document.selectFirst(".questionLi, .mark_title") != null
    require(recognized && document.selectFirst("input[type=password]") == null) {
        "无法识别作业详情，请重新登录后重试"
    }
    fun sanitize(element: org.jsoup.nodes.Element): String {
        val copy = element.clone()
        copy.select("script,style,textarea,input,button,iframe,form,.answerBg,.questionLi").remove()
        val cleaned = Jsoup.clean(copy.outerHtml(), "https://mooc1.chaoxing.com/",
            org.jsoup.safety.Safelist.basicWithImages()
                .addProtocols("a", "href", "https").addProtocols("img", "src", "https"))
        // 有些要求只有下载图标，没有链接文字；清洗后补充可见入口，避免误报空要求。
        val content = Jsoup.parseBodyFragment(cleaned).body()
        content.select("a[href]").filter { it.text().isBlank() && it.selectFirst("img") == null }
            .forEach { it.text("附件链接") }
        return content.html()
    }
    fun meaningful(value: String): Boolean {
        val body = Jsoup.parseBodyFragment(value).body()
        return body.text().isNotBlank() || body.selectFirst("img") != null
    }
    val primary = descriptions.map(::sanitize).filter(::meaningful)
    if (primary.isNotEmpty()) return primary.joinToString("\n")

    fun removeNumber(node: org.jsoup.nodes.Node): Boolean {
        if (node is org.jsoup.nodes.TextNode && node.wholeText.isNotBlank()) {
            node.text(node.wholeText.replaceFirst(Regex("""^\s*\d+\s*[.、．]\s*"""), ""))
            return true
        }
        return node.childNodes().any(::removeNumber)
    }
    val typeLabel = Regex("""^[（(][^（）()\n]{0,24}题[）)]$""")
    val fallback = document.select(".questionLi .mark_name").map { source ->
        val body = source.clone().tagName("div")
        body.select(".colorShallow").filter { typeLabel.matches(it.text().trim()) }.forEach { it.remove() }
        removeNumber(body)
        sanitize(body)
    }.filter(::meaningful)
    return fallback.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: "<p>本作业暂无可展示的要求</p>"
}

/** 固定窄屏排版，长链接、公式和预格式文本都不能撑破卡片。 */
fun homeworkDetailDocument(html: String): String = """
    <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
    <style>html,body{margin:0;padding:0;width:100%;box-sizing:border-box}
    body{font:16px sans-serif;line-height:1.6;overflow-wrap:anywhere;word-break:break-word}
    *{box-sizing:border-box;max-width:100%}img{height:auto}pre{white-space:pre-wrap}table{table-layout:fixed;width:100%}
    </style></head><body>$html</body></html>
""".trimIndent()
