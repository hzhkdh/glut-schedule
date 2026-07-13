package com.glut.schedule.service.parser

import com.glut.schedule.data.model.FitnessHistoryRecord
import com.glut.schedule.data.model.FitnessResult
import com.glut.schedule.data.model.FitnessScoreItem
import com.glut.schedule.data.model.FitnessSnapshot
import com.glut.schedule.data.model.FitnessStandardRow
import com.glut.schedule.data.model.FitnessStandardTable
import com.glut.schedule.data.model.FitnessStandardType

class FitnessParser {
    private data class HtmlTable(val text: String, val rows: List<List<String>>)
    private data class StandardTitle(val key: String, val title: String)
    private data class CompositeCandidate(val table: FitnessStandardTable, val sourceRowCount: Int)

    fun parseSnapshot(currentHtml: String, historyHtml: String): FitnessSnapshot = FitnessSnapshot(
        current = parseCurrent(currentHtml),
        history = parseHistory(historyHtml)
    )

    fun parseCurrent(html: String): FitnessResult {
        val table = tableWithHeader(html, listOf("项目名称", "测试成绩"))
        val headerIndex = table.rows.indexOfFirst { "项目名称" in it && "测试成绩" in it }
        if (headerIndex < 0) return resultFromTextRows(html)

        val headers = table.rows[headerIndex]
        val parsedItems = mutableListOf<FitnessScoreItem>()
        var totalScore = ""
        var totalLevel = ""
        table.rows.drop(headerIndex + 1).forEach { row ->
            if (row.size < 2) return@forEach
            val item = rowObject(headers, row)
            val name = item["项目名称"].orEmpty()
            val rowLabel = row.firstOrNull().orEmpty()
            if (name in TOTAL_LABELS || rowLabel in TOTAL_LABELS) {
                totalScore = item["总分"].orEmpty().ifBlank { item["分数"].orEmpty() }
                totalLevel = item["总分等级"].orEmpty().ifBlank { item["结论"].orEmpty() }
            } else if (name.isNotBlank() && row.size >= 4) {
                parsedItems += FitnessScoreItem(
                    name = name,
                    testScore = item["测试成绩"].orEmpty(),
                    score = item["分数"].orEmpty(),
                    conclusion = item["结论"].orEmpty(),
                    standardScore = item["标准分"].orEmpty(),
                    bonusScore = item["附加分"].orEmpty()
                )
                if (totalScore.isBlank() && item["总分"].orEmpty().isNotBlank()) {
                    totalScore = item["总分"].orEmpty()
                    totalLevel = item["总分等级"].orEmpty()
                }
            }
        }

        val textItems = parseCurrentTextRows(html)
        val items = if (textItems.size > parsedItems.size) {
            val parsedByName = parsedItems.associateBy { fitnessItemKey(it.name) }
            textItems.map { parsedByName[fitnessItemKey(it.name)] ?: it }
        } else {
            parsedItems
        }
        if (totalScore.isBlank()) {
            val textOverall = parseCurrentTextRowsWithOverall(html).firstOrNull { it.second.isNotBlank() }
            totalScore = textOverall?.second.orEmpty()
            totalLevel = textOverall?.third.orEmpty()
        }
        return FitnessResult(items, totalScore, totalLevel)
    }

    fun parseHistoryDetail(html: String): FitnessResult = parseCurrent(html)

    fun parseHistory(html: String): List<FitnessHistoryRecord> {
        val table = tableWithHeader(html, listOf("学年", "体测成绩", "体测等级"))
        val headerIndex = table.rows.indexOfFirst { "学年" in it && "体测成绩" in it }
        if (headerIndex < 0) return emptyList()
        val headers = table.rows[headerIndex]
        return table.rows.drop(headerIndex + 1)
            .map { rowObject(headers, it) }
            .filter { it["学年"].orEmpty().isNotBlank() && it["体测成绩"].orEmpty().isNotBlank() }
            .map {
                FitnessHistoryRecord(
                    year = it["学年"].orEmpty(),
                    term = it["学期"].orEmpty(),
                    grade = it["年级"].orEmpty(),
                    totalScore = it["体测成绩"].orEmpty(),
                    totalLevel = it["体测等级"].orEmpty()
                )
            }
            .sortedWith(compareByDescending<FitnessHistoryRecord> { it.year }.thenByDescending { it.term.toIntOrNull() ?: 0 })
    }

    fun parseStandard(html: String): List<FitnessStandardTable> {
        val candidates = mutableMapOf<String, CompositeCandidate>()
        tables(html).forEach { table ->
            val title = standardTitle(table) ?: return@forEach
            val parsed = parseCompositeStandard(table, title) ?: return@forEach
            val previous = candidates[title.key]
            if (previous == null || parsed.sourceRowCount < previous.sourceRowCount) {
                candidates[title.key] = parsed
            }
        }
        parseBmiStandard(html)?.let { candidates[it.key] = CompositeCandidate(it, 0) }
        parseBonusStandard(html)?.let { candidates[it.key] = CompositeCandidate(it, 0) }
        return listOf("male", "female", "bmi", "bonus").mapNotNull { candidates[it]?.table }
    }

