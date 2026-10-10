package com.glut.schedule.partner

import com.glut.schedule.data.model.ClassPeriod
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.academicWeeksForText
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

enum class PartnerIdentityColor(
    val storageValue: String,
    val displayName: String
) {
    PINK("pink", "粉色"),
    BLUE("blue", "蓝色"),
    PURPLE("purple", "紫色"),
    TEAL("teal", "青色"),
    GREEN("green", "绿色"),
    ORANGE("orange", "橙色"),
    RED("red", "红色"),
    GOLD("gold", "金色");

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
    val startTime: String?,
    val endTime: String?,
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
 * 本机仅保存展示层信息；邀请码快照仍保持 v1，确保 Android 与微信端继续互通。
 */
data class ImportedPartnerProfile(
    val id: String,
    val name: String,
    val snapshot: PartnerScheduleSnapshot,
    val displayColor: PartnerIdentityColor
)

fun partnerProfileDefaultName(index: Int): String = "课表${index + 1}"

fun ImportedPartnerProfile.displayCourses(): List<PartnerCourse> =
    snapshot.courses.map { it.copy(ownerColor = displayColor) }

/** 只有无权威钟点的研究生来源需要 v2；已有有钟点来源继续生成 v1。 */
fun partnerSnapshotSchemaVersion(campus: String): Int = if (campus == "graduate") 2 else 1

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
    val isGraduate = campus == "graduate"
    val periodBySection = classPeriods.associateBy { it.section }
    val sharedCourses = courses.flatMap { course ->
        course.occurrences.mapNotNull { occurrence ->
            val firstPeriod = periodBySection[occurrence.startSection]
            val lastPeriod = periodBySection[occurrence.endSection]
            if (!isGraduate && (firstPeriod == null || lastPeriod == null)) return@mapNotNull null
            PartnerCourse(
                // 本地 occurrence.id 可能由教室/教师参与生成，不能跨越隐私开关上传。
                id = "",
                title = course.title.trim(),
                room = course.room.trim().takeIf { shareRoom && it.isNotEmpty() },
                teacher = course.teacher.trim().takeIf { shareTeacher && it.isNotEmpty() },
                dayOfWeek = occurrence.dayOfWeek,
                startSection = occurrence.startSection,
                endSection = occurrence.endSection,
                weeks = academicWeeksForText(occurrence.weekText),
                // 研究生门户没有权威钟点，不能把测试期遗留的自定义作息写入邀请码。
                startTime = if (isGraduate) null else firstPeriod!!.startsAt,
                endTime = if (isGraduate) null else lastPeriod!!.endsAt,
                ownerColor = identityColor
            )
        }
    }.mapIndexed { index, course ->
        // v1 的 id 只用于快照内稳定区分课程，不承载本地数据库或教务系统身份。
        course.copy(id = "shared-${index + 1}")
    }
    return PartnerScheduleSnapshot(
        schemaVersion = partnerSnapshotSchemaVersion(campus),
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
        require(schemaVersion == 1 || schemaVersion == 2) { "不支持的课表快照版本" }
        val identityColor = PartnerIdentityColor.fromStorage(root.getString("identityColor"))
        val courses = root.getJSONArray("courses").objects().map {
            courseFromJson(it, identityColor, schemaVersion)
        }
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
        .put("startTime", course.startTime ?: JSONObject.NULL)
        .put("endTime", course.endTime ?: JSONObject.NULL)

    private fun courseFromJson(
        value: JSONObject,
        ownerColor: PartnerIdentityColor,
        schemaVersion: Int
    ): PartnerCourse {
        val dayOfWeek = value.getInt("dayOfWeek")
        val startSection = value.getInt("startSection")
        val endSection = value.getInt("endSection")
        val startTime = value.nullableTimeString("startTime")
        val endTime = value.nullableTimeString("endTime")
        require(dayOfWeek in 1..7) { "星期字段无效" }
        require(startSection > 0 && endSection >= startSection) { "节次字段无效" }
        val hasUnknownTimes = schemaVersion == 2 && startTime == null && endTime == null
        val hasValidTimes = startTime != null && endTime != null && runCatching {
            LocalTime.parse(startTime) < LocalTime.parse(endTime)
        }.getOrDefault(false)
        require(hasUnknownTimes || hasValidTimes) { "课程时间无效" }
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

    /** 时间字段只有 JSON null 表示未知；空字符串必须留给校验器拒绝。 */
    private fun JSONObject.nullableTimeString(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key).trim()

    private fun JSONArray.objects(): List<JSONObject> =
        (0 until length()).map { index -> getJSONObject(index) }

    private fun JSONArray.ints(): List<Int> =
        (0 until length()).map { index -> getInt(index) }.distinct().sorted()
}
