package com.glut.schedule

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleSourceMigrationTest {
    @Test
    fun migrationMarksCompletionOnlyAfterCacheInvalidationSucceeds() = runTest {
        val calls = mutableListOf<String>()

        val migrated = migrateScheduleSourceIfNeeded(
            alreadyMigrated = false,
            invalidateCaches = { calls += "invalidate" },
            markMigrated = { calls += "mark" }
        )

        assertTrue(migrated)
        assertEquals(listOf("invalidate", "mark"), calls)
    }

    @Test
    fun completedMigrationDoesNotInvalidateCachesAgain() = runTest {
        var invalidated = false

        val migrated = migrateScheduleSourceIfNeeded(
            alreadyMigrated = true,
            invalidateCaches = { invalidated = true },
            markMigrated = { error("不应重复写标记") }
        )

        assertFalse(migrated)
        assertFalse(invalidated)
    }
}
