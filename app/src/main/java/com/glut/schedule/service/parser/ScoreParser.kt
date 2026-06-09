package com.glut.schedule.service.parser

import com.glut.schedule.data.model.ScoreInfo

class ScoreParser {
    /**
     * Parse score HTML. When [year] and [term] are null, they are extracted from the
     * table cells (cells[0] = year, cells[1] = term).
     *
     * Column layout verified via Chrome DevTools on live教务 system (2026-06-09):
     *
     *   Guilin (22 columns):
     *     [0]=year [1]=term [2]=dept [3]=courseCode [4]=courseName
     *     [5]=serial [6]=teacher [7]=score [8]=gpa [9]=credit
     *     [10]=hours [11]=examType [12]=attribute(必修/限选/任选) [13]=remark
     *     [14]=examNature [15]=deferred [16]=requirement [17]=category [18]=K
     *     [19]=doubleDegree [20]=passFlag [21]=proceduralScore
     *
     *   Nanning (13 columns):
     *     [0]=year [1]=term [2]=courseCode [3]=courseName [4]=teacher
     *     [5]=score [6]=gpa [7]=credit [8]=remark [9]=examNature
     *     [10]=deferred [11]=category(公共必修课/专业选修课…) [12]=passFlag
     *
     * Year formats handled:
     *   - "2025-2026" → extracts "2025"
     *   - "2019" → keeps "2019"
     *   - "45" → 45 + 1980 = 2025 (encoded format: year - 1980)
     */
    fun parseScoreHtml(
        html: String,
        year: String? = null,
        term: Int? = null,
        isNanning: Boolean = false,
        attributeMap: Map<String, String> = emptyMap()
    ): List<ScoreInfo> {
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
        val dataRows = if (headerIndex >= 0) rows.drop(headerIndex + 1) else rows

        // Column indices differ between Guilin and Nanning campuses.
        val courseIdx = if (isNanning) 3 else 4
        val scoreIdx = if (isNanning) 5 else 7
        val gpaIdx: Int? = if (isNanning) 6 else 8
        val creditIdx = if (isNanning) 7 else 9
        val categoryIdx = if (isNanning) 11 else 12

        for (row in dataRows) {
            val cells = parseCells(row.value)
            if (cells.isEmpty()) continue

            // Extract year from cells[0] when not provided externally
            val rowYear = year ?: extractYear(cells.getOrElse(0) { "" })
            if (rowYear == null) continue

            // Extract term from cells[1] when not provided externally
            val rowTerm = term ?: cleanHtmlText(cells.getOrElse(1) { "" }).let { raw ->
                when {
                    raw.contains("春") || raw == "1" -> 1
                    raw.contains("秋") || raw == "2" || raw == "3" -> {
                        // Nanning encodes autumn as "3", Guilin as "2"
                        if (raw == "3") 2 else 2
                    }
                    raw.any { it.isDigit() } -> raw.filter { it.isDigit() }.toIntOrNull() ?: 1
                    else -> raw.toIntOrNull() ?: 1
                }
            }

            val courseName = cleanHtmlText(cells.getOrElse(courseIdx) { "" })
            if (courseName.isBlank()) continue

            val scoreText = cleanHtmlText(cells.getOrElse(scoreIdx) { "" })
            if (scoreText.isBlank()) continue

            // Category: Guilin [12] = 必修/限选/任选 directly
            // Nanning: look up by course code [2] from teaching plan attribute map
            val rawCategory = cleanHtmlText(cells.getOrElse(categoryIdx) { "" })
            val category = if (isNanning) {
                val courseCode = cleanHtmlText(cells.getOrElse(2) { "" })
                attributeMap[courseCode]
                    ?: normalizeNanningCategory(rawCategory)
            } else rawCategory

            // Credit extraction
            val credit = cleanHtmlText(cells.getOrElse(creditIdx) { "0" })
                .let { Regex("""([.\d]+)""").find(it)?.value?.toDoubleOrNull() } ?: 0.0

            // GPA: use table value when available, calculate from score as fallback
            val gpa = if (gpaIdx != null) {
                // Extract GPA from the table (Nanning: cells[6], Guilin: cells[8])
                val rawGpa = cleanHtmlText(cells.getOrElse(gpaIdx) { "" })
                val parsedGpa = Regex("""([.\d]+)""").find(rawGpa)?.value?.toDoubleOrNull()
                // Validate: GPA should be in 0.0–4.x range
                if (parsedGpa != null && parsedGpa in 0.0..5.0) {
                    parsedGpa
                } else {
                    scoreToGpa(scoreText)
                }
            } else {
                scoreToGpa(scoreText)
            }

            val examType = cleanHtmlText(cells.getOrElse(2) { "" })

            scores.add(
                ScoreInfo(
                    id = ScoreInfo.stableId(courseName, rowYear, rowTerm),
                    courseName = courseName,
                    score = scoreText,
                    gpa = gpa,
                    credit = credit,
                    year = rowYear,
                    term = rowTerm,
                    category = category,
                    examType = examType
                )
            )
        }
        return scores
    }

