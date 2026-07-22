package com.glut.schedule.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduleDatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun deleteDatabaseBeforeTest() {
        context.deleteDatabase(TEST_DATABASE)
    }

    @After
    fun deleteDatabaseAfterTest() {
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun migration8To9ReferencesFinalCoursesTableAndPreservesRows() {
        createVersion8Database().use { helper ->
            helper.writableDatabase.apply {
                execSQL(
                    "INSERT INTO courses (id, title, room, teacher, colorHex) " +
                        "VALUES ('course-1', '高等数学', 'A101', '张老师', '#123456')"
                )
                execSQL(
                    "INSERT INTO course_occurrences " +
                        "(id, courseId, dayOfWeek, startSection, endSection, weekText, note) " +
                        "VALUES ('occurrence-1', 'course-1', 1, 1, 2, '1-16周', '旧数据')"
                )
            }
        }

        migrateToVersion9().use { helper ->
            val database = helper.writableDatabase

            database.query("PRAGMA foreign_key_list(`course_occurrences`)").use { cursor ->
                assertEquals(2, cursor.count)
                while (cursor.moveToNext()) {
                    assertEquals(
                        "courses",
                        cursor.getString(cursor.getColumnIndexOrThrow("table"))
                    )
                }
            }
            database.query(
                "SELECT title, semesterId FROM courses WHERE id = 'course-1'"
            ).use { cursor ->
                assertEquals(1, cursor.count)
                cursor.moveToFirst()
                assertEquals("高等数学", cursor.getString(0))
                assertEquals("legacy-current", cursor.getString(1))
            }
            database.query(
                "SELECT courseId, semesterId FROM course_occurrences WHERE id = 'occurrence-1'"
            ).use { cursor ->
                assertEquals(1, cursor.count)
                cursor.moveToFirst()
                assertEquals("course-1", cursor.getString(0))
                assertEquals("legacy-current", cursor.getString(1))
            }
        }
    }

    private fun createVersion8Database(): SupportSQLiteOpenHelper = openHelper(
        version = 8,
        onCreate = { database ->
            database.execSQL(
                "CREATE TABLE courses (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, " +
                    "room TEXT NOT NULL, teacher TEXT NOT NULL, colorHex TEXT NOT NULL)"
            )
            database.execSQL(
                "CREATE TABLE course_occurrences (id TEXT NOT NULL PRIMARY KEY, " +
                    "courseId TEXT NOT NULL, dayOfWeek INTEGER NOT NULL, " +
                    "startSection INTEGER NOT NULL, endSection INTEGER NOT NULL, " +
                    "weekText TEXT NOT NULL, note TEXT NOT NULL)"
            )
            database.execSQL(
                "CREATE TABLE class_periods (section INTEGER NOT NULL PRIMARY KEY, " +
                    "startsAt TEXT NOT NULL, endsAt TEXT NOT NULL)"
            )
            database.execSQL(
                "CREATE TABLE semester_adjustments (id TEXT NOT NULL PRIMARY KEY, " +
                    "type TEXT NOT NULL, title TEXT NOT NULL, teacher TEXT NOT NULL, " +
                    "originalWeek INTEGER NOT NULL, originalDay INTEGER NOT NULL, " +
                    "originalStartSection INTEGER NOT NULL, originalEndSection INTEGER NOT NULL, " +
                    "originalRoom TEXT NOT NULL, makeupWeek INTEGER NOT NULL, " +
                    "makeupDay INTEGER NOT NULL, makeupStartSection INTEGER NOT NULL, " +
                    "makeupEndSection INTEGER NOT NULL, makeupRoom TEXT NOT NULL)"
            )
        }
    )

    private fun migrateToVersion9(): SupportSQLiteOpenHelper = openHelper(
        version = 9,
        onCreate = { error("Expected the version 8 fixture to exist") },
        onUpgrade = { database, oldVersion, newVersion ->
            assertEquals(8, oldVersion)
            assertEquals(9, newVersion)
            ScheduleDatabase.MIGRATION_8_9.migrate(database)
        }
    )

    private fun openHelper(
        version: Int,
        onCreate: (SupportSQLiteDatabase) -> Unit,
        onUpgrade: (SupportSQLiteDatabase, Int, Int) -> Unit = { _, _, _ -> }
    ): SupportSQLiteOpenHelper {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DATABASE)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) = onCreate(db)

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int
                ) = onUpgrade(db, oldVersion, newVersion)
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration)
    }

    private companion object {
        const val TEST_DATABASE = "schedule-migration-test.db"
    }
}
