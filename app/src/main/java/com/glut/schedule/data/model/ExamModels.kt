package com.glut.schedule.data.model

import java.security.MessageDigest
import java.time.LocalDate

data class ExamInfo(
    val id: String,
    val courseName: String,
    val examDate: LocalDate,
    val startTime: String,
    val endTime: String,
    val location: String,
    val seatNumber: String,
    val examType: String,
    val note: String
) {
    companion object {
        fun stableId(
            courseName: String,
            examDate: LocalDate,
            startTime: String,
            endTime: String,
            location: String
        ): String {
            val raw = "$courseName|$examDate|$startTime|$endTime|$location"
            return MessageDigest.getInstance("MD5").digest(raw.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}

fun cleanExamText(text: String): String {
    return text
        .replace(Regex("&nbsp;|&#160;|&#xA0;", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("&amp;", RegexOption.IGNORE_CASE), "&")
        .replace(Regex("&lt;", RegexOption.IGNORE_CASE), "<")
        .replace(Regex("&gt;", RegexOption.IGNORE_CASE), ">")
        .replace(Regex("&quot;", RegexOption.IGNORE_CASE), "\"")
        .replace(Regex("&#39;|&apos;", RegexOption.IGNORE_CASE), "'")
        .replace('\u00A0', ' ')
        .replace(Regex("""\s+"""), " ")
        .trim()
}

fun ExamInfo.sanitized(): ExamInfo = copy(
    courseName = cleanExamText(courseName),
    startTime = cleanExamText(startTime),
    endTime = cleanExamText(endTime),
    location = cleanExamText(location),
    seatNumber = cleanExamText(seatNumber),
    examType = cleanExamText(examType),
    note = cleanExamText(note)
)
