package com.glut.schedule.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [CourseEntity::class, CourseOccurrenceEntity::class, ClassPeriodEntity::class, ExamEntity::class, ScoreEntity::class, GradeExamEntity::class, StudyPlanGroupEntity::class, StudyPlanCourseEntity::class, SemesterAdjustmentEntity::class],
    version = 7,
    exportSchema = false
)
abstract class ScheduleDatabase : RoomDatabase() {
    abstract fun scheduleDao(): ScheduleDao
}
