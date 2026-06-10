package com.glut.schedule.service.parser

import com.glut.schedule.data.model.StudyPlanGroup
import com.glut.schedule.data.model.StudyPlanCourse
import com.glut.schedule.data.model.CourseStatus

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

    /**
     * Parse 课程类别 → 选课属性 mapping from the teaching plan page.
     * Used by ScoreParser for Nanning campus where the score table doesn't include
     * a dedicated 选课属性 column — we infer it from the teaching plan's课组 definitions.
     *
     * Structure: each课组 table (class "form output_ctx") has columns:
     *   课组层级 | 课组名称 | 课程要求 | ...
     * Data rows with 课组层级 == "课组" or "子课组" map 课组名称 → 课程要求.
     */
    /**
     * Parse course-level 课程号 → 选课属性 mapping from the teaching plan page.
     * Unlike课组-level mapping, this correctly handles cases where the same 课程类别
     * (e.g. "专业选修课") maps to different attributes in different groups.
     *
     * Page structure alternates: [form group table] [datalist course table] [form group] [datalist course] ...
     * Each course table's courses inherit the preceding group's 课程要求.
     */
    fun parseCourseAttributeMap(html: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val body = html.replace(Regex("""\s+"""), " ")

        val tableRegex = Regex("""<table[^>]*class\s*=\s*["'][^"']*(?:form|datalist)[^"']*output_ctx[^"']*["'][^>]*>(.*?)</table>""", RegexOption.DOT_MATCHES_ALL)
        val rowRegex = Regex("""<tr[^>]*>(.*?)</tr>""", RegexOption.DOT_MATCHES_ALL)

        val allTables = tableRegex.findAll(body).toList()
        var currentAttr = ""  // attribute from the most recent group table

        for (table in allTables) {
            val tableHtml = table.groupValues[1]
            val rows = rowRegex.findAll(tableHtml).toList()
            if (rows.isEmpty()) continue

            val headerCells = parseCells(rows.first().value)
            val headerText = headerCells.joinToString(",") { cleanHtmlText(it) }

            val isGroupTable = headerText.contains("课组名称") && headerText.contains("课程要求")
            val isCourseTable = headerText.contains("课程号") && headerText.contains("课程名")

            if (isGroupTable) {
                // Extract current attribute from the first课组/子课组 row
                val headerIndex = rows.indexOfFirst { it.value.contains("<th") }
                val dataRows = if (headerIndex >= 0) rows.drop(headerIndex + 1) else rows
                for (row in dataRows) {
                    val cells = parseCells(row.value)
                    if (cells.size < 3) continue
                    val level = cleanHtmlText(cells[0])
                    if (level != "课组" && level != "子课组") continue
                    val attr = cleanHtmlText(cells[2])
                    if (attr == "必修" || attr == "限选" || attr == "任选") {
                        currentAttr = attr
                        break
                    }
                }
            } else if (isCourseTable && currentAttr.isNotBlank()) {
                // Map each course code to the current group's attribute
                val headerIndex = rows.indexOfFirst { it.value.contains("<th") }
                val dataRows = if (headerIndex >= 0) rows.drop(headerIndex + 1) else rows
                for (row in dataRows) {
                    val cells = parseCells(row.value)
                    if (cells.size < 2) continue
                    val courseCode = cleanHtmlText(cells[0])  // [0]=课程号
                    if (courseCode.isNotBlank()) {
                        map[courseCode] = currentAttr
                    }
                }
            }
        }
        return map
    }

    /**
     * Parse both groups and courses from the 平铺模式 (flat mode) HTML.
     * The page alternates between group tables and course tables.
     */
    fun parseData(html: String): Pair<List<StudyPlanGroup>, List<StudyPlanCourse>> {
        val groups = mutableListOf<StudyPlanGroup>()
        val courses = mutableListOf<StudyPlanCourse>()
        val body = html.replace(Regex("""\s+"""), " ")

        val tableRegex = Regex("""<table[^>]*>(.*?)</table>""", RegexOption.DOT_MATCHES_ALL)
        val tables = tableRegex.findAll(body).toList()
        val rowRegex = Regex("""<tr[^>]*>(.*?)</tr>""", RegexOption.DOT_MATCHES_ALL)

        var currentGroupId: String? = null

        for (table in tables) {
            val tableHtml = table.groupValues[1]
            val rows = rowRegex.findAll(tableHtml).toList()
            if (rows.isEmpty()) continue

            val headerCells = parseCells(rows.first().value)
            val headerText = headerCells.joinToString(",") { cleanHtmlText(it) }

            // Skip student info table
            if (headerText.contains("院系") && headerText.contains("专业")) continue

            // Type 1: Free elective groups
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
                    val group = makeGroup(name, attr, credit.first, credit.second,
                        count.first, count.second, passed)
                    groups.add(group)
                    currentGroupId = group.id
                }
            }

            // Type 2: Mandatory/limited groups
            if (headerText.contains("课组名称") && headerText.contains("课程要求") &&
                !headerText.contains("任选课组")) {
                val headerIndex = rows.indexOfFirst { it.value.contains("<th") }
                val dataRows = rows.drop(headerIndex + 1)
                for (row in dataRows) {
                    val cells = parseCells(row.value)
                    if (cells.size < 2) continue
                    val name = cleanHtmlText(cells[1])
                    if (name.isBlank() || name.contains("说明") || name.contains("课程号")) continue

                    var attr = ""
                    for (i in 2 until cells.size) {
                        val t = cleanHtmlText(cells[i])
                        if (t in listOf("必修", "限选", "任选")) { attr = t; break }
                    }
                    if (attr.isBlank()) continue

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

                    val group = makeGroup(name, attr, creditEarned, creditRequired,
                        countPassed, countRequired, passed)
                    groups.add(group)
                    currentGroupId = group.id
                }
            }

            // Course table
            if (headerText.contains("课程号") && headerText.contains("课程名") &&
                !headerText.contains("任选课组名称") && !headerText.contains("课组名称")) {
                val headerIndex = rows.indexOfFirst { it.value.contains("<th") }
                // Build full header text from both header rows to detect campus layout
                val headerRows = if (headerIndex >= 0) {
                    rows.slice(headerIndex..minOf(headerIndex + 1, rows.lastIndex))
                } else rows.take(2)
                val allHeaderText = headerRows.joinToString(" ") { it.value }

                // Detect campus: Nanning has no "详细学时"/"实验" sub-columns
                val isNanning = !allHeaderText.contains("实验") && !allHeaderText.contains("详细学时")

                // Layout: 课程类别 col, semester start col, semester count
                val colCat: Int
                val semStart: Int
                val semCount: Int
                if (isNanning) {
                    colCat = 5    // 课程类别 at index 5
                    semStart = 6  // 6 semesters start at index 6
                    semCount = 6
                } else {
                    colCat = 9    // 课程类别 at index 9 (after 4 详细学时 cols)
                    semStart = 10 // 8 semesters start at index 10
                    semCount = 8
                }
                val headerRowStrings = headerRows.map { it.value }
                // Header row 2 has fewer cells than data rows (no 详细学时 prefix in Guilin)
                val headerSemStart = if (isNanning) 0 else 4
                val semNames = parseSemesterNames(headerRowStrings, headerSemStart, semCount)

                // Data rows (skip 2 header rows)
                val dataRows = rows.drop(headerIndex + 2)
                for (row in dataRows) {
                    val cells = parseCells(row.value)
                    if (cells.size < 5) continue
                    val courseCode = cleanHtmlText(cells[0])
                    if (courseCode.isBlank()) continue

                    val nameWithStatus = cleanHtmlText(cells[1])
                    val courseName = nameWithStatus
                        .replace(Regex("""^(最高成绩[^ ]+|已选课[^ ]+)\s+"""), "")
                        .trim()

                    val status = parseCourseStatus(cells[1])
                    val assessment = cleanHtmlText(cells[2])
                    val credit = cleanHtmlText(cells[3]).toDoubleOrNull() ?: 0.0
                    val hours = cleanHtmlText(cells[4])

                    val semester = findSemesterDynamic(cells, semStart, semNames)

                    val gid = currentGroupId ?: StudyPlanGroup.stableId("未分类", "")
                    val course = StudyPlanCourse(
                        id = StudyPlanCourse.stableId(gid, courseName),
                        groupId = gid,
                        courseName = courseName,
                        credit = credit,
                        hours = hours,
                        assessment = assessment,
                        semester = semester,
                        status = status
                    )
                    courses.add(course)
                }
            }
        }

        return Pair(
            groups.distinctBy { it.id },
            courses.distinctBy { it.id }
        )
    }

    private fun parseCourseStatus(cellHtml: String): CourseStatus {
        return when {
            cellHtml.contains("course_pass_reelect") -> CourseStatus.PASSED_REELECT
            cellHtml.contains("course_failed_reelect") -> CourseStatus.FAILED_REELECT
            cellHtml.contains("course_unelected") -> CourseStatus.UNELECTED
            cellHtml.contains("course_unknown_pass") -> CourseStatus.UNKNOWN
            cellHtml.contains("course_pass") -> CourseStatus.PASSED
            cellHtml.contains("course_failed") -> CourseStatus.FAILED
            else -> CourseStatus.UNKNOWN
        }
    }

    /**
     * Parse semester names from header rows, converting教务 format to app format.
     * E.g. "1秋2024" → "2024秋", "1春2025" → "2025春"
     */
    private fun parseSemesterNames(headerRows: List<String>, semStart: Int, count: Int): List<String> {
        // Use cells from the second header row (the sub-header with semester year labels)
        val secondRow = if (headerRows.size >= 2) headerRows[1] else headerRows.last()
        val cells = parseCells(secondRow)
        val names = mutableListOf<String>()
        for (i in semStart until minOf(semStart + count, cells.size)) {
            val raw = cleanHtmlText(cells[i])
            if (raw.isBlank()) continue
            // Convert "1秋2024" → "2024秋", "1春2025" → "2025春"
            val converted = convertSemesterName(raw)
            names.add(converted.ifBlank { raw })
        }
        // Pad to expected count if some names couldn't be parsed
        while (names.size < count) names.add("")
        return names
    }

    /**
     * Convert教务 semester format to app format.
     * "1秋2024" → "2024秋", "1春2025" → "2025春"
     */
    private fun convertSemesterName(raw: String): String {
        val yearMatch = Regex("""(\d{4})""").find(raw) ?: return raw
        val year = yearMatch.groupValues[1]
        return if (raw.contains("秋")) "${year}秋"
        else if (raw.contains("春")) "${year}春"
        else raw
    }

    /**
     * Find which semester column has "Y" and return the corresponding name.
     */
    private fun findSemesterDynamic(cells: List<String>, semStart: Int, semNames: List<String>): String {
        for (i in semStart until minOf(semStart + semNames.size, cells.size)) {
            val text = cleanHtmlText(cells[i])
            if (text == "Y") {
                val idx = i - semStart
                if (idx in semNames.indices) return semNames[idx]
            }
        }
        return ""
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
