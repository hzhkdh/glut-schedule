package com.glut.schedule.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [CourseEntity::class, CourseOccurrenceEntity::class, ClassPeriodEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ScheduleDatabase : RoomDatabase() {
    abstract fun scheduleDao(): ScheduleDao
}
