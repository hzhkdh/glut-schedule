package com.glut.schedule.service.parser

import com.glut.schedule.data.model.ScoreInfo

class ScoreParser {
    /**
     * Parse score HTML. When [year] and [term] are null, they are extracted from the
     * table cells (cells[0] = year, cells[1] = term).
     *
     * Column layout (from reference projects):
     *   Guilin (glut Android): cells[0]=year, [1]=term, [4]=courseName, [7]=score, [20]=failState
     *   Nanning (GlutAssistantN): cells[2]=courseCode, [3]=courseName, [4]=teacher,
     *                              [5]=score, [6]=gpa, [7]=credit, [11]=category
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
        isNanning: Boolean = false
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
        // Verified against GlutAssistant (Flutter), GlutAssistantN (Flutter), and glut (Kotlin/Android).
        //
        // Guilin layout (glut project Jsoup child indices):
        //   [0]=year [1]=term [2]=courseCode [3]=courseName [4]=teacher [5]=hours
        //   [6]=creditFlag [7]=score [8]=gpa ... [20]=failState
        //   → GlutAssistant Guilin regex: GPA at the cell after score (cells[8]).
        //
        // Nanning layout (GlutAssistantN Jsoup child indices):
        //   [2]=courseCode [3]=courseName [4]=teacher [5]=score [6]=gpa [7]=credit [11]=category
        //
        // Guilin score table does NOT have a credit (学分) column — confirmed by both
        // GlutAssistant (only extracts GPA) and glut (only extracts name + score + failState).
        val courseIdx = if (isNanning) 3 else 4
        val scoreIdx = if (isNanning) 5 else 7
        val gpaIdx: Int? = if (isNanning) 6 else 8          // Guilin: GPA right after score (cells[8])
        val creditIdx = if (isNanning) 7 else null           // Guilin: no credit column in score table
        val categoryIdx = if (isNanning) 11 else null        // Nanning: course category at [11]

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

            // Category: use dedicated column for Nanning, fallback for Guilin
            val category = if (categoryIdx != null) {
                cleanHtmlText(cells.getOrElse(categoryIdx) { "" })
            } else {
                // Guilin: category column is not at a known fixed position
                // cells[1] is term, not category — leave blank for Guilin
                ""
            }

            // Credit extraction
            val credit = if (creditIdx != null) {
                // Nanning: credit is directly at the known column (cells[7])
                cleanHtmlText(cells.getOrElse(creditIdx) { "0" })
                    .let { Regex("""([.\d]+)""").find(it)?.value?.toDoubleOrNull() } ?: 0.0
            } else {
                // Guilin: score table has NO credit column.
                // Both GlutAssistant and glut do not extract credit for Guilin.
                // The old auto-detection from cells[5]/[6]/[8] picked up hours
                // or other metadata (causing bugs like 学分=118.0 for 体育3).
                0.0
            }

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
