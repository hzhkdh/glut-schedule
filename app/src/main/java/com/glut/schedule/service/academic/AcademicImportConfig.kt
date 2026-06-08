package com.glut.schedule.service.academic

object AcademicImportConfig {
    const val host = "jw.glut.edu.cn"
    private const val timetablePath = "http://jw.glut.edu.cn/academic/manager/coursearrange/showTimetable.do"
    const val defaultYearId = "46"
    const val defaultTermId = "1"
    const val loginUrl = "http://jw.glut.edu.cn/academic/preGotoAffairFrame.do#/menu"
    val directTimetableUrl = loginUrl

    val timetableUrlPatterns = listOf(
        Regex("""showTimetable\.do""", RegexOption.IGNORE_CASE),
        Regex("""timetableType=STUDENT""", RegexOption.IGNORE_CASE),
        Regex("""/academic/manager/coursearrange""", RegexOption.IGNORE_CASE),
        Regex("""studentTimetable""", RegexOption.IGNORE_CASE),
    )

    val loginPagePatterns = listOf(
        Regex("""cas\.glut\.edu\.cn""", RegexOption.IGNORE_CASE),
        Regex("""/login""", RegexOption.IGNORE_CASE),
        Regex("""preLogin""", RegexOption.IGNORE_CASE),
    )

    fun buildStudentTimetableUrl(
        studentId: String,
        yearId: String = defaultYearId,
        termId: String = defaultTermId,
        baseUrl: String = timetablePath.substringBefore("/academic")
    ): String {
        return "$baseUrl/academic/manager/coursearrange/showTimetable.do?id=$studentId&yearid=$yearId&termid=$termId&timetableType=STUDENT&sectionType=BASE"
    }
}

fun hasUsableAcademicCookie(cookie: String): Boolean {
    return cookie.contains("JSESSIONID=", ignoreCase = true) ||
        cookie.contains("CASTGC=", ignoreCase = true) ||
        cookie.contains("TGC=", ignoreCase = true)
}

fun shouldUseExistingAcademicCookie(cookie: String): Boolean {
    return hasUsableAcademicCookie(cookie)
}
