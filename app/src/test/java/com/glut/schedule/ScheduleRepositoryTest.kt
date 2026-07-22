package com.glut.schedule

import com.glut.schedule.data.local.ClassPeriodEntity
import com.glut.schedule.data.local.AcademicSemesterEntity
import com.glut.schedule.data.local.CourseEntity
import com.glut.schedule.data.local.CourseOccurrenceEntity
import com.glut.schedule.data.local.ExamEntity
import com.glut.schedule.data.local.GradeExamEntity
import com.glut.schedule.data.local.ScheduleDao
import com.glut.schedule.data.local.ScoreEntity
import com.glut.schedule.data.local.toEntity
import com.glut.schedule.data.model.AcademicSemester
import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.SemesterSeason
import com.glut.schedule.data.repository.ScheduleRepository
import com.glut.schedule.data.settings.CampusType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleRepositoryTest {
    @Test
    fun replacingScheduleOnlyTargetsSelectedSemester() = runTest {
        val dao = FakeScheduleDao()
        val repository = ScheduleRepository(dao, flowOf(CampusType.GUILIN))
        val autumn = AcademicSemester.create(
            CampusType.GUILIN, 2024, "44", SemesterSeason.AUTUMN, "2", isCurrent = false
        )
        val spring = AcademicSemester.create(
            CampusType.GUILIN, 2025, "45", SemesterSeason.SPRING, "1", isCurrent = true
        )

        repository.replaceSemesterSchedule(autumn, listOf(course("shared", "历史课程")), emptyList())
        repository.replaceSemesterSchedule(spring, listOf(course("shared", "当前课程")), emptyList())

        assertEquals(listOf(autumn.id, spring.id), dao.replacedSemesterIds)
        assertEquals(2, dao.insertedCourses.map { it.semesterId }.distinct().size)
        repository.selectSemester(autumn.id)
        assertEquals("历史课程", repository.courses.first().single().title)
        assertEquals(spring.id, repository.currentSemester.first()?.id)
    }

    @Test
    fun seedIfEmptyOnlySeedsClassPeriodsNotPersonalSampleCourses() = runTest {
        val dao = FakeScheduleDao(courseCount = 0)
        val repository = ScheduleRepository(dao, flowOf(CampusType.GUILIN))

        repository.seedIfEmpty()

        assertEquals(14, dao.insertedPeriods.size)
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
        val repository = ScheduleRepository(dao, flowOf(CampusType.GUILIN))

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
        val repository = ScheduleRepository(dao, flowOf(CampusType.GUILIN))

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
        val repository = ScheduleRepository(dao, flowOf(CampusType.GUILIN))

        repository.seedIfEmpty()

        assertEquals(listOf("import-current"), dao.currentCourseIds)
    }

    @Test
    fun seedIfEmptyDoesNotRestoreLegacyAfterCanonicalCurrentExists() = runTest {
        val current = AcademicSemester.create(
            CampusType.GUILIN, 2025, "45", SemesterSeason.SPRING, "1", isCurrent = true
        ).toEntity()
        val dao = FakeScheduleDao(initialSemesters = listOf(current))
        val repository = ScheduleRepository(dao, flowOf(CampusType.GUILIN))

        repository.seedIfEmpty()

        assertEquals(listOf(current.id), dao.semesterIds)
    }

    private fun course(id: String, title: String) = ScheduleCourse(
        id = id,
        title = title,
        room = "A101",
        teacher = "教师",
        colorHex = "#4477AA",
        occurrences = listOf(
            CourseOccurrence("$id-occ", id, 1, 1, 2, "1-16周", "")
        )
    )

    private class FakeScheduleDao(
        courseCount: Int? = null,
        courseIds: List<String> = emptyList(),
        private val courseIdsAfterRead: List<String>? = null,
        initialSemesters: List<AcademicSemesterEntity> = emptyList()
    ) : ScheduleDao {
        val currentCourseIds = courseIds.toMutableList()
        private val initialCourseCount = courseCount
        val insertedPeriods = mutableListOf<ClassPeriodEntity>()
        val insertedCourses = mutableListOf<CourseEntity>()
        val insertedOccurrences = mutableListOf<CourseOccurrenceEntity>()
        val replacedSemesterIds = mutableListOf<String>()
        val deletedCourseIds = mutableListOf<String>()
        var deleteCoursesCalls = 0
        var deleteOccurrencesCalls = 0
        var deleteOccurrencesForCoursesCalls = 0
        private val semesterFlow = MutableStateFlow(initialSemesters)
        val semesterIds: List<String> get() = semesterFlow.value.map { it.id }
        private val courseFlow = MutableStateFlow<List<CourseEntity>>(emptyList())
        private val occurrenceFlow = MutableStateFlow<List<CourseOccurrenceEntity>>(emptyList())
        private val periodFlow = MutableStateFlow<List<ClassPeriodEntity>>(emptyList())
        private val adjustmentFlow = MutableStateFlow<List<com.glut.schedule.data.local.SemesterAdjustmentEntity>>(emptyList())

        override fun observeSemesters(): Flow<List<AcademicSemesterEntity>> = semesterFlow

        override suspend fun insertSemester(semester: AcademicSemesterEntity) {
            semesterFlow.value = semesterFlow.value.filterNot { it.id == semester.id } + semester
        }

        override suspend fun clearOtherCurrentSemesters(semesterId: String) {
            semesterFlow.value = semesterFlow.value.map { if (it.id == semesterId) it else it.copy(isCurrent = false) }
        }

        override suspend fun deleteSemester(semesterId: String) {
            semesterFlow.value = semesterFlow.value.filterNot { it.id == semesterId }
            courseFlow.value = courseFlow.value.filterNot { it.semesterId == semesterId }
            occurrenceFlow.value = occurrenceFlow.value.filterNot { it.semesterId == semesterId }
        }

        override suspend fun deleteAllSemesters() { semesterFlow.value = emptyList() }

        override fun observeCourses(): Flow<List<CourseEntity>> = courseFlow

        override fun observeOccurrences(): Flow<List<CourseOccurrenceEntity>> = occurrenceFlow

        override fun observeClassPeriods(): Flow<List<ClassPeriodEntity>> = periodFlow

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
            val keys = courses.map { it.semesterId to it.id }.toSet()
            courseFlow.value = courseFlow.value.filterNot { (it.semesterId to it.id) in keys } + courses
        }

        override suspend fun insertOccurrences(occurrences: List<CourseOccurrenceEntity>) {
            insertedOccurrences.addAll(occurrences)
            val keys = occurrences.map { it.semesterId to it.id }.toSet()
            occurrenceFlow.value = occurrenceFlow.value.filterNot { (it.semesterId to it.id) in keys } + occurrences
        }

        override suspend fun insertClassPeriods(periods: List<ClassPeriodEntity>) {
            insertedPeriods.addAll(periods)
            periodFlow.value = periods
        }

        override suspend fun deleteClassPeriods() {
            insertedPeriods.clear()
            periodFlow.value = emptyList()
        }

        override suspend fun deleteClassPeriodsForSemester(semesterId: String) {
            periodFlow.value = periodFlow.value.filterNot { it.semesterId == semesterId }
        }

        override suspend fun replaceClassPeriods(periods: List<ClassPeriodEntity>) {
            insertedPeriods.clear()
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

        override suspend fun deleteCoursesForSemester(semesterId: String) {
            courseFlow.value = courseFlow.value.filterNot { it.semesterId == semesterId }
        }

        override suspend fun deleteOccurrencesForSemester(semesterId: String) {
            occurrenceFlow.value = occurrenceFlow.value.filterNot { it.semesterId == semesterId }
        }

        override suspend fun replaceSemesterSchedule(
            semester: AcademicSemesterEntity,
            courses: List<CourseEntity>,
            occurrences: List<CourseOccurrenceEntity>,
            periods: List<ClassPeriodEntity>,
            adjustments: List<com.glut.schedule.data.local.SemesterAdjustmentEntity>
        ) {
            replacedSemesterIds += semester.id
            super<ScheduleDao>.replaceSemesterSchedule(semester, courses, occurrences, periods, adjustments)
        }

        override fun observeExams(): Flow<List<ExamEntity>> = flowOf(emptyList())

        override suspend fun insertExams(exams: List<ExamEntity>) {}

        override suspend fun deleteAllExams() {}

        override fun observeScores(): Flow<List<ScoreEntity>> = flowOf(emptyList())

        override suspend fun insertScores(scores: List<ScoreEntity>) {}

        override suspend fun deleteAllScores() {}

        override fun observeGradeExams(): Flow<List<GradeExamEntity>> = flowOf(emptyList())

        override suspend fun insertGradeExams(exams: List<GradeExamEntity>) {}

        override suspend fun deleteAllGradeExams() {}

        override fun observeStudyPlanGroups(): Flow<List<com.glut.schedule.data.local.StudyPlanGroupEntity>> = flowOf(emptyList())

        override suspend fun insertStudyPlanGroups(groups: List<com.glut.schedule.data.local.StudyPlanGroupEntity>) {}

        override suspend fun deleteAllStudyPlanGroups() {}

        override fun observeStudyPlanCourses(): Flow<List<com.glut.schedule.data.local.StudyPlanCourseEntity>> = flowOf(emptyList())

        override suspend fun insertStudyPlanCourses(courses: List<com.glut.schedule.data.local.StudyPlanCourseEntity>) {}

        override suspend fun deleteAllStudyPlanCourses() {}

        override fun observeSemesterAdjustments(): Flow<List<com.glut.schedule.data.local.SemesterAdjustmentEntity>> = adjustmentFlow

        override suspend fun insertSemesterAdjustments(adjustments: List<com.glut.schedule.data.local.SemesterAdjustmentEntity>) {
            adjustmentFlow.value = adjustmentFlow.value + adjustments
        }

        override suspend fun deleteAllSemesterAdjustments() { adjustmentFlow.value = emptyList() }

        override suspend fun deleteSemesterAdjustmentsForSemester(semesterId: String) {
            adjustmentFlow.value = adjustmentFlow.value.filterNot { it.semesterId == semesterId }
        }
    }
}
