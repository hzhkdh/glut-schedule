package com.glut.schedule.service.parser

import org.jsoup.Jsoup

/** 只接受明确的空调课结果；无法确认页面完整时保留旧记录。 */
fun validateAdjustmentRefresh(html: String, parsedCount: Int) {
    val doc = Jsoup.parse(html)
    val kinds = setOf("调课", "补课", "停课", "代课")
    val recordRows = doc.select("tr").filter { it.selectFirst("td")?.text()?.trim() in kinds }
    // 正常主行至少有类型和两组时间列；部分坏行也不能静默覆盖完整缓存。
    require(recordRows.none { it.select("td").size < 12 }) { "调课行不完整，已保留原有记录" }
    if (parsedCount > 0) return
    val hasUnparsedRows = recordRows.isNotEmpty()
    val explicitEmpty = doc.text().let { text ->
        listOf("暂无调课", "无调课记录", "无调课信息").any(text::contains)
    }
    val emptyTable = doc.select("table").any { table ->
        val headings = table.select("th, tr:first-child td").text()
        headings.contains("类型") && headings.contains("补课") &&
            (headings.contains("时间") || headings.contains("周次"))
    }
    // 教务没有调课时会省略整个调课表，而不是输出“暂无调课”。
    // 仅在文档完整、七天表头和节次行齐全、也没有待解析调课区时接受这种空结果。
    val timetable = doc.selectFirst("table#timetable")
    val weekday = Regex("(?:周|星期)[一二三四五六日天]")
    val headings = timetable?.select("tr")?.firstOrNull()?.select("th,td").orEmpty()
    val days = headings.map { it.text().trim() }.filter(weekday::matches)
        .map { it.removePrefix("星期").removePrefix("周").replace("天", "日") }.toSet()
    val hasSectionRow = timetable?.select("tr")?.any { row ->
        val cells = row.select("th,td")
        cells.size >= 8 && Regex("第\\s*\\d+\\s*节").containsMatchIn(cells.first()?.text().orEmpty())
    } == true
    val hasAdjustmentHeading = doc.select("th,td").any {
        it.text().trim() in setOf("类型", "调课记录", "调课信息", "补课记录", "补课时间", "调课时间")
    }
    val omittedEmptySection = !hasAdjustmentHeading && days.size == 7 && hasSectionRow &&
        doc.selectFirst("input[type=password]") == null &&
        Regex("</body\\s*>\\s*</html\\s*>\\s*$", RegexOption.IGNORE_CASE).containsMatchIn(html)
    require(!hasUnparsedRows && (explicitEmpty || emptyTable || omittedEmptySection)) {
        "调课区域不完整，已保留原有记录"
    }
}