    /**
     * Extract academic year from a cell value. Handles multiple formats:
     * - "2025-2026" → "2025"
     * - "2019" → "2019"
     * - "45" → "2025" (encoded: year - 1980)
     * - "2025-2026 春" → "2025"
     */
    private fun extractYear(cellHtml: String): String? {
        val cleaned = cleanHtmlText(cellHtml)
        if (cleaned.isBlank()) return null

        // Try "YYYY-YYYY" format first (e.g., "2025-2026")
        val dashMatch = Regex("""(\d{4})\s*-\s*\d{4}""").find(cleaned)
        if (dashMatch != null) {
            return dashMatch.groupValues[1]
        }

        // Try standalone 4-digit year (e.g., "2019")
        val year4Match = Regex("""\b(\d{4})\b""").find(cleaned)
        if (year4Match != null) {
            return year4Match.groupValues[1]
        }

        // Try encoded year (year - 1980, e.g., "45" → 2025)
        val numericMatch = Regex("""(\d+)""").find(cleaned)
        if (numericMatch != null) {
            val num = numericMatch.groupValues[1].toIntOrNull()
            if (num != null && num in 30..99) {
                // Encoded format: year - 1980
                // Range 30-99 covers years 2010-2079
                return (num + 1980).toString()
            }
        }

        return null
    }

    /**
     * Normalize Nanning course category (课程类别) to standard attribute (必修/限选/任选).
     * Nanning score table does not have a dedicated 选课属性 column; the [11] 课程类别
     * column contains values like "公共必修课", "专业选修课" etc.
     */
    private fun normalizeNanningCategory(category: String): String = when {
        category == "公共必修课" -> "必修"
        category == "专业基础课" -> "必修"
        category == "专业核心课" -> "必修"
        category == "实践教学环节" -> "必修"
        category == "专业选修课" -> "限选"
        category == "公共选修课" -> "任选"
        category == "必修" || category == "限选" || category == "任选" -> category
        else -> ""  // unrecognized (e.g. "学位课") → no badge
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
        // Order matters: check "不及格" before "及格" to avoid substring false match
        val gradeMap = mapOf(
            "优秀" to 4.0, "优" to 4.0, "A" to 4.0,
            "良好" to 3.0, "良" to 3.0, "B" to 3.0,
            "中等" to 2.0, "中" to 2.0, "C" to 2.0,
            "不及格" to 0.0, "F" to 0.0, "及格" to 1.0, "D" to 1.0
        )
        val gradeMatch = gradeMap.entries.firstOrNull { (key, _) ->
            scoreText.contains(key, ignoreCase = true)
        }
        if (gradeMatch != null) return gradeMatch.value

        val numeric = scoreText.replace(Regex("""[^\d.]"""), "").toDoubleOrNull()
        return when {
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
