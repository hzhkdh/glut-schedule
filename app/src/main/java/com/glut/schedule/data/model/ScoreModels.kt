package com.glut.schedule.data.model

import com.glut.schedule.data.local.ScoreEntity
import java.security.MessageDigest

data class ScoreInfo(
    val id: String,
    val courseName: String,
    val score: String,
    val gpa: Double,
    val credit: Double,
    val year: String,
    val term: Int,
    val category: String,
    val examType: String
) {
    companion object {
        fun stableId(courseName: String, year: String, term: Int): String {
            val raw = "$courseName|$year|$term"
            return MessageDigest.getInstance("MD5")
                .digest(raw.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}

fun ScoreInfo.toEntity(): ScoreEntity = ScoreEntity(
    id = id, courseName = courseName, score = score,
    gpa = gpa, credit = credit, year = year,
    term = term, category = category, examType = examType
)

fun ScoreEntity.toModel(): ScoreInfo = ScoreInfo(
    id = id, courseName = courseName, score = score,
    gpa = gpa, credit = credit, year = year,
    term = term, category = category, examType = examType
)
