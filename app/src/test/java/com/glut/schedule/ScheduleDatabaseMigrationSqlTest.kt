package com.glut.schedule

import androidx.sqlite.db.SupportSQLiteDatabase
import com.glut.schedule.data.local.ScheduleDatabase
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class ScheduleDatabaseMigrationSqlTest {
    @Test
    fun migration8To9BuildsOccurrenceForeignKeyAgainstFinalCoursesTable() {
        val statements = mutableListOf<String>()
        val database = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, arguments ->
            if (method.name == "execSQL") {
                statements += arguments.orEmpty().first() as String
            }
            null
        } as SupportSQLiteDatabase

        ScheduleDatabase.MIGRATION_8_9.migrate(database)

        val parentRename = statements.indexOfFirst {
            it.contains("ALTER TABLE `courses_new` RENAME TO `courses`")
        }
        val childCreate = statements.indexOfFirst {
            it.contains("CREATE TABLE `course_occurrences_new`")
        }
        assertTrue(
            "course_occurrences must be rebuilt after the final courses table exists",
            parentRename in 0 until childCreate
        )
        assertTrue(
            statements[childCreate].contains("REFERENCES `courses`(`semesterId`, `id`)")
        )
    }
}
