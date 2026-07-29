package com.glut.schedule.partner

import com.glut.schedule.data.model.ClassPeriod
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.academicWeeksForText
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

enum class PartnerIdentityColor(val storageValue: String) {
    PINK("pink"),
    BLUE("blue");

    companion object {
        fun fromStorage(value: String): PartnerIdentityColor =
            entries.firstOrNull { it.storageValue == value }
                ?: throw IllegalArgumentException("不支持的身份颜色")
    }
}

data class PartnerCourse(
    val id: String,
    val title: String,
    val room: String?,
    val teacher: String?,
    val dayOfWeek: Int,
    val startSection: Int,
    val endSection: Int,
    val weeks: List<Int>,
    val startTime: String,
    val endTime: String,
    val ownerColor: PartnerIdentityColor
)

data class PartnerScheduleSnapshot(
    val schemaVersion: Int = 1,
    val identityColor: PartnerIdentityColor,
    val campus: String,
    val semesterStartMonday: LocalDate,
    val semesterEndDate: LocalDate,
    val courses: List<PartnerCourse>
)

/**
 * 将本地课表转换为可共享快照。转换发生在上传前，确保未授权字段和本地备注
 * 从一开始就不会进入网络请求体。
 */
fun createPartnerScheduleSnapshot(
    identityColor: PartnerIdentityColor,
    campus: String,
    semesterStartMonday: LocalDate,
    semesterEndDate: LocalDate,
    courses: List<ScheduleCourse>,
    classPeriods: List<ClassPeriod>,
    shareRoom: Boolean,
    shareTeacher: Boolean
): PartnerScheduleSnapshot {
    val periodBySection = classPeriods.associateBy { it.section }
    val sharedCourses = courses.flatMap { course ->
        course.occurrences.mapNotNull { occurrence ->
            val firstPeriod = periodBySection[occurrence.startSection] ?: return@mapNotNull null
            val lastPeriod = periodBySection[occurrence.endSection] ?: return@mapNotNull null
            PartnerCourse(
                id = occurrence.id,
                title = course.title.trim(),
                room = course.room.trim().takeIf { shareRoom && it.isNotEmpty() },
                teacher = course.teacher.trim().takeIf { shareTeacher && it.isNotEmpty() },
                dayOfWeek = occurrence.dayOfWeek,
                startSection = occurrence.startSection,
                endSection = occurrence.endSection,
                weeks = academicWeeksForText(occurrence.weekText),
                startTime = firstPeriod.startsAt,
                endTime = lastPeriod.endsAt,
                ownerColor = identityColor
            )
        }
    }
    return PartnerScheduleSnapshot(
        identityColor = identityColor,
        campus = campus,
        semesterStartMonday = semesterStartMonday,
        semesterEndDate = semesterEndDate,
        courses = sharedCourses
    )
}

object PartnerScheduleSnapshotCodec {
    fun encode(snapshot: PartnerScheduleSnapshot): String {
        return JSONObject()
            .put("schemaVersion", snapshot.schemaVersion)
            .put("identityColor", snapshot.identityColor.storageValue)
            .put("campus", snapshot.campus)
            .put("semesterStartMonday", snapshot.semesterStartMonday.toString())
            .put("semesterEndDate", snapshot.semesterEndDate.toString())
            .put("courses", JSONArray(snapshot.courses.map(::courseToJson)))
            .toString()
    }

    fun decode(raw: String): PartnerScheduleSnapshot {
        val root = JSONObject(raw)
        val schemaVersion = root.getInt("schemaVersion")
        require(schemaVersion == 1) { "不支持的课表快照版本" }
        val identityColor = PartnerIdentityColor.fromStorage(root.getString("identityColor"))
        val courses = root.getJSONArray("courses").objects().map { courseFromJson(it, identityColor) }
        return PartnerScheduleSnapshot(
            schemaVersion = schemaVersion,
            identityColor = identityColor,
            campus = root.getString("campus"),
            semesterStartMonday = LocalDate.parse(root.getString("semesterStartMonday")),
            semesterEndDate = LocalDate.parse(root.getString("semesterEndDate")),
            courses = courses
        )
    }

    private fun courseToJson(course: PartnerCourse): JSONObject = JSONObject()
        .put("id", course.id)
        .put("title", course.title)
        .put("room", course.room ?: JSONObject.NULL)
        .put("teacher", course.teacher ?: JSONObject.NULL)
        .put("dayOfWeek", course.dayOfWeek)
        .put("startSection", course.startSection)
        .put("endSection", course.endSection)
        .put("weeks", JSONArray(course.weeks))
        .put("startTime", course.startTime)
        .put("endTime", course.endTime)

    private fun courseFromJson(value: JSONObject, ownerColor: PartnerIdentityColor): PartnerCourse {
        val dayOfWeek = value.getInt("dayOfWeek")
        val startSection = value.getInt("startSection")
        val endSection = value.getInt("endSection")
        val startTime = value.getString("startTime")
        val endTime = value.getString("endTime")
        require(dayOfWeek in 1..7) { "星期字段无效" }
        require(startSection > 0 && endSection >= startSection) { "节次字段无效" }
        require(LocalTime.parse(startTime) < LocalTime.parse(endTime)) { "课程时间无效" }
        return PartnerCourse(
            id = value.getString("id"),
            title = value.getString("title").trim().also { require(it.isNotEmpty()) { "课程名不能为空" } },
            room = value.nullableString("room"),
            teacher = value.nullableString("teacher"),
            dayOfWeek = dayOfWeek,
            startSection = startSection,
            endSection = endSection,
            weeks = value.getJSONArray("weeks").ints(),
            startTime = startTime,
            endTime = endTime,
            ownerColor = ownerColor
        )
    }

    private fun JSONObject.nullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key).trim().takeIf(String::isNotEmpty)

    private fun JSONArray.objects(): List<JSONObject> =
        (0 until length()).map { index -> getJSONObject(index) }

    private fun JSONArray.ints(): List<Int> =
        (0 until length()).map { index -> getInt(index) }.distinct().sorted()
}
