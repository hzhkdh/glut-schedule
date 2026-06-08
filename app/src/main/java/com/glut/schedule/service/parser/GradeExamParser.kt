package com.glut.schedule.service.parser

import com.glut.schedule.data.model.GradeExamInfo

class GradeExamParser {
    /**
     * Parse the grade/level exam HTML page from the academic system.
     *
     * Endpoint: /academic/student/skilltest/skilltest.jsdo?moduleId=2090
     *
     * The page contains two tables with class="infolist_tab":
     *   1. "等级考试通知" (notices) — ignored
     *   2. "我参加的考试" (exams I've taken) — parsed
     *
     * Columns: 考试名称, 考试时间, 准考证号, 证件号码, 成绩, 是否批准考试, 取消
     */
    fun parse(html: String): List<GradeExamInfo> {
        val exams = mutableListOf<GradeExamInfo>()
        val body = html.replace(Regex("""\s+"""), " ")

        val tableRegex = Regex(
            """<table[^>]*class\s*=\s*["'][^"']*infolist_tab[^"']*["'][^>]*>(.*?)</table>""",
            RegexOption.DOT_MATCHES_ALL
        )
        val tables = tableRegex.findAll(body).toList()

        // The second "infolist_tab" table is "我参加的考试"
        if (tables.size < 2) return exams

        val myExamsTable = tables[1].groupValues[1]

        val rowRegex = Regex("""<tr[^>]*class\s*=\s*["'][^"']*infolist_common[^"']*["'][^>]*>(.*?)</tr>""", RegexOption.DOT_MATCHES_ALL)
        val rows = rowRegex.findAll(myExamsTable).toList()

        for (row in rows) {
            val cells = parseCells(row.groupValues[1])
            if (cells.isEmpty()) continue

            val examName = cleanText(cells.getOrElse(0) { "" })
            if (examName.isBlank()) continue

            val examTime = cleanText(cells.getOrElse(1) { "" })
            val ticketNumber = cleanText(cells.getOrElse(2) { "" })
            // cells[3] = 证件号码 (id card number) — skip for privacy
            val score = cleanText(cells.getOrElse(4) { "" })
            val status = cleanText(cells.getOrElse(5) { "" })

            exams.add(
                GradeExamInfo(
                    id = GradeExamInfo.stableId(examName, ticketNumber),
                    examName = examName,
                    examTime = examTime,
                    ticketNumber = ticketNumber,
                    score = score,
                    status = status
                )
            )
        }

        return exams
    }

    private fun parseCells(rowHtml: String): List<String> {
        val cellRegex = Regex("""<t[dh][^>]*>(.*?)</t[dh]>""", RegexOption.DOT_MATCHES_ALL)
        return cellRegex.findAll(rowHtml).map { it.groupValues[1] }.toList()
    }

    private fun cleanText(text: String): String {
        return text
            .replace(Regex("""<[^>]+>"""), "")
            .replace(Regex("&nbsp;|&#160;|&#xA0;", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("&amp;", RegexOption.IGNORE_CASE), "&")
            .replace(Regex("&lt;", RegexOption.IGNORE_CASE), "<")
            .replace(Regex("&gt;", RegexOption.IGNORE_CASE), ">")
            .trim()
    }
}
