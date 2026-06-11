package com.glut.schedule.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.glut.schedule.data.local.ScoreEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM courses ORDER BY title")
    fun observeCourses(): Flow<List<CourseEntity>>

    @Query("SELECT * FROM course_occurrences ORDER BY dayOfWeek, startSection")
    fun observeOccurrences(): Flow<List<CourseOccurrenceEntity>>

    @Query("SELECT * FROM class_periods ORDER BY section")
    fun observeClassPeriods(): Flow<List<ClassPeriodEntity>>

    @Query("SELECT COUNT(*) FROM courses")
    suspend fun courseCount(): Int

    @Query("SELECT id FROM courses")
    suspend fun courseIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourses(courses: List<CourseEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOccurrences(occurrences: List<CourseOccurrenceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClassPeriods(periods: List<ClassPeriodEntity>)

    @Query("DELETE FROM class_periods")
    suspend fun deleteClassPeriods()

    @Transaction
    suspend fun replaceClassPeriods(periods: List<ClassPeriodEntity>) {
        deleteClassPeriods()
        insertClassPeriods(periods)
    }

    @Query("DELETE FROM courses")
    suspend fun deleteCourses()

    @Query("DELETE FROM course_occurrences")
    suspend fun deleteOccurrences()

    @Query("DELETE FROM courses WHERE id IN (:courseIds)")
    suspend fun deleteCoursesByIds(courseIds: List<String>)

    @Query("DELETE FROM course_occurrences WHERE courseId IN (:courseIds)")
    suspend fun deleteOccurrencesForCourses(courseIds: List<String>)

    @Transaction
    suspend fun replaceCourses(
        courses: List<CourseEntity>,
        occurrences: List<CourseOccurrenceEntity>
    ) {
        deleteOccurrences()
        deleteCourses()
        insertCourses(courses)
        insertOccurrences(occurrences)
    }

    @Query("SELECT * FROM exams ORDER BY examDate, startTime")
    fun observeExams(): Flow<List<ExamEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExams(exams: List<ExamEntity>)

    @Query("DELETE FROM exams")
    suspend fun deleteAllExams()

    @Transaction
    suspend fun replaceExams(exams: List<ExamEntity>) {
        deleteAllExams()
        insertExams(exams)
    }

    @Query("SELECT * FROM scores ORDER BY year DESC, term DESC, courseName")
    fun observeScores(): Flow<List<ScoreEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScores(scores: List<ScoreEntity>)

    @Query("DELETE FROM scores")
    suspend fun deleteAllScores()

    @Transaction
    suspend fun replaceScores(scores: List<ScoreEntity>) {
        deleteAllScores()
        insertScores(scores)
    }

    @Query("SELECT * FROM grade_exams ORDER BY examTime DESC")
    fun observeGradeExams(): Flow<List<GradeExamEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGradeExams(exams: List<GradeExamEntity>)

    @Query("DELETE FROM grade_exams")
    suspend fun deleteAllGradeExams()

    @Transaction
    suspend fun replaceGradeExams(exams: List<GradeExamEntity>) {
        deleteAllGradeExams()
        insertGradeExams(exams)
    }

    @Query("SELECT * FROM study_plan_groups ORDER BY groupName")
    fun observeStudyPlanGroups(): Flow<List<StudyPlanGroupEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStudyPlanGroups(groups: List<StudyPlanGroupEntity>)

    @Query("DELETE FROM study_plan_groups")
    suspend fun deleteAllStudyPlanGroups()

    @Transaction
    suspend fun replaceStudyPlanGroups(groups: List<StudyPlanGroupEntity>) {
        deleteAllStudyPlanGroups()
        insertStudyPlanGroups(groups)
    }

    @Query("SELECT * FROM study_plan_courses ORDER BY groupId, courseName")
    fun observeStudyPlanCourses(): Flow<List<StudyPlanCourseEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStudyPlanCourses(courses: List<StudyPlanCourseEntity>)

    @Query("DELETE FROM study_plan_courses")
    suspend fun deleteAllStudyPlanCourses()

    @Transaction
    suspend fun replaceStudyPlanData(
        groups: List<StudyPlanGroupEntity>,
        courses: List<StudyPlanCourseEntity>
    ) {
        deleteAllStudyPlanGroups()
        deleteAllStudyPlanCourses()
        insertStudyPlanGroups(groups)
        insertStudyPlanCourses(courses)
    }

    @Query("SELECT * FROM semester_adjustments ORDER BY makeupWeek, makeupDay, makeupStartSection")
    fun observeSemesterAdjustments(): Flow<List<SemesterAdjustmentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSemesterAdjustments(adjustments: List<SemesterAdjustmentEntity>)

    @Query("DELETE FROM semester_adjustments")
    suspend fun deleteAllSemesterAdjustments()

    @Transaction
    suspend fun replaceSemesterAdjustments(adjustments: List<SemesterAdjustmentEntity>) {
        deleteAllSemesterAdjustments()
        insertSemesterAdjustments(adjustments)
    }
}
