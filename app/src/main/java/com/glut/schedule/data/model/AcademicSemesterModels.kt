package com.glut.schedule.data.model

import com.glut.schedule.data.settings.CampusType
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
    val portalMaxWeek: Int? = null
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
            portalMaxWeek: Int? = null
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
                portalMaxWeek = portalMaxWeek
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
