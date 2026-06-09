package com.glut.schedule.data.repository

import com.glut.schedule.data.local.ScheduleDao
import com.glut.schedule.data.local.toEntity
import com.glut.schedule.data.local.toModel
import com.glut.schedule.data.model.ClassPeriod
import com.glut.schedule.data.model.CourseColorMapper
import com.glut.schedule.data.model.ExamInfo
import com.glut.schedule.data.model.GradeExamInfo
import com.glut.schedule.data.model.ScoreInfo
import com.glut.schedule.data.model.StudyPlanGroup
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.SemesterAdjustment
import com.glut.schedule.data.model.defaultClassPeriods
import com.glut.schedule.data.model.guilinClassPeriods
import com.glut.schedule.data.model.nanningClassPeriods
import com.glut.schedule.data.settings.CampusType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class ScheduleRepository(
    private val dao: ScheduleDao,
    private val campusType: Flow<CampusType>
) {
    val courses: Flow<List<ScheduleCourse>> = combine(
        dao.observeCourses(),
        dao.observeOccurrences()
    ) { courses, occurrences ->
        val occurrencesByCourse = occurrences.map { it.toModel() }.groupBy { it.courseId }
        courses.map { course -> course.toModel(occurrencesByCourse[course.id].orEmpty()) }
    }

    val classPeriods: Flow<List<ClassPeriod>> = combine(
        dao.observeClassPeriods(),
        campusType
    ) { periods, campusType ->
        periods.map { it.toModel() }.ifEmpty {
            when (campusType) {
                CampusType.GUILIN -> guilinClassPeriods()
                CampusType.NANNING -> nanningClassPeriods()
            }
        }
    }

    suspend fun seedIfEmpty() {
        dao.insertClassPeriods(defaultClassPeriods().map { it.toEntity() })

        if (dao.courseCount() > 0) {
            clearLegacyBundledSampleCoursesIfPresent()
        }
    }

    private suspend fun clearLegacyBundledSampleCoursesIfPresent() {
        val ids = dao.courseIds().toSet()
        if (ids == legacyBundledSampleCourseIds) {
            val legacyIds = legacyBundledSampleCourseIds.toList()
            dao.deleteOccurrencesForCourses(legacyIds)
            dao.deleteCoursesByIds(legacyIds)
        }
    }

    val exams: Flow<List<ExamInfo>> = dao.observeExams().map { entities ->
        entities.map { it.toModel() }
    }

    suspend fun replaceExams(exams: List<ExamInfo>) {
        dao.replaceExams(exams.map { it.toEntity() })
    }

    val scores: Flow<List<ScoreInfo>> = dao.observeScores().map { entities ->
        entities.map { it.toModel() }
    }

    suspend fun replaceScores(scores: List<ScoreInfo>) {
        dao.replaceScores(scores.map { it.toEntity() })
    }

    val gradeExams: Flow<List<GradeExamInfo>> = dao.observeGradeExams().map { entities ->
        entities.map { it.toModel() }
    }

    suspend fun replaceGradeExams(exams: List<GradeExamInfo>) {
        dao.replaceGradeExams(exams.map { it.toEntity() })
    }

    val studyPlanGroups: Flow<List<StudyPlanGroup>> = dao.observeStudyPlanGroups().map { entities ->
        entities.map { it.toModel() }
    }

    suspend fun replaceStudyPlanGroups(groups: List<StudyPlanGroup>) {
        dao.replaceStudyPlanGroups(groups.map { it.toEntity() })
    }

    val semesterAdjustments: Flow<List<SemesterAdjustment>> = dao.observeSemesterAdjustments().map { entities ->
        entities.map { it.toModel() }
    }

    suspend fun replaceSemesterAdjustments(adjustments: List<SemesterAdjustment>) {
        dao.replaceSemesterAdjustments(adjustments.map { it.toEntity() })
    }

    suspend fun replaceImportedCourses(courses: List<ScheduleCourse>) {
        // Write campus-specific periods so the schedule grid shows correct time slots.
        val campus = campusType.first()
        val periods = when (campus) {
            CampusType.GUILIN -> guilinClassPeriods()
            CampusType.NANNING -> nanningClassPeriods()
        }
        dao.insertClassPeriods(periods.map { it.toEntity() })
        val coloredCourses = CourseColorMapper.assignColors(courses)
        dao.replaceCourses(
            courses = coloredCourses.map { it.toEntity() },
            occurrences = coloredCourses.flatMap { course -> course.occurrences.map { it.toEntity() } }
        )
    }

    private companion object {
        val legacyBundledSampleCourseIds = setOf(
            "digital-logic",
            "embedded",
            "english",
            "machine-learning",
            "politics",
            "os",
            "java",
            "algorithm"
        )
    }
}
