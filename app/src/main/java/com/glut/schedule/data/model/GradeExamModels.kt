package com.glut.schedule.data.model

import java.security.MessageDigest

data class GradeExamInfo(
    val id: String,
    val examName: String,
    val examTime: String,
    val ticketNumber: String,
    val score: String,
    val status: String
) {
    companion object {
        fun stableId(examName: String, ticketNumber: String): String {
            val raw = "$examName|$ticketNumber"
            return MessageDigest.getInstance("MD5")
                .digest(raw.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
