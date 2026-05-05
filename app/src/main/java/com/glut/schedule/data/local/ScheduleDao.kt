package com.glut.schedule.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourses(courses: List<CourseEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOccurrences(occurrences: List<CourseOccurrenceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClassPeriods(periods: List<ClassPeriodEntity>)

    @Query("DELETE FROM courses")
    suspend fun deleteCourses()

    @Query("DELETE FROM course_occurrences")
    suspend fun deleteOccurrences()

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
}
