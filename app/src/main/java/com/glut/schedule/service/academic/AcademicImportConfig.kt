package com.glut.schedule.service.academic

import java.net.URI

object AcademicImportConfig {
    const val host = "jw.glut.edu.cn"
    private const val timetablePath = "http://jw.glut.edu.cn/academic/manager/coursearrange/showTimetable.do"
    const val defaultStudentId = "712170"
    const val defaultYearId = "46"
    const val defaultTermId = "1"
    const val loginUrl = "http://jw.glut.edu.cn/academic/preGotoAffairFrame.do#/menu"
    val directTimetableUrl = buildStudentTimetableUrl(
        studentId = defaultStudentId,
        yearId = defaultYearId,
        termId = defaultTermId
    )

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

    fun extractStudentId(rawUrl: String): String {
        return Regex("""[?&]id=(\d+)""").find(rawUrl)?.groupValues?.get(1).orEmpty()
    }

    fun extractYearId(rawUrl: String): String {
        return Regex("""[?&]yearid=(\d+)""").find(rawUrl)?.groupValues?.get(1).orEmpty()
    }

    fun extractTermId(rawUrl: String): String {
        return Regex("""[?&]termid=(\d+)""").find(rawUrl)?.groupValues?.get(1).orEmpty()
    }

    fun buildStudentTimetableUrl(
        studentId: String = defaultStudentId,
        yearId: String = defaultYearId,
        termId: String = defaultTermId
    ): String {
        return "$timetablePath?id=$studentId&yearid=$yearId&termid=$termId&timetableType=STUDENT&sectionType=BASE"
    }
}

fun isAcademicPage(url: String): Boolean {
    return runCatching {
        val uri = URI(url)
        uri.host.equals(AcademicImportConfig.host, ignoreCase = true) &&
            uri.path.orEmpty().startsWith("/academic")
    }.getOrDefault(false)
}

fun isTimetablePage(url: String): Boolean {
    return AcademicImportConfig.timetableUrlPatterns.any { it.containsMatchIn(url) }
}

fun isLoginPage(url: String): Boolean {
    return AcademicImportConfig.loginPagePatterns.any { it.containsMatchIn(url) }
}

fun isAcademicDomainPage(url: String): Boolean {
    return runCatching {
        val uri = URI(url)
        uri.host.contains("glut.edu.cn", ignoreCase = true)
    }.getOrDefault(false)
}

fun hasUsableAcademicCookie(cookie: String): Boolean {
    return cookie.contains("JSESSIONID=", ignoreCase = true) ||
        cookie.contains("CASTGC=", ignoreCase = true) ||
        cookie.contains("TGC=", ignoreCase = true)
}
