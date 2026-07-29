package com.glut.schedule.service.academic

import org.jsoup.Jsoup

private const val EVALUATION_BLOCK_MESSAGE =
    "教务系统提示：有课程未参加评教，暂时不能查看成绩"
private const val ACADEMIC_SCORE_PAGE_ERROR_MESSAGE =
    "教务成绩页面异常，请稍后重试"

/**
 * 识别成绩接口返回的“非成绩页面”。
 *
 * 教务系统发生内部异常时仍可能返回 HTTP 200，但正文是标题为“提示信息”的错误页；
 * 因此不能仅以状态码或解析出的成绩条数判断成功，否则会把上游错误误报为“暂无成绩”。
 */
internal fun scorePageUnavailableReason(html: String): String? {
    if (html.isBlank()) return null

    val compact = html.replace(Regex("""\s+"""), "")
    if (
        compact.contains("评教") &&
        (compact.contains("不能查看成绩") || compact.contains("暂时不能查看"))
    ) {
        return EVALUATION_BLOCK_MESSAGE
    }

    val document = Jsoup.parse(html)
    val hasNormalScoreTable = document.select("table.datalist").isNotEmpty()
    val isAcademicErrorPage =
        document.title().trim() == "提示信息" ||
            document.select("table.error").isNotEmpty()

    return if (!hasNormalScoreTable && isAcademicErrorPage) {
        ACADEMIC_SCORE_PAGE_ERROR_MESSAGE
    } else {
        null
    }
}
