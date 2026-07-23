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
import com.glut.schedule.data.model.ClassPeriod
import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.SemesterSeason
import com.glut.schedule.data.model.guilinClassPeriods
import com.glut.schedule.data.model.nanningClassPeriods
import com.glut.schedule.data.repository.ScheduleRepository
import com.glut.schedule.data.settings.CampusType
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleRepositoryTest {
    @Test
    fun classPeriodsUseViewedSemesterCampusOverrideWhileCurrentPeriodsUseCurrentCampusOverride() = runTest {
        val historical = AcademicSemester.create(
            CampusType.GUILIN, 2024, "44", SemesterSeason.AUTUMN, "2", isCurrent = false
        )
        val current = AcademicSemester.create(
            CampusType.NANNING, 2026, "46", SemesterSeason.AUTUMN, "2", isCurrent = true
        )
        val guilinOverride = guilinClassPeriods().map {
            if (it.section == 1) it.copy(startsAt = "07:45", endsAt = "08:30") else it
        }
        val nanningOverride = nanningClassPeriods().map {
            if (it.section == 1) it.copy(startsAt = "07:55", endsAt = "08:40") else it
        }
        val dao = FakeScheduleDao(
            initialSemesters = listOf(historical.toEntity(), current.toEntity())
        )
        val repository = ScheduleRepository(
            dao = dao,
            campusType = flowOf(CampusType.NANNING),
            classPeriodOverrides = flowOf(
                mapOf(
                    CampusType.GUILIN to guilinOverride,
                    CampusType.NANNING to nanningOverride
                )
            )
        )

        repository.selectSemester(historical.id)

        assertEquals(guilinOverride, repository.classPeriods.first())
        assertEquals(nanningOverride, repository.currentClassPeriods.first())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun classPeriodOverrideFlowUpdatesLiveAndMissingOverrideFallsBackToStoredPeriods() = runTest {
        val historical = AcademicSemester.create(
            CampusType.GUILIN, 2024, "44", SemesterSeason.AUTUMN, "2", isCurrent = false
        )
        val stored = guilinClassPeriods().map {
            if (it.section == 1) it.copy(startsAt = "08:05", endsAt = "08:50") else it
        }
        val override = guilinClassPeriods().map {
            if (it.section == 1) it.copy(startsAt = "07:40", endsAt = "08:25") else it
        }
        val overrides = MutableStateFlow<Map<CampusType, List<ClassPeriod>>>(emptyMap())
        val dao = FakeScheduleDao(initialSemesters = listOf(historical.toEntity()))
        dao.insertClassPeriods(stored.map { it.toEntity(historical.id) })
        val repository = ScheduleRepository(
            dao = dao,
            campusType = flowOf(CampusType.NANNING),
            classPeriodOverrides = overrides
        )
        repository.selectSemester(historical.id)

        val emissions = async { repository.classPeriods.take(2).toList() }
        runCurrent()

        overrides.value = mapOf(CampusType.GUILIN to override)

        assertEquals(listOf(stored, override), emissions.await())
    }

    @Test
    fun missingOverrideAndStoredPeriodsFallBackToEachSemestersCampusDefaults() = runTest {
        val historical = AcademicSemester.create(
            CampusType.GUILIN, 2024, "44", SemesterSeason.AUTUMN, "2", isCurrent = false
        )
        val current = AcademicSemester.create(
            CampusType.NANNING, 2026, "46", SemesterSeason.AUTUMN, "2", isCurrent = true
        )
        val dao = FakeScheduleDao(
            initialSemesters = listOf(historical.toEntity(), current.toEntity())
        )
        val repository = ScheduleRepository(
            dao = dao,
            campusType = flowOf(CampusType.GUILIN),
            classPeriodOverrides = flowOf(emptyMap())
        )
        repository.selectSemester(historical.id)

        assertEquals(guilinClassPeriods(), repository.classPeriods.first())
        assertEquals(nanningClassPeriods(), repository.currentClassPeriods.first())
    }

    @Test
    fun legacyCurrentUsesSelectedNanningOverrideInsteadOfLegacyGuilinCampus() = runTest {
        val override = nanningClassPeriods().map {
            if (it.section == 1) it.copy(startsAt = "07:55", endsAt = "08:35") else it
        }
        val dao = FakeScheduleDao(initialSemesters = listOf(legacyCurrentSemester()))
        dao.insertClassPeriods(
            guilinClassPeriods().map { it.toEntity(AcademicSemester.LEGACY_CURRENT_ID) }
        )
        val repository = ScheduleRepository(
            dao = dao,
            campusType = flowOf(CampusType.NANNING),
            classPeriodOverrides = flowOf(mapOf(CampusType.NANNING to override))
        )

        assertEquals(override, repository.currentClassPeriods.first())
        assertEquals(11, repository.classPeriods.first().size)
    }

    @Test
    fun legacyCurrentRejectsStoredGuilinPeriodsWhenSelectedCampusIsNanning() = runTest {
        val dao = FakeScheduleDao(initialSemesters = listOf(legacyCurrentSemester()))
        dao.insertClassPeriods(
            guilinClassPeriods().map { it.toEntity(AcademicSemester.LEGACY_CURRENT_ID) }
        )
        val repository = ScheduleRepository(
            dao = dao,
            campusType = flowOf(CampusType.NANNING)
        )

        assertEquals(nanningClassPeriods(), repository.currentClassPeriods.first())
        assertEquals(nanningClassPeriods(), repository.classPeriods.first())
    }

    @Test
    fun legacyCurrentKeepsValidStoredPeriodsForSelectedGuilinCampus() = runTest {
        val stored = guilinClassPeriods().map {
            if (it.section == 1) it.copy(startsAt = "08:10", endsAt = "08:55") else it
        }
        val dao = FakeScheduleDao(initialSemesters = listOf(legacyCurrentSemester()))
        dao.insertClassPeriods(
            stored.map { it.toEntity(AcademicSemester.LEGACY_CURRENT_ID) }
        )
        val repository = ScheduleRepository(
            dao = dao,
            campusType = flowOf(CampusType.GUILIN)
        )

        assertEquals(stored, repository.currentClassPeriods.first())
        assertEquals(stored, repository.classPeriods.first())
    }

    @Test
    fun replacingSemesterScheduleDoesNotChangeViewedSemester() = runTest {
        val historical = AcademicSemester.create(
            CampusType.GUILIN, 2024, "44", SemesterSeason.AUTUMN, "2", isCurrent = false
        )
        val dao = FakeScheduleDao(initialSemesters = listOf(historical.toEntity()))
        val repository = ScheduleRepository(dao, flowOf(CampusType.GUILIN))
        repository.selectSemester(AcademicSemester.LEGACY_CURRENT_ID)

        repository.replaceSemesterSchedule(historical, listOf(course("history", "历史课程")), emptyList())

        assertEquals(AcademicSemester.LEGACY_CURRENT_ID, repository.viewedSemesterId.value)
    }

    @Test
    fun savingCatalogLeavesExactlyOneIncomingCurrentAndPreservesCacheMetadata() = runTest {
        val importedAt = 1234L
        val oldCurrent = AcademicSemester.create(
            CampusType.GUILIN, 2025, "45", SemesterSeason.SPRING, "1", isCurrent = true
        )
        val promoted = AcademicSemester.create(
            CampusType.GUILIN, 2025, "45", SemesterSeason.AUTUMN, "2", isCurrent = false,
            cacheStatus = com.glut.schedule.data.model.SemesterCacheStatus.CACHED,
            importedAtEpochMillis = importedAt
        )
        val dao = FakeScheduleDao(initialSemesters = listOf(oldCurrent.toEntity(), promoted.toEntity()))
        val repository = ScheduleRepository(dao, flowOf(CampusType.GUILIN))

        repository.saveSemesterCatalog(
            listOf(oldCurrent.copy(isCurrent = false), promoted.copy(isCurrent = true))
        )

        val semesters = repository.semesters.first()
        assertEquals(1, semesters.count { it.isCurrent })
        assertTrue(semesters.single { it.id == promoted.id }.isCurrent)
        assertFalse(semesters.single { it.id == oldCurrent.id }.isCurrent)
        assertEquals(importedAt, semesters.single { it.id == promoted.id }.importedAtEpochMillis)
    }

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
    fun replacingHistoricalSchedulePersistsPortalMaximumWeek() = runTest {
        val semester = AcademicSemester.create(
            CampusType.GUILIN, 2025, "45", SemesterSeason.AUTUMN, "2", isCurrent = false
        )
        val dao = FakeScheduleDao(initialSemesters = listOf(semester.toEntity()))
        val repository = ScheduleRepository(dao, flowOf(CampusType.GUILIN))

        repository.replaceSemesterSchedule(
            semester = semester,
            courses = listOf(course("internship", "实习")),
            adjustments = emptyList(),
            portalMaxWeek = 19
        )

        assertEquals(19, repository.semesters.first().single().portalMaxWeek)
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

    @Test
    fun seedIfEmptyMakesEmptyHistoricalCachesDownloadableAgain() = runTest {
        val current = AcademicSemester.create(
            CampusType.GUILIN, 2026, "46", SemesterSeason.AUTUMN, "2", isCurrent = true,
            cacheStatus = com.glut.schedule.data.model.SemesterCacheStatus.CACHED
        )
        val historical = AcademicSemester.create(
            CampusType.GUILIN, 2026, "46", SemesterSeason.SPRING, "1", isCurrent = false,
            cacheStatus = com.glut.schedule.data.model.SemesterCacheStatus.CACHED
        )
        val dao = FakeScheduleDao(initialSemesters = listOf(current.toEntity(), historical.toEntity()))
        val repository = ScheduleRepository(dao, flowOf(CampusType.GUILIN))

        repository.seedIfEmpty()

        val semesters = repository.semesters.first()
        assertEquals(
            com.glut.schedule.data.model.SemesterCacheStatus.NOT_CACHED,
            semesters.single { it.id == historical.id }.cacheStatus
        )
        assertEquals(
            com.glut.schedule.data.model.SemesterCacheStatus.CACHED,
            semesters.single { it.id == current.id }.cacheStatus
        )
    }

    @Test
    fun seedIfEmptyMakesHistoricalCachesWithoutOccurrencesDownloadableAgain() = runTest {
        val current = AcademicSemester.create(
            CampusType.GUILIN, 2026, "46", SemesterSeason.AUTUMN, "2", isCurrent = true,
            cacheStatus = com.glut.schedule.data.model.SemesterCacheStatus.CACHED
        )
        val historical = AcademicSemester.create(
            CampusType.GUILIN, 2025, "45", SemesterSeason.AUTUMN, "2", isCurrent = false,
            cacheStatus = com.glut.schedule.data.model.SemesterCacheStatus.CACHED
        )
        val dao = FakeScheduleDao(initialSemesters = listOf(current.toEntity(), historical.toEntity()))
        dao.insertCourses(listOf(course("broken-history", "历史课程").toEntity(historical.id)))
        val repository = ScheduleRepository(dao, flowOf(CampusType.GUILIN))

        repository.seedIfEmpty()

        assertEquals(
            com.glut.schedule.data.model.SemesterCacheStatus.NOT_CACHED,
            repository.semesters.first().single { it.id == historical.id }.cacheStatus
        )
    }

    @Test
    fun seedIfEmptyMakesInterruptedDownloadsRetryableAfterRestart() = runTest {
        val current = AcademicSemester.create(
            CampusType.GUILIN, 2026, "46", SemesterSeason.AUTUMN, "2", isCurrent = true,
            cacheStatus = com.glut.schedule.data.model.SemesterCacheStatus.DOWNLOADING
        )
        val historical = AcademicSemester.create(
            CampusType.GUILIN, 2025, "45", SemesterSeason.AUTUMN, "2", isCurrent = false,
            cacheStatus = com.glut.schedule.data.model.SemesterCacheStatus.DOWNLOADING
        )
        val dao = FakeScheduleDao(initialSemesters = listOf(current.toEntity(), historical.toEntity()))
        val repository = ScheduleRepository(dao, flowOf(CampusType.GUILIN))

        repository.seedIfEmpty()

        assertTrue(
            repository.semesters.first().all {
                it.cacheStatus == com.glut.schedule.data.model.SemesterCacheStatus.NOT_CACHED
            }
        )
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

    private fun legacyCurrentSemester() = AcademicSemester(
        id = AcademicSemester.LEGACY_CURRENT_ID,
        campus = CampusType.GUILIN,
        portalYear = 0,
        portalYearId = "",
        season = SemesterSeason.SPRING,
        portalTermId = "",
        displayName = "当前学期（待确认）",
        isCurrent = true
    ).toEntity()

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
