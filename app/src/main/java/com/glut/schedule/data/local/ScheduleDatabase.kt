package com.glut.schedule.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [AcademicSemesterEntity::class, CourseEntity::class, CourseOccurrenceEntity::class, ClassPeriodEntity::class, ExamEntity::class, ScoreEntity::class, GradeExamEntity::class, StudyPlanGroupEntity::class, StudyPlanCourseEntity::class, SemesterAdjustmentEntity::class],
    version = 9,
    exportSchema = true
)
abstract class ScheduleDatabase : RoomDatabase() {
    abstract fun scheduleDao(): ScheduleDao

    companion object {
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE semester_adjustments ADD COLUMN type TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `academic_semesters` (
                        `id` TEXT NOT NULL,
                        `campus` TEXT NOT NULL,
                        `portalYear` INTEGER NOT NULL,
                        `portalYearId` TEXT NOT NULL,
                        `season` TEXT NOT NULL,
                        `portalTermId` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `isCurrent` INTEGER NOT NULL,
                        `cacheStatus` TEXT NOT NULL,
                        `importedAtEpochMillis` INTEGER,
                        `semesterStartDate` TEXT,
                        `semesterEndDate` TEXT,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT OR IGNORE INTO `academic_semesters`
                    (`id`, `campus`, `portalYear`, `portalYearId`, `season`, `portalTermId`, `displayName`, `isCurrent`, `cacheStatus`, `importedAtEpochMillis`, `semesterStartDate`, `semesterEndDate`)
                    VALUES ('legacy-current', 'GUILIN', 0, '', 'SPRING', '', '当前学期（待确认）', 1, 'CACHED', NULL, NULL, NULL)
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE `courses_new` (
                        `id` TEXT NOT NULL, `title` TEXT NOT NULL, `room` TEXT NOT NULL,
                        `teacher` TEXT NOT NULL, `colorHex` TEXT NOT NULL, `semesterId` TEXT NOT NULL,
                        PRIMARY KEY(`semesterId`, `id`),
                        FOREIGN KEY(`semesterId`) REFERENCES `academic_semesters`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("INSERT INTO `courses_new` SELECT `id`, `title`, `room`, `teacher`, `colorHex`, 'legacy-current' FROM `courses`")

                db.execSQL("""
                    CREATE TABLE `class_periods_new` (
                        `section` INTEGER NOT NULL, `startsAt` TEXT NOT NULL, `endsAt` TEXT NOT NULL,
                        `semesterId` TEXT NOT NULL, PRIMARY KEY(`semesterId`, `section`),
                        FOREIGN KEY(`semesterId`) REFERENCES `academic_semesters`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("INSERT INTO `class_periods_new` SELECT `section`, `startsAt`, `endsAt`, 'legacy-current' FROM `class_periods`")

                db.execSQL("""
                    CREATE TABLE `semester_adjustments_new` (
                        `id` TEXT NOT NULL, `type` TEXT NOT NULL, `title` TEXT NOT NULL, `teacher` TEXT NOT NULL,
                        `originalWeek` INTEGER NOT NULL, `originalDay` INTEGER NOT NULL,
                        `originalStartSection` INTEGER NOT NULL, `originalEndSection` INTEGER NOT NULL,
                        `originalRoom` TEXT NOT NULL, `makeupWeek` INTEGER NOT NULL, `makeupDay` INTEGER NOT NULL,
                        `makeupStartSection` INTEGER NOT NULL, `makeupEndSection` INTEGER NOT NULL,
                        `makeupRoom` TEXT NOT NULL, `semesterId` TEXT NOT NULL,
                        PRIMARY KEY(`semesterId`, `id`),
                        FOREIGN KEY(`semesterId`) REFERENCES `academic_semesters`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("INSERT INTO `semester_adjustments_new` SELECT `id`, `type`, `title`, `teacher`, `originalWeek`, `originalDay`, `originalStartSection`, `originalEndSection`, `originalRoom`, `makeupWeek`, `makeupDay`, `makeupStartSection`, `makeupEndSection`, `makeupRoom`, 'legacy-current' FROM `semester_adjustments`")

                db.execSQL("DROP TABLE `courses`")
                db.execSQL("DROP TABLE `class_periods`")
                db.execSQL("DROP TABLE `semester_adjustments`")
                db.execSQL("ALTER TABLE `courses_new` RENAME TO `courses`")
                db.execSQL("""
                    CREATE TABLE `course_occurrences_new` (
                        `id` TEXT NOT NULL, `courseId` TEXT NOT NULL, `dayOfWeek` INTEGER NOT NULL,
                        `startSection` INTEGER NOT NULL, `endSection` INTEGER NOT NULL,
                        `weekText` TEXT NOT NULL, `note` TEXT NOT NULL, `semesterId` TEXT NOT NULL,
                        PRIMARY KEY(`semesterId`, `id`),
                        FOREIGN KEY(`semesterId`, `courseId`) REFERENCES `courses`(`semesterId`, `id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("INSERT INTO `course_occurrences_new` SELECT `id`, `courseId`, `dayOfWeek`, `startSection`, `endSection`, `weekText`, `note`, 'legacy-current' FROM `course_occurrences`")
                db.execSQL("DROP TABLE `course_occurrences`")
                db.execSQL("ALTER TABLE `course_occurrences_new` RENAME TO `course_occurrences`")
                db.execSQL("ALTER TABLE `class_periods_new` RENAME TO `class_periods`")
                db.execSQL("ALTER TABLE `semester_adjustments_new` RENAME TO `semester_adjustments`")
                db.execSQL("CREATE INDEX `index_courses_semesterId` ON `courses` (`semesterId`)")
                db.execSQL("CREATE INDEX `index_course_occurrences_semesterId_courseId` ON `course_occurrences` (`semesterId`, `courseId`)")
                db.execSQL("CREATE INDEX `index_class_periods_semesterId` ON `class_periods` (`semesterId`)")
                db.execSQL("CREATE INDEX `index_semester_adjustments_semesterId` ON `semester_adjustments` (`semesterId`)")
            }
        }
    }
}
