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

data class AcademicEnrollment(
    val entranceDate: LocalDate?,
    val enrollmentYear: Int,
    val source: AcademicEnrollmentSource,
    val isConsistent: Boolean
) {
    val catalogStartDate: LocalDate = LocalDate.of(enrollmentYear, 9, 1)
}

enum class AcademicEnrollmentSource { ENTRANCE_DATE, PORTAL_FIELD, STUDENT_NUMBER }
