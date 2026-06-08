package com.glut.schedule.service.parser

import com.glut.schedule.data.model.StudyPlanGroup

class StudyPlanParser {

    fun parseStudentIds(html: String): Pair<String, String>? {
        val linkRegex = Regex(
            """scheduleJump\.jsp\?link=studentScheduleLineShow\.do(?:&amp;|&)studentId=([^&"]+)(?:&amp;|&)classId=(\d+)""",
            RegexOption.IGNORE_CASE
        )
        val matches = linkRegex.findAll(html).toList()
        if (matches.isEmpty()) return null
        val lastMatch = matches.last()
        return Pair(lastMatch.groupValues[1], lastMatch.groupValues[2])
    }

    /**
     * Parse all study plan groups from the teaching plan page.
     *
     * The page has two types of group tables:
     *   1. Free elective groups: table with header "任选课组名称, 说明, 课程要求, 学分要求, 门数要求, 课组审查要求"
     *   2. Mandatory/limited groups: rows with header "课组名称, 课程要求, 学分, 门数" between course tables
     *
     * Column format for type 1 (free electives):
     *   [0]=name, [2]=attribute, [3]=credit(EARNED OP REQUIRED), [4]=count(PASSED OP REQUIRED), [5]=pass/fail image
     *
     * Column format for type 2 (mandatory groups):
     *   [0]=name, [1]=attribute, [2+3]=credit(EARNED OP REQUIRED), [4+5]=count(PASSED OP REQUIRED)
     *   Pass status from image (course_pass.png / course_failed.png)
     */
    fun parseGroups(html: String): List<StudyPlanGroup> {
        val groups = mutableListOf<StudyPlanGroup>()
        val body = html.replace(Regex("""\s+"""), " ")

        // Find ALL table-like structures (any <table> tag, not just datalist)
        val tableRegex = Regex("""<table[^>]*>(.*?)</table>""", RegexOption.DOT_MATCHES_ALL)
        val tables = tableRegex.findAll(body).toList()

        for (table in tables) {
            val tableHtml = table.groupValues[1]
            val rowRegex = Regex("""<tr[^>]*>(.*?)</tr>""", RegexOption.DOT_MATCHES_ALL)
            val rows = rowRegex.findAll(tableHtml).toList()
            if (rows.isEmpty()) continue

            // Get header text
            val headerCells = parseCells(rows.first().value)
            val headerText = headerCells.joinToString(",") { cleanHtmlText(it) }

            // Skip student info table
            if (headerText.contains("院系") && headerText.contains("专业")) continue

            // Type 1: Free elective groups (任选课组)
            if (headerText.contains("任选课组名称")) {
                val headerIndex = rows.indexOfFirst { it.value.contains("<th") }
                val dataRows = if (headerIndex >= 0) rows.drop(headerIndex + 1) else rows
                for (row in dataRows) {
                    val cells = parseCells(row.value)
                    if (cells.size < 6) continue
                    val name = cleanHtmlText(cells[0])
                    if (name.isBlank()) continue
                    val attr = cleanHtmlText(cells[2])
                    val credit = parseNumPair(cleanHtmlText(cells[3]))
                    val count = parseIntPair(cleanHtmlText(cells[4]))
                    val passed = cells[5].contains("course_pass.png") ||
                        cleanHtmlText(cells[5]).contains("通过")
                    groups.add(makeGroup(name, attr, credit.first, credit.second,
                        count.first, count.second, passed))
                }
            }

            // Type 2: Mandatory/limited groups (课组)
            if (headerText.contains("课组名称") && headerText.contains("课程要求") &&
                !headerText.contains("任选课组")) {
                val headerIndex = rows.indexOfFirst { it.value.contains("<th") }
                val dataRows = rows.drop(headerIndex + 1)
                for (row in dataRows) {
                    val cells = parseCells(row.value)
                    if (cells.size < 2) continue
                    val name = cleanHtmlText(cells[1])  // Skip hierarchy column
                    if (name.isBlank() || name.contains("说明") || name.contains("课程号")) continue

                    // Find attribute (必修/限选/任选) among cells
                    var attr = ""
                    for (i in 2 until cells.size) {
                        val t = cleanHtmlText(cells[i])
                        if (t in listOf("必修", "限选", "任选")) { attr = t; break }
                    }
                    if (attr.isBlank()) continue

                    // Find two number pairs using regex
                    val compRegex = Regex("""([\d.]+)\s*([<>=]+)\s*([\d.]+)""")
                    val allText = cells.joinToString(" ") { cleanHtmlText(it) }
                    val comps = compRegex.findAll(allText).toList()
                    if (comps.size < 2) continue

                    val creditEarned = comps[0].groupValues[1].toDoubleOrNull() ?: 0.0
                    val creditRequired = comps[0].groupValues[3].toDoubleOrNull() ?: 0.0
                    val countPassed = comps[1].groupValues[1].toIntOrNull() ?: 0
                    val countRequired = comps[1].groupValues[3].toIntOrNull() ?: 0
                    val passed = row.value.contains("course_pass.png") ||
                        (creditEarned >= creditRequired && countPassed >= countRequired)

                    groups.add(makeGroup(name, attr, creditEarned, creditRequired,
                        countPassed, countRequired, passed))
                }
            }
        }

        // Deduplicate by id
        return groups.distinctBy { it.id }
    }

    private fun makeGroup(name: String, attr: String, creditEarned: Double, creditRequired: Double,
                          countPassed: Int, countRequired: Int, passed: Boolean): StudyPlanGroup {
        return StudyPlanGroup(
            id = StudyPlanGroup.stableId(name, attr),
            groupName = name, attribute = attr,
            creditRequired = creditRequired, creditEarned = creditEarned,
            countRequired = countRequired, countPassed = countPassed,
            isPassed = passed
        )
    }

    private fun parseNumPair(text: String): Pair<Double, Double> {
        val regex = Regex("""([\d.]+)\s*[<>=]+\s*([\d.]+)""")
        val m = regex.find(text.trim())
        if (m != null) return Pair(m.groupValues[1].toDoubleOrNull() ?: 0.0, m.groupValues[2].toDoubleOrNull() ?: 0.0)
        return Pair(0.0, 0.0)
    }

    private fun parseIntPair(text: String): Pair<Int, Int> {
        val regex = Regex("""(\d+)\s*[<>=]+\s*(\d+)""")
        val m = regex.find(text.trim())
        if (m != null) return Pair(m.groupValues[1].toIntOrNull() ?: 0, m.groupValues[2].toIntOrNull() ?: 0)
        return Pair(0, 0)
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
}
