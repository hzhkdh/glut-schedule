package com.glut.schedule.data.model

import com.glut.schedule.data.local.StudyPlanGroupEntity
import com.glut.schedule.data.local.StudyPlanCourseEntity
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

/** 研究生门户只提供课程清单，不提供课组要求或达标结论。 */
const val STUDY_PLAN_READ_ONLY_ATTRIBUTE = "研究生只读"

/** 页面据此隐藏学分要求、通过门数和合格图标，避免把未知状态显示成未通过。 */
val StudyPlanGroup.isReadOnly: Boolean
    get() = attribute == STUDY_PLAN_READ_ONLY_ATTRIBUTE

enum class CourseStatus {
    PASSED,          // course_pass.png
    FAILED,          // course_failed.png
    FAILED_REELECT,  // course_failed_reelect.png
    PASSED_REELECT,  // course_pass_reelect.png
    UNELECTED,       // course_unelected.png
    UNKNOWN          // course_unknown_pass.png
}

data class StudyPlanCourse(
    val id: String,
    val groupId: String,
    val courseName: String,
    val credit: Double,
    val hours: String,
    val assessment: String,
    val semester: String,
    val status: CourseStatus
) {
    companion object {
        fun stableId(groupId: String, courseName: String): String {
            val raw = "studyplan_course|$groupId|$courseName"
            return MessageDigest.getInstance("MD5")
                .digest(raw.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}

data class StudyPlanGroupWithCourses(
    val group: StudyPlanGroup,
    val courses: List<StudyPlanCourse>
)