    private fun resultFromTextRows(html: String): FitnessResult {
        val rows = parseCurrentTextRowsWithOverall(html)
        val overall = rows.firstOrNull { it.second.isNotBlank() }
        return FitnessResult(rows.map { it.first }, overall?.second.orEmpty(), overall?.third.orEmpty())
    }

    private fun parseCurrentTextRows(html: String): List<FitnessScoreItem> =
        parseCurrentTextRowsWithOverall(html).map { it.first }

    private fun parseCurrentTextRowsWithOverall(html: String): List<Triple<FitnessScoreItem, String, String>> {
        val lines = pageLines(html)
        val start = lines.indexOfLast { it == "项目名称" }
        if (start < 0) return emptyList()
        val result = mutableListOf<Triple<FitnessScoreItem, String, String>>()
        var index = start + 1
        while (index < lines.size) {
            val name = lines[index]
            if (fitnessItemKey(name).isBlank()) {
                index++
                continue
            }
            val values = mutableListOf<String>()
            while (index + 1 < lines.size &&
                fitnessItemKey(lines[index + 1]).isBlank() &&
                !lines[index + 1].contains("查看体质测试报告") && values.size < 7
            ) {
                values += lines[++index]
            }
            result += Triple(
                FitnessScoreItem(
                    name = name,
                    testScore = values.getOrElse(0) { "" },
                    score = values.getOrElse(1) { "" },
                    conclusion = values.getOrElse(2) { "" },
                    standardScore = values.getOrElse(3) { "" },
                    bonusScore = values.getOrElse(4) { "" }
                ),
                values.getOrElse(5) { "" },
                values.getOrElse(6) { "" }
            )
            index++
        }
        return result
    }

    private fun parseCompositeStandard(table: HtmlTable, title: StandardTitle): CompositeCandidate? {
        val headerIndex = table.rows.indexOfFirst { row ->
            row.any { cell ->
                val normalized = cell.replace(Regex("\\s+"), "")
                normalized.startsWith("分值") && normalized.endsWith("项目")
            }
        }
        if (headerIndex < 0) return null
        val sourceHeaders = table.rows[headerIndex]
        val rows = table.rows.drop(headerIndex + 1).mapNotNull { row ->
            val level = normalizeLevel(row.firstOrNull().orEmpty())
            val score = if (level.isNotBlank()) row.getOrElse(1) { "" } else row.firstOrNull().orEmpty()
            val values = if (level.isNotBlank()) row.drop(2) else row.drop(1)
            if (!isScoreValue(score) || values.size < sourceHeaders.size - 1) null
            else FitnessStandardRow(level = level, score = score, values = values.take(sourceHeaders.size - 1))
        }
        if (rows.isEmpty()) return null
        val weightNote = Regex("各项指标的权重比例[：:][\\s\\S]*$")
            .find(table.text)?.value?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        return CompositeCandidate(
            FitnessStandardTable(
                key = title.key,
                title = title.title,
                type = FitnessStandardType.COMPOSITE,
                headers = sourceHeaders.drop(1),
                rows = rows,
                weightNote = weightNote
            ),
            table.rows.size
        )
    }

    private fun parseBmiStandard(html: String): FitnessStandardTable? {
        val lines = pageLines(html)
        val start = lines.indexOfFirst { BMI_TITLE.containsMatchIn(it) }
        if (start < 0) return null
        val headerIndex = lines.indexOfFirstFrom(start) { it == "对象" }
        if (headerIndex < 0) return null
        val headers = lines.drop(headerIndex).take(5)
        val rawScores = lines.drop(headerIndex + 5).take(4)
        val maleIndex = lines.indexOfFirstFrom(headerIndex + 9) { it == "大学男生" }
        val femaleIndex = lines.indexOfFirstFrom(maleIndex + 1) { it == "大学女生" }
        if (headers.size < 5 || rawScores.size < 4 || maleIndex < 0 || femaleIndex < 0) return null
        val rows = listOf(
            FitnessStandardRow(label = "大学男生", values = lines.drop(maleIndex + 1).take(4)),
            FitnessStandardRow(label = "大学女生", values = lines.drop(femaleIndex + 1).take(4))
        )
        if (rows.any { it.values.size < 4 }) return null
        val end = lines.indexOfFirstFrom(femaleIndex + 1) { BONUS_TITLE.containsMatchIn(it) }
        val noteEnd = if (end < 0) lines.size else end
        val note = lines.subList((femaleIndex + 5).coerceAtMost(lines.size), noteEnd)
            .firstOrNull { it.contains("体质指数（BMI）") || it.contains("体重指数BMI") }.orEmpty()
        return FitnessStandardTable(
            key = "bmi",
            title = "表三 BMI",
            type = FitnessStandardType.BMI,
            headers = headers,
            scores = listOf("分数") + rawScores,
            rows = rows,
            note = note
        )
    }

