package com.glut.schedule.service.export

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 导出文件名规则。
 *
 * 文件名会出现在系统分享面板、相册和下载目录里，必须满足两件事：
 * 1. 不带姓名、学号等个人信息——只由学期名、周次和日期拼成；
 * 2. 去掉文件系统保留字符——学期名来自教务系统，不能假设它干净。
 */

private val invalidFileNameChars = Regex("""[\\/:*?"<>|\u0000-\u001F]""")

/** 纯函数：把任意远端文本压成可安全落盘的文件名片段。 */
fun sanitizeExportName(raw: String, fallback: String): String {
    val cleaned = invalidFileNameChars.replace(raw, "")
        .trim()
        .trim('.')
        .replace(Regex("""\s+"""), " ")
    return cleaned.take(40).ifBlank { fallback }
}

/** 例如 `桂工课表_2026-2027学年第一学期_第09周_20260907.png`。 */
fun scheduleImageFileName(
    semesterName: String,
    weekNumber: Int,
    weekMonday: LocalDate?
): String {
    val semester = sanitizeExportName(semesterName, "本学期")
    val week = weekNumber.toString().padStart(2, '0')
    // 无权威校历时文件名同样不包含估算日期。
    val date = weekMonday?.format(DateTimeFormatter.BASIC_ISO_DATE)?.let { "_$it" }.orEmpty()
    return "桂工课表_${semester}_第${week}周$date.png"
}

/** 例如 `桂工课表_2026-2027学年第一学期.ics`。 */
fun scheduleIcsFileName(semesterName: String): String =
    "桂工课表_${sanitizeExportName(semesterName, "本学期")}.ics"

/** 例如 `桂工课表_2026-2027学年第一学期.csv`。 */
fun scheduleCsvFileName(semesterName: String): String =
    "桂工课表_${sanitizeExportName(semesterName, "本学期")}.csv"

/** 例如 `桂工课表_2026-2027学年第一学期_第09周.pdf` 或 `..._整学期.pdf`。 */
fun schedulePdfFileName(
    semesterName: String,
    weekNumber: Int?,
    wholeSemester: Boolean
): String {
    val semester = sanitizeExportName(semesterName, "本学期")
    val suffix = if (wholeSemester || weekNumber == null) {
        "整学期"
    } else {
        "第${weekNumber.toString().padStart(2, '0')}周"
    }
    return "桂工课表_${semester}_$suffix.pdf"
}

/** PDF 左下角只展示品牌与实际导出日期，不使用学期或课程日期。 */
fun pdfExportFooter(date: LocalDate = LocalDate.now()): String = "由「桂系一站式」导出·${date.format(DateTimeFormatter.ISO_LOCAL_DATE)}"
