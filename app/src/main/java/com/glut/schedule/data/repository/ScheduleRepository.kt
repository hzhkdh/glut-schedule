package com.glut.schedule.data.repository

import com.glut.schedule.data.local.ScheduleDao
import com.glut.schedule.data.local.toEntity
import com.glut.schedule.data.local.toModel
import com.glut.schedule.data.model.ClassPeriod
import com.glut.schedule.data.model.AcademicSemester
import com.glut.schedule.data.model.SemesterCacheStatus
import com.glut.schedule.data.model.CourseColorMapper
import com.glut.schedule.data.model.CourseTimeSemesterSource
import com.glut.schedule.data.model.ExamInfo
import com.glut.schedule.data.model.GradeExamInfo
import com.glut.schedule.data.model.ScoreInfo
import com.glut.schedule.data.model.StudyPlanGroup
import com.glut.schedule.data.model.StudyPlanCourse
import com.glut.schedule.data.model.StudyPlanGroupWithCourses
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.SemesterAdjustment
import com.glut.schedule.data.model.defaultClassPeriods
import com.glut.schedule.data.model.guilinClassPeriods
import com.glut.schedule.data.model.nanningClassPeriods
import com.glut.schedule.data.model.pingfengClassPeriods
import com.glut.schedule.data.model.yanshanClassPeriods
import com.glut.schedule.data.model.validateClassPeriods
import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.data.settings.ClassPeriodProfile
import com.glut.schedule.data.settings.GUILIN_SUB_CAMPUS_DEFAULT
import com.glut.schedule.data.settings.GUILIN_SUB_CAMPUS_PINGFENG
import com.glut.schedule.data.settings.classPeriodProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

private data class ClassPeriodConfig(
    val legacyOverrides: Map<CampusType, List<ClassPeriod>>,
    val profileOverrides: Map<ClassPeriodProfile, List<ClassPeriod>>,
    val guilinSubCampus: String
)

