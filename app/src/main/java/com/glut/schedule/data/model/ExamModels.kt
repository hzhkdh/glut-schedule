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
