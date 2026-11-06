package com.glut.schedule.service.parser

import org.jsoup.Jsoup

/** 课表网格正常不代表调课区完整；无法确认的空结果必须保留旧记录。 */
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
    require(!hasUnparsedRows && (explicitEmpty || emptyTable)) { "调课区域不完整，已保留原有记录" }
}
