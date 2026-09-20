package com.glut.schedule.data.model

import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.data.settings.SemesterImportMode
import java.time.LocalDate

enum class SemesterSeason { SPRING, AUTUMN }

enum class SemesterCacheStatus { NOT_CACHED, CACHED, DOWNLOADING, FAILED }

data class AcademicSemester(
    val id: String,
    val campus: CampusType,
    val portalYear: Int,
    val portalYearId: String,
    val season: SemesterSeason,
    val portalTermId: String,
    val displayName: String,
    val isCurrent: Boolean,
    val cacheStatus: SemesterCacheStatus = SemesterCacheStatus.NOT_CACHED,
    val importedAtEpochMillis: Long? = null,
    val semesterStartDate: LocalDate? = null,
    val semesterEndDate: LocalDate? = null,
    val portalMaxWeek: Int? = null,
    /**
     * 该学期是用哪条线路导入的。
     *
     * 模式2 的快照没有逐周锚点、`portalMaxWeek` 来源也不同，不记录会让后续所有诊断
     * 退化成考古；同时缓存重下时也要靠它告诉用户「该学期原本是模式1 缓存的」。
     */
    val importMode: SemesterImportMode = SemesterImportMode.WEEKLY
) {
    companion object {
        const val LEGACY_CURRENT_ID = "legacy-current"

        fun create(
            campus: CampusType,
            portalYear: Int,
            portalYearId: String,
            season: SemesterSeason,
            portalTermId: String,
            isCurrent: Boolean,
            cacheStatus: SemesterCacheStatus = SemesterCacheStatus.NOT_CACHED,
            importedAtEpochMillis: Long? = null,
            semesterStartDate: LocalDate? = null,
            semesterEndDate: LocalDate? = null,
            portalMaxWeek: Int? = null,
            importMode: SemesterImportMode = SemesterImportMode.WEEKLY
        ): AcademicSemester {
            val campusKey = campus.name.lowercase()
            val seasonKey = season.name.lowercase()
            val seasonLabel = if (season == SemesterSeason.SPRING) "春" else "秋"
            val resolvedYearId = portalYearId.ifBlank { (portalYear - 1980).toString() }
            val resolvedTermId = portalTermId.ifBlank {
                when (season) {
                    SemesterSeason.SPRING -> "1"
                    SemesterSeason.AUTUMN -> if (campus == CampusType.GUILIN) "2" else "3"
                }
            }
            return AcademicSemester(
                id = "$campusKey:$portalYear:$seasonKey",
                campus = campus,
                portalYear = portalYear,
                portalYearId = resolvedYearId,
                season = season,
                portalTermId = resolvedTermId,
                displayName = "$portalYear·$seasonLabel",
                isCurrent = isCurrent,
                cacheStatus = cacheStatus,
                importedAtEpochMillis = importedAtEpochMillis,
                semesterStartDate = semesterStartDate,
                semesterEndDate = semesterEndDate,
                portalMaxWeek = portalMaxWeek,
                importMode = importMode
            )
        }
    }
}

/** 线路标签，与导入页线路选择器上的文案保持一致。 */
fun SemesterImportMode.displayLabel(): String = when (this) {
    SemesterImportMode.WEEKLY -> "模式1"
    SemesterImportMode.PERSONAL_ONLY -> "模式2"
}

/**
 * 「重下会换线路」的提前说明；不需要提示时返回空串。
 *
 * 重下会改变课表时间/教室的取值口径（模式1 以周次课表为准，模式2 全部来自个人课表），
 * 用户不知情就会以为数据出错或丢了，所以必须在按下「重新下载」之前说清楚。
 *
 * 只在「确实可以重下」的学期上提示：当前学期是「正在查看」、没有重下入口；
 * 未缓存的学期也没有可对比的线路。判据必须与界面上的 canRedownload 保持一致，
 * 否则会出现「有提示却没有重下按钮」的悬空文案。
 */
fun semesterImportModeSwitchHint(
    semester: AcademicSemester,
    currentMode: SemesterImportMode
): String {
    if (semester.isCurrent) return ""
    if (semester.cacheStatus != SemesterCacheStatus.CACHED) return ""
    if (semester.importMode == currentMode) return ""
    return "该学期原用${semester.importMode.displayLabel()}缓存，" +
        "重新下载将改用${currentMode.displayLabel()}"
}

data class AcademicEnrollment(
    val entranceDate: LocalDate?,
    val enrollmentYear: Int,
    val source: AcademicEnrollmentSource,
    val isConsistent: Boolean
) {
    val catalogStartDate: LocalDate = LocalDate.of(enrollmentYear, 9, 1)
}

enum class AcademicEnrollmentSource { ENTRANCE_DATE, PORTAL_FIELD, STUDENT_NUMBER }
