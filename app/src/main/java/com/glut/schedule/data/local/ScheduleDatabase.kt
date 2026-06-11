package com.glut.schedule.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CourseEntity::class, CourseOccurrenceEntity::class, ClassPeriodEntity::class, ExamEntity::class, ScoreEntity::class, GradeExamEntity::class, StudyPlanGroupEntity::class, StudyPlanCourseEntity::class, SemesterAdjustmentEntity::class],
    version = 8,
    exportSchema = false
)
abstract class ScheduleDatabase : RoomDatabase() {
    abstract fun scheduleDao(): ScheduleDao

    companion object {
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE semester_adjustments ADD COLUMN type TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
