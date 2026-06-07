package com.glut.schedule.service.parser

import com.glut.schedule.data.model.ScoreInfo

class ScoreParser {
    fun parseScoreHtml(html: String, year: String, term: Int): List<ScoreInfo> {
        val scores = mutableListOf<ScoreInfo>()
        val body = html.replace(Regex("""\s+"""), " ")

        val tableRegex = Regex(
            """<table[^>]*class\s*=\s*["'][^"']*datalist[^"']*["'][^>]*>(.*?)</table>""",
            RegexOption.DOT_MATCHES_ALL
        )
        val tableHtml = tableRegex.find(body)?.groupValues?.get(1) ?: body

        val rowRegex = Regex("""<tr[^>]*>(.*?)</tr>""", RegexOption.DOT_MATCHES_ALL)
        val rows = rowRegex.findAll(tableHtml).toList()

        val headerIndex = rows.indexOfFirst { it.value.contains("<th") }
        val dataRows = if (headerIndex >= 0) rows.drop(headerIndex + 1) else rows.drop(1)

        for (row in dataRows) {
            val cells = parseCells(row.value)
            if (cells.isEmpty()) continue

            val courseName = cleanHtmlText(cells.getOrElse(4) { "" })
            if (courseName.isBlank()) continue

            val scoreText = cleanHtmlText(cells.getOrElse(7) { "" })
            val category = cleanHtmlText(cells.getOrElse(1) { "" })
            val credit = cells.getOrElse(4) { "0" }.let {
                Regex("""[.\d]+""").find(it)?.value?.toDoubleOrNull() ?: 0.0
            }
            val examType = cleanHtmlText(cells.getOrElse(2) { "" })
            val gpa = scoreToGpa(scoreText)

            if (courseName.isNotBlank() && scoreText.isNotBlank()) {
                scores.add(
                    ScoreInfo(
                        id = ScoreInfo.stableId(courseName, year, term),
                        courseName = courseName,
                        score = scoreText,
                        gpa = gpa,
                        credit = credit,
                        year = year,
                        term = term,
                        category = category,
                        examType = examType
                    )
                )
            }
        }
        return scores
    }

    private fun parseCells(rowHtml: String): List<String> {
        val cellRegex = Regex("""<t[dh][^>]*>(.*?)</t[dh]>""", RegexOption.DOT_MATCHES_ALL)
        return cellRegex.findAll(rowHtml).map { it.groupValues[1] }.toList()
    }

    private fun cleanHtmlText(text: String): String {
        return text
            .replace(Regex("""<[^>]+>"""), "")
            .replace(Regex("&nbsp;|&#160;|&#xA0;", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("&amp;", RegexOption.IGNORE_CASE), "&")
            .replace(Regex("&lt;", RegexOption.IGNORE_CASE), "<")
            .replace(Regex("&gt;", RegexOption.IGNORE_CASE), ">")
            .trim()
    }

    private fun scoreToGpa(scoreText: String): Double {
        val gradeMap = mapOf(
            "优秀" to 4.0, "优" to 4.0, "A" to 4.0,
            "良好" to 3.0, "良" to 3.0, "B" to 3.0,
            "中等" to 2.0, "中" to 2.0, "C" to 2.0,
            "及格" to 1.0, "D" to 1.0,
            "不及格" to 0.0, "F" to 0.0
        )
        gradeMap.entries.firstOrNull { (key, _) ->
            scoreText.contains(key, ignoreCase = true)
        }?.value ?: run {
            val numeric = scoreText.replace(Regex("""[^\d.]"""), "").toDoubleOrNull()
            when {
                numeric == null -> 0.0
                numeric >= 90 -> 4.0
                numeric >= 85 -> 3.7
                numeric >= 80 -> 3.3
                numeric >= 75 -> 3.0
                numeric >= 70 -> 2.7
                numeric >= 65 -> 2.3
                numeric >= 60 -> 2.0
                numeric >= 1 -> 1.0
                else -> 0.0
            }
        }
    }
}