class ScheduleRepository(
    private val dao: ScheduleDao,
    private val campusType: Flow<CampusType>,
    private val courseColorOverrides: Flow<Map<String, String>> = flowOf(emptyMap()),
    private val classPeriodOverrides: Flow<Map<CampusType, List<ClassPeriod>>> = flowOf(emptyMap()),
    private val classPeriodProfileOverrides: Flow<Map<ClassPeriodProfile, List<ClassPeriod>>> = flowOf(emptyMap()),
    private val guilinSubCampus: Flow<String> = flowOf(GUILIN_SUB_CAMPUS_DEFAULT)
) {
    private val _viewedSemesterId = MutableStateFlow(AcademicSemester.LEGACY_CURRENT_ID)
    val viewedSemesterId: StateFlow<String> = _viewedSemesterId

    val semesters: Flow<List<AcademicSemester>> = dao.observeSemesters().map { entities ->
        entities.map { it.toModel() }
    }

    val currentSemester: Flow<AcademicSemester?> = semesters.map { list ->
        list.firstOrNull { it.isCurrent } ?: list.firstOrNull { it.id == AcademicSemester.LEGACY_CURRENT_ID }
    }

    val viewedSemester: Flow<AcademicSemester?> = combine(semesters, viewedSemesterId) { list, selectedId ->
        list.firstOrNull { it.id == selectedId }
            ?: list.firstOrNull { it.isCurrent }
            ?: list.firstOrNull()
    }

    val courses: Flow<List<ScheduleCourse>> = combine(
        dao.observeCourses(),
        dao.observeOccurrences(),
        viewedSemesterId
    ) { courses, occurrences, semesterId ->
        mapCourses(courses, occurrences, semesterId)
    }

    val currentCourses: Flow<List<ScheduleCourse>> = combine(
        dao.observeCourses(),
        dao.observeOccurrences(),
        currentSemester
    ) { courses, occurrences, semester ->
        mapCourses(courses, occurrences, semester?.id ?: AcademicSemester.LEGACY_CURRENT_ID)
    }

    private val classPeriodConfig = combine(
        classPeriodOverrides, classPeriodProfileOverrides, guilinSubCampus
    ) { legacyOverrides, profileOverrides, subCampus ->
        ClassPeriodConfig(legacyOverrides, profileOverrides, subCampus)
    }

    val classPeriods: Flow<List<ClassPeriod>> = combine(
        viewedSemester, campusType, classPeriodConfig
    ) { semester, selectedCampus, config ->
        resolveClassPeriods(
            semester, selectedCampus,
            config.legacyOverrides, config.profileOverrides, config.guilinSubCampus
        )
    }

    val currentClassPeriods: Flow<List<ClassPeriod>> = combine(
        currentSemester, campusType, classPeriodConfig
    ) { semester, selectedCampus, config ->
        resolveClassPeriods(
            semester, selectedCampus,
            config.legacyOverrides, config.profileOverrides, config.guilinSubCampus
        )
    }

    /**
     * 课时统计需要同时读取多个学期，不能复用只面向“正在查看学期”的 courses 流。
     * 当前学期采用用户此刻生效的作息；历史学期则固定使用下载时随学期保存的作息。
     */
    val courseTimeSemesterSources: Flow<List<CourseTimeSemesterSource>> = combine(
        semesters,
        dao.observeCourses(),
        dao.observeOccurrences(),
        dao.observeClassPeriods(),
        currentClassPeriods
    ) { semesterList, courseEntities, occurrenceEntities, periodEntities, liveCurrentPeriods ->
        semesterList
            .sortedWith(
                compareByDescending<AcademicSemester> { it.isCurrent }
                    .thenByDescending { it.portalYear }
                    .thenByDescending { it.season.ordinal }
            )
            .map { semester ->
                CourseTimeSemesterSource(
                    semesterId = semester.id,
                    semesterLabel = semester.displayName,
                    isCurrent = semester.isCurrent,
                    isDownloaded = semester.cacheStatus == SemesterCacheStatus.CACHED,
                    portalMaxWeek = semester.portalMaxWeek,
                    courses = mapCourses(courseEntities, occurrenceEntities, semester.id),
                    classPeriods = if (semester.isCurrent) {
                        liveCurrentPeriods
                    } else {
                        periodEntities
                            .filter { it.semesterId == semester.id }
                            .map { it.toModel() }
                    }
                )
            }
    }

    suspend fun seedIfEmpty() {
        if (semesters.first().isEmpty()) {
            dao.insertSemester(legacySemesterEntity())
            dao.replaceClassPeriods(defaultClassPeriods().map { it.toEntity() })
        }

        // 修复步骤失败不应阻止种子数据初始化，各自捕获异常仅记录日志。
        runCatching { repairInterruptedDownloads() }
            .onFailure { android.util.Log.w("ScheduleRepo", "repairInterruptedDownloads failed", it) }
        runCatching { repairEmptyHistoricalCaches() }
            .onFailure { android.util.Log.w("ScheduleRepo", "repairEmptyHistoricalCaches failed", it) }

        if (dao.courseCount() > 0) {
            clearLegacyBundledSampleCoursesIfPresent()
        }
    }

    private suspend fun repairInterruptedDownloads() {
        semesters.first()
            .filter { it.cacheStatus == SemesterCacheStatus.DOWNLOADING }
            .forEach { semester ->
                dao.insertSemester(
                    semester.copy(
                        cacheStatus = SemesterCacheStatus.NOT_CACHED,
                        importedAtEpochMillis = null
                    ).toEntity()
                )
            }
    }

    private suspend fun repairEmptyHistoricalCaches() {
        val semesterIdsWithCourses = dao.observeCourses().first().mapTo(mutableSetOf()) { it.semesterId }
        val semesterIdsWithOccurrences = dao.observeOccurrences().first()
            .mapTo(mutableSetOf()) { it.semesterId }
        semesters.first()
            .filter { semester ->
                !semester.isCurrent &&
                    semester.cacheStatus == SemesterCacheStatus.CACHED &&
                    (semester.id !in semesterIdsWithCourses ||
                        semester.id !in semesterIdsWithOccurrences)
            }
            .forEach { semester ->
                dao.insertSemester(
                    semester.copy(
                        cacheStatus = SemesterCacheStatus.NOT_CACHED,
                        importedAtEpochMillis = null
                    ).toEntity()
                )
            }
    }

    private suspend fun clearLegacyBundledSampleCoursesIfPresent() {
        val ids = dao.courseIds().toSet()
        if (ids == legacyBundledSampleCourseIds) {
            val legacyIds = legacyBundledSampleCourseIds.toList()
            dao.deleteOccurrencesForCourses(legacyIds)
            dao.deleteCoursesByIds(legacyIds)
        }
    }

    val exams: Flow<List<ExamInfo>> = dao.observeExams().map { entities ->
        entities.map { it.toModel() }
    }

    suspend fun replaceExams(exams: List<ExamInfo>) {
        dao.replaceExams(exams.map { it.toEntity() })
    }

    val scores: Flow<List<ScoreInfo>> = dao.observeScores().map { entities ->
        entities.map { it.toModel() }
    }

    suspend fun replaceScores(scores: List<ScoreInfo>) {
        dao.replaceScores(scores.map { it.toEntity() })
    }

    val gradeExams: Flow<List<GradeExamInfo>> = dao.observeGradeExams().map { entities ->
        entities.map { it.toModel() }
    }

    suspend fun replaceGradeExams(exams: List<GradeExamInfo>) {
        dao.replaceGradeExams(exams.map { it.toEntity() })
    }

    val studyPlanGroups: Flow<List<StudyPlanGroup>> = dao.observeStudyPlanGroups().map { entities ->
        entities.map { it.toModel() }
    }

    val studyPlanCourses: Flow<List<StudyPlanCourse>> = dao.observeStudyPlanCourses().map { entities ->
        entities.map { it.toModel() }
    }

    val studyPlanGroupsWithCourses: Flow<List<StudyPlanGroupWithCourses>> =
        combine(studyPlanGroups, studyPlanCourses) { groups, courses ->
            val coursesByGroup = courses.groupBy { it.groupId }
            groups.map { group ->
                StudyPlanGroupWithCourses(
                    group = group,
                    courses = coursesByGroup[group.id] ?: emptyList()
                )
            }
        }

    suspend fun replaceStudyPlanGroups(groups: List<StudyPlanGroup>) {
        dao.replaceStudyPlanGroups(groups.map { it.toEntity() })
    }

    suspend fun replaceStudyPlanData(
        groups: List<StudyPlanGroup>,
        courses: List<StudyPlanCourse>
    ) {
        dao.replaceStudyPlanData(
            groups = groups.map { it.toEntity() },
            courses = courses.map { it.toEntity() }
        )
    }

    val semesterAdjustments: Flow<List<SemesterAdjustment>> = combine(
        dao.observeSemesterAdjustments(), viewedSemesterId
    ) { entities, semesterId ->
        entities.filter { it.semesterId == semesterId }.map { it.toModel() }
    }

    suspend fun replaceSemesterAdjustments(
        semesterId: String,
        adjustments: List<SemesterAdjustment>
    ) {
        dao.replaceSemesterAdjustments(
            semesterId = semesterId,
            adjustments = adjustments.map { it.toEntity(semesterId) }
        )
    }

    suspend fun replaceImportedCourses(courses: List<ScheduleCourse>) {
        // Write campus-specific periods so the schedule grid shows correct time slots.
        val campus = campusType.first()
        val periods = when (campus) {
            CampusType.GUILIN -> guilinClassPeriods()
            CampusType.NANNING -> nanningClassPeriods()
        }
        val semester = currentSemester.first() ?: legacySemesterEntity().toModel()
        val coloredCourses = CourseColorMapper.assignColors(courses, courseColorOverrides.first())
        dao.replaceSemesterSchedule(
            semester = semester.copy(
                cacheStatus = SemesterCacheStatus.CACHED,
                importedAtEpochMillis = System.currentTimeMillis()
            ).toEntity(),
            courses = coloredCourses.map { it.toEntity(semester.id) },
            occurrences = coloredCourses.flatMap { course -> course.occurrences.map { it.toEntity(semester.id) } },
            periods = periods.map { it.toEntity(semester.id) },
            adjustments = dao.observeSemesterAdjustments().first()
                .filter { it.semesterId == semester.id }
        )
    }

    suspend fun replaceSemesterSchedule(
        semester: AcademicSemester,
        courses: List<ScheduleCourse>,
        adjustments: List<SemesterAdjustment>,
        classPeriods: List<ClassPeriod>,
        semesterStartDate: java.time.LocalDate? = semester.semesterStartDate,
        semesterEndDate: java.time.LocalDate? = semester.semesterEndDate,
        portalMaxWeek: Int? = semester.portalMaxWeek
    ) {
        val coloredCourses = CourseColorMapper.assignColors(courses, courseColorOverrides.first())
        val cachedSemester = semester.copy(
            cacheStatus = SemesterCacheStatus.CACHED,
            importedAtEpochMillis = System.currentTimeMillis(),
            semesterStartDate = semesterStartDate,
            semesterEndDate = semesterEndDate,
            portalMaxWeek = portalMaxWeek
        )
        dao.replaceSemesterSchedule(
            semester = cachedSemester.toEntity(),
            courses = coloredCourses.map { it.toEntity(semester.id) },
            occurrences = coloredCourses.flatMap { course -> course.occurrences.map { it.toEntity(semester.id) } },
            // 历史统计依赖此处冻结的作息，必须保存调用时实际生效的子校区与用户覆盖。
            periods = classPeriods.map { it.toEntity(semester.id) },
            adjustments = adjustments.map { it.toEntity(semester.id) }
        )
        if (semester.isCurrent && semester.id != AcademicSemester.LEGACY_CURRENT_ID) {
            dao.deleteSemester(AcademicSemester.LEGACY_CURRENT_ID)
        }
    }

    suspend fun saveSemesterCatalog(catalog: List<AcademicSemester>) {
        catalog.forEach { incoming ->
            val existing = semesters.first().firstOrNull { it.id == incoming.id }
            dao.insertSemester(incoming.copy(
                cacheStatus = existing?.cacheStatus ?: incoming.cacheStatus,
                importedAtEpochMillis = existing?.importedAtEpochMillis,
                semesterStartDate = existing?.semesterStartDate,
                semesterEndDate = existing?.semesterEndDate,
                portalMaxWeek = existing?.portalMaxWeek
            ).toEntity())
        }
        catalog.singleOrNull { it.isCurrent }?.let { current ->
            dao.clearOtherCurrentSemesters(current.id)
        }
    }

    suspend fun updateSemesterCacheStatus(semesterId: String, status: SemesterCacheStatus) {
        val semester = semesters.first().firstOrNull { it.id == semesterId } ?: return
        dao.insertSemester(semester.copy(cacheStatus = status).toEntity())
    }

    fun selectSemester(semesterId: String) {
        _viewedSemesterId.value = semesterId
    }

    suspend fun resetViewedSemesterToCurrent() {
        _viewedSemesterId.value = currentSemester.first()?.id ?: AcademicSemester.LEGACY_CURRENT_ID
    }

    suspend fun clearAllData() {
        dao.clearAll()
    }

    private companion object {
        val legacyBundledSampleCourseIds = setOf(
            "digital-logic",
            "embedded",
            "english",
            "machine-learning",
            "politics",
            "os",
            "java",
            "algorithm"
        )

        fun mapCourses(
            courses: List<com.glut.schedule.data.local.CourseEntity>,
            occurrences: List<com.glut.schedule.data.local.CourseOccurrenceEntity>,
            semesterId: String
        ): List<ScheduleCourse> {
            val matchingOccurrences = occurrences.filter { it.semesterId == semesterId }
            val occurrencesByCourse = matchingOccurrences.map { it.toModel() }.groupBy { it.courseId }
            return courses.filter { it.semesterId == semesterId }
                .map { course -> course.toModel(occurrencesByCourse[course.id].orEmpty()) }
        }

        fun resolveClassPeriods(
            semester: AcademicSemester?,
            selectedCampus: CampusType,
            legacyOverrides: Map<CampusType, List<ClassPeriod>>,
            profileOverrides: Map<ClassPeriodProfile, List<ClassPeriod>>,
            guilinSubCampus: String = GUILIN_SUB_CAMPUS_DEFAULT
        ): List<ClassPeriod> {
            val campus = if (semester?.id == AcademicSemester.LEGACY_CURRENT_ID) {
                selectedCampus
            } else {
                semester?.campus ?: selectedCampus
            }
            val profile = classPeriodProfile(campus, guilinSubCampus)
            // 展示时间只由当前档案控制，避免学期表中的旧雁山时间压过屏风配置。
            return profileOverrides[profile]
                ?: legacyOverrides[campus]
                ?: defaultClassPeriods(profile)
        }

        fun legacySemesterEntity() = com.glut.schedule.data.local.AcademicSemesterEntity(
            id = AcademicSemester.LEGACY_CURRENT_ID,
            campus = CampusType.GUILIN.name,
            portalYear = 0,
            portalYearId = "",
            season = com.glut.schedule.data.model.SemesterSeason.SPRING.name,
            portalTermId = "",
            displayName = "当前学期（待确认）",
            isCurrent = true,
            cacheStatus = SemesterCacheStatus.CACHED.name,
            importedAtEpochMillis = null,
            semesterStartDate = null,
            semesterEndDate = null,
            portalMaxWeek = null
        )
    }
}
