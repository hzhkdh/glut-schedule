package com.glut.schedule.data.model

import com.glut.schedule.data.local.StudyPlanGroupEntity
import java.security.MessageDigest

data class StudyPlanGroup(
    val id: String,
    val groupName: String,
    val attribute: String,
    val creditRequired: Double,
    val creditEarned: Double,
    val countRequired: Int,
    val countPassed: Int,
    val isPassed: Boolean
) {
    companion object {
        fun stableId(groupName: String, attribute: String): String {
            val raw = "studyplan|$groupName|$attribute"
            return MessageDigest.getInstance("MD5")
                .digest(raw.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