    private fun parseBonusStandard(html: String): FitnessStandardTable? {
        val lines = pageLines(html)
        val start = lines.indexOfFirst { BONUS_TITLE.containsMatchIn(it) }
        if (start < 0) return null
        val headerIndex = lines.indexOfFirstFrom(start + 1) {
            val normalized = it.replace(Regex("\\s+"), "")
            normalized.startsWith("分值") && normalized.endsWith("项目")
        }
        if (headerIndex < 0) return null
        val headers = listOf("分值") + lines.drop(headerIndex + 1).take(4)
        if (headers.size < 5) return null
        val rows = mutableListOf<FitnessStandardRow>()
        var index = headerIndex + 5
        while (index + 4 < lines.size && isScoreValue(lines[index])) {
            rows += FitnessStandardRow(score = lines[index], values = lines.drop(index + 1).take(4))
            index += 5
        }
        return if (rows.isEmpty()) null else FitnessStandardTable(
            key = "bonus",
            title = "表四 加分",
            type = FitnessStandardType.BONUS,
            headers = headers,
            rows = rows
        )
    }

    private fun tables(html: String): List<HtmlTable> = TABLE_REGEX.findAll(html).map { match ->
        val raw = match.value
        val rows = ROW_REGEX.findAll(raw).mapNotNull { rowMatch ->
            val cells = CELL_REGEX.findAll(rowMatch.value).map { decode(it.value) }.toList()
            cells.takeIf { it.isNotEmpty() }
        }.toList()
        HtmlTable(decode(raw), rows)
    }.toList()

    private fun tableWithHeader(html: String, required: List<String>): HtmlTable =
        tables(html).firstOrNull { table -> table.rows.any { row -> required.all { it in row } } }
            ?: HtmlTable("", emptyList())

    private fun rowObject(headers: List<String>, row: List<String>): Map<String, String> =
        headers.mapIndexed { index, header -> header to row.getOrElse(index) { "" } }.toMap()

    private fun pageLines(html: String): List<String> = html
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</(td|th|tr)>", RegexOption.IGNORE_CASE), "\n")
        .replace(TAG_REGEX, " ")
        .let(::decodeEntities)
        .split(Regex("\n+"))
        .map { it.replace(Regex("\\s+"), " ").trim() }
        .filter { it.isNotBlank() }

    private fun decode(text: String): String = decodeEntities(
        text.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " ")
            .replace(TAG_REGEX, " ")
    ).replace(Regex("\\s+"), " ").trim()

    private fun decodeEntities(text: String): String = text
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)

    private fun fitnessItemKey(name: String): String {
        val text = name.replace(Regex("\\s+"), "").replace(Regex("[（(][^）)]*[）)]"), "")
        return when {
            text.startsWith("身高") -> "身高"
            text.startsWith("体重") -> "体重"
            text.startsWith("肺活量") -> "肺活量"
            text.startsWith("50米跑") -> "50米跑"
            text.startsWith("立定跳远") -> "立定跳远"
            Regex("^(1000米跑|千米跑|800米跑)").containsMatchIn(text) -> "长跑"
            Regex("^坐(?:位)?体前屈").containsMatchIn(text) -> "坐体前屈"
            Regex("^(引体向上|仰卧起坐)").containsMatchIn(text) -> "力量"
            text.startsWith("耐力加分") -> "耐力加分"
            text.startsWith("柔韧力量加分") -> "柔韧力量加分"
            else -> ""
        }
    }

    private fun standardTitle(table: HtmlTable): StandardTitle? = when {
        MALE_TITLE.containsMatchIn(table.text) -> StandardTitle("male", "表一 男生")
        FEMALE_TITLE.containsMatchIn(table.text) -> StandardTitle("female", "表二 女生")
        else -> null
    }

    private fun normalizeLevel(value: String): String = when (value.replace(Regex("\\s+"), "")) {
        "优", "优秀" -> "优秀"
        "良好" -> "良好"
        "及格" -> "及格"
        "不及格" -> "不及格"
        else -> ""
    }

    private fun isScoreValue(value: String): Boolean = value.trim().matches(Regex("-?\\d+(\\.\\d+)?"))

    private inline fun <T> List<T>.indexOfFirstFrom(start: Int, predicate: (T) -> Boolean): Int {
        if (start < 0) return -1
        for (index in start until size) if (predicate(this[index])) return index
        return -1
    }

    companion object {
        private val TOTAL_LABELS = setOf("总分", "总计")
        private val TABLE_REGEX = Regex("<table[\\s\\S]*?</table>", RegexOption.IGNORE_CASE)
        private val ROW_REGEX = Regex("<tr[\\s\\S]*?</tr>", RegexOption.IGNORE_CASE)
        private val CELL_REGEX = Regex("<t[dh][\\s\\S]*?</t[dh]>", RegexOption.IGNORE_CASE)
        private val TAG_REGEX = Regex("<[^>]+>")
        private val MALE_TITLE = Regex("表一[：:]?学生体质测试评分标准[（(]大学男生")
        private val FEMALE_TITLE = Regex("表二[：:]?学生体质测试评分标准[（(]大学女生")
        private val BMI_TITLE = Regex("表三[：:]?体重指数[（(]BMI[）)]?单项评分表")
        private val BONUS_TITLE = Regex("表四[：:]?学生体质测试加分项目评分表")
    }
}
