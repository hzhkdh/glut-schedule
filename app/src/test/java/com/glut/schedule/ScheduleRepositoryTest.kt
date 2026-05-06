package com.glut.schedule

import com.glut.schedule.data.local.ClassPeriodEntity
import com.glut.schedule.data.local.CourseEntity
import com.glut.schedule.data.local.CourseOccurrenceEntity
import com.glut.schedule.data.local.ScheduleDao
import com.glut.schedule.data.repository.ScheduleRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleRepositoryTest {
    @Test
    fun seedIfEmptyOnlySeedsClassPeriodsNotPersonalSampleCourses() = runTest {
        val dao = FakeScheduleDao(courseCount = 0)
        val repository = ScheduleRepository(dao)

        repository.seedIfEmpty()

        assertEquals(12, dao.insertedPeriods.size)
        assertEquals(0, dao.insertedCourses.size)
        assertEquals(0, dao.insertedOccurrences.size)
    }

    @Test
    fun seedIfEmptyClearsLegacyBundledSampleCourses() = runTest {
        val dao = FakeScheduleDao(
            courseCount = 8,
            courseIds = listOf(
                "digital-logic",
                "embedded",
                "english",
                "machine-learning",
                "politics",
                "os",
                "java",
                "algorithm"
            )
        )
        val repository = ScheduleRepository(dao)

        repository.seedIfEmpty()

        assertEquals(
            listOf(
                "algorithm",
                "digital-logic",
                "embedded",
                "english",
                "java",
                "machine-learning",
                "os",
                "politics"
            ),
            dao.deletedCourseIds.sorted()
        )
        assertEquals(1, dao.deleteOccurrencesForCoursesCalls)
    }

    @Test
    fun seedIfEmptyKeepsImportedCourses() = runTest {
        val dao = FakeScheduleDao(
            courseCount = 2,
            courseIds = listOf("import-abc", "import-def")
        )
        val repository = ScheduleRepository(dao)

        repository.seedIfEmpty()

        assertEquals(0, dao.deleteOccurrencesCalls)
        assertEquals(0, dao.deleteCoursesCalls)
    }

    @Test
    fun seedIfEmptyDoesNotDeleteImportedCoursesWhenLegacyCleanupRacesWithImport() = runTest {
        val dao = FakeScheduleDao(
            courseIds = listOf(
                "digital-logic",
                "embedded",
                "english",
                "machine-learning",
                "politics",
                "os",
                "java",
                "algorithm"
            ),
            courseIdsAfterRead = listOf("import-current")
        )
        val repository = ScheduleRepository(dao)

        repository.seedIfEmpty()

        assertEquals(listOf("import-current"), dao.currentCourseIds)
    }

    private class FakeScheduleDao(
        courseCount: Int? = null,
        courseIds: List<String> = emptyList(),
        private val courseIdsAfterRead: List<String>? = null
    ) : ScheduleDao {
        val currentCourseIds = courseIds.toMutableList()
        private val initialCourseCount = courseCount
        val insertedPeriods = mutableListOf<ClassPeriodEntity>()
        val insertedCourses = mutableListOf<CourseEntity>()
        val insertedOccurrences = mutableListOf<CourseOccurrenceEntity>()
        val deletedCourseIds = mutableListOf<String>()
        var deleteCoursesCalls = 0
        var deleteOccurrencesCalls = 0
        var deleteOccurrencesForCoursesCalls = 0

        override fun observeCourses(): Flow<List<CourseEntity>> = flowOf(emptyList())

        override fun observeOccurrences(): Flow<List<CourseOccurrenceEntity>> = flowOf(emptyList())

        override fun observeClassPeriods(): Flow<List<ClassPeriodEntity>> = flowOf(emptyList())

        override suspend fun courseCount(): Int = initialCourseCount ?: currentCourseIds.size

        override suspend fun courseIds(): List<String> {
            val ids = currentCourseIds.toList()
            if (courseIdsAfterRead != null) {
                currentCourseIds.clear()
                currentCourseIds.addAll(courseIdsAfterRead)
            }
            return ids
        }

        override suspend fun insertCourses(courses: List<CourseEntity>) {
            insertedCourses.addAll(courses)
        }

        override suspend fun insertOccurrences(occurrences: List<CourseOccurrenceEntity>) {
            insertedOccurrences.addAll(occurrences)
        }

        override suspend fun insertClassPeriods(periods: List<ClassPeriodEntity>) {
            insertedPeriods.addAll(periods)
        }

        override suspend fun deleteCourses() {
            deleteCoursesCalls++
            currentCourseIds.clear()
        }

        override suspend fun deleteOccurrences() {
            deleteOccurrencesCalls++
        }

        override suspend fun deleteCoursesByIds(courseIds: List<String>) {
            deletedCourseIds.addAll(courseIds)
            currentCourseIds.removeAll(courseIds.toSet())
        }

        override suspend fun deleteOccurrencesForCourses(courseIds: List<String>) {
            deleteOccurrencesForCoursesCalls++
        }
    }
}
