package com.glut.schedule

import android.app.Application
import androidx.room.Room
import com.glut.schedule.data.local.ScheduleDatabase
import com.glut.schedule.data.repository.ScheduleRepository
import com.glut.schedule.data.settings.ScheduleSettingsStore
import com.glut.schedule.service.academic.AcademicSessionStore
import com.glut.schedule.service.academic.ApiProbeService
import com.glut.schedule.service.academic.AcademicExamService
import com.glut.schedule.service.academic.AcademicLoginService
import com.glut.schedule.service.academic.AcademicSemesterImportService
import com.glut.schedule.service.academic.SemesterBulkDownloadCoordinator
import com.glut.schedule.service.academic.SemesterDownloadSession
import com.glut.schedule.service.academic.CredentialStore
import com.glut.schedule.service.fitness.FitnessApiService
import com.glut.schedule.service.fitness.FitnessStore
import com.glut.schedule.service.finance.FinanceApiService
import com.glut.schedule.service.finance.FinanceParser
import com.glut.schedule.service.finance.FinanceStore
import com.glut.schedule.service.campus.CampusImageFileCache
import com.glut.schedule.service.campus.CampusImageService
import com.glut.schedule.service.background.RemoteBackgroundAssetStore
import com.glut.schedule.service.background.RemoteBackgroundRepository
import com.glut.schedule.service.background.AndroidRemoteArtworkSaver
import com.glut.schedule.service.parser.AcademicScheduleParser
import com.glut.schedule.service.parser.GlutAcademicScheduleParser
import com.glut.schedule.service.parser.GlutExamParser
import com.glut.schedule.service.parser.ScoreParser
import com.glut.schedule.service.parser.GradeExamParser
import com.glut.schedule.service.parser.FitnessParser
import com.glut.schedule.service.parser.StudyPlanParser
import com.glut.schedule.service.AppUpdater
import com.glut.schedule.service.NoticeChecker
import com.glut.schedule.service.UpdateChecker
import com.glut.schedule.service.greeting.GreetingTemplateRepository
import com.glut.schedule.service.greeting.HttpGreetingTemplateRemote
import com.glut.schedule.service.holiday.TimorHolidayClient
import com.glut.schedule.partner.PartnerScheduleApiService
import com.glut.schedule.partner.PartnerScheduleStore
import com.glut.schedule.ui.components.ScheduleBackgroundStore
import com.glut.schedule.widget.ScheduleWidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 大节课表来源迁移只执行一次；清理失败时不写完成标记，下次启动继续重试。
 */
internal suspend fun migrateScheduleSourceIfNeeded(
    alreadyMigrated: Boolean,
    invalidateCaches: suspend () -> Unit,
    markMigrated: suspend () -> Unit
): Boolean {
    if (alreadyMigrated) return false
    invalidateCaches()
    markMigrated()
    return true
}

class ScheduleApplication : Application() {
    lateinit var appContainer: AppContainer
        private set
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this, applicationScope)
        applicationScope.launch {
            val migrationFlags = getSharedPreferences("schedule_migration_flags", MODE_PRIVATE)
            try {
                migrateScheduleSourceIfNeeded(
                    alreadyMigrated = migrationFlags.getBoolean(
                        "show_timetable_source_v1",
                        false
                    ),
                    invalidateCaches = appContainer.scheduleRepository::invalidateLegacyImportCaches,
                    markMigrated = {
                        check(
                            migrationFlags.edit()
                                .putBoolean("show_timetable_source_v1", true)
                                .commit()
                        ) { "无法保存课表来源迁移标记" }
                    }
                )
            } catch (e: Exception) {
                android.util.Log.e("ScheduleApp", "Failed to migrate schedule source", e)
            }
            try {
                appContainer.scheduleRepository.resetViewedSemesterToCurrent()
            } catch (e: Exception) {
                android.util.Log.e("ScheduleApp", "Failed to reset semester on start", e)
            }
        }
        applicationScope.launch {
            appContainer.greetingTemplateRepository.initializeAndRefresh()
        }
        observeWidgetDataChanges()
    }

    @OptIn(FlowPreview::class)
    private fun observeWidgetDataChanges() {
        applicationScope.launch {
            // 调休规则也要参与触发：否则用户在 App 内新增/删除规则后，
            // 小组件要等到下一次定时刷新才会跟着变。
            val currentSemesterManualDayCopies = combine(
                appContainer.scheduleRepository.currentSemester,
                appContainer.settingsStore.manualDayCopies
            ) { semester, rulesBySemester ->
                rulesBySemester[semester?.id.orEmpty()].orEmpty()
            }
            combine(
                appContainer.scheduleRepository.currentCourses,
                appContainer.scheduleRepository.currentClassPeriods,
                appContainer.settingsStore.semesterStartMonday,
                appContainer.settingsStore.semesterEndDate,
                currentSemesterManualDayCopies
            ) { courses, periods, semesterStart, semesterEnd, manualDayCopies ->
                listOf(courses, periods, semesterStart, semesterEnd, manualDayCopies)
            }.debounce(500)
                .collect { ScheduleWidgetUpdater.updateAll(this@ScheduleApplication) }
        }
    }
}

class AppContainer(application: Application, applicationScope: CoroutineScope) {
    private val database = Room.databaseBuilder(
        application,
        ScheduleDatabase::class.java,
        "glut_schedule.db"
    ).addMigrations(
        ScheduleDatabase.MIGRATION_7_8,
        ScheduleDatabase.MIGRATION_8_9,
        ScheduleDatabase.MIGRATION_9_10,
        ScheduleDatabase.MIGRATION_10_11,
        ScheduleDatabase.MIGRATION_11_12,
        ScheduleDatabase.MIGRATION_12_13,
        ScheduleDatabase.MIGRATION_13_14
    )
     .build()

    val settingsStore = ScheduleSettingsStore(application)
    val greetingTemplateRepository = GreetingTemplateRepository(
        cache = settingsStore,
        remote = HttpGreetingTemplateRemote()
    )
    val scheduleRepository = ScheduleRepository(
        database.scheduleDao(),
        settingsStore.campusType,
        settingsStore.courseColorOverrides,
        settingsStore.classPeriodOverrides,
        classPeriodProfileOverrides = settingsStore.classPeriodProfileOverrides,
        guilinSubCampus = settingsStore.guilinSubCampus
    )
    val backgroundStore = ScheduleBackgroundStore(application)
    val remoteBackgroundRepository = RemoteBackgroundRepository(
        catalogCacheFile = application.filesDir.resolve("remote_backgrounds/catalog.json"),
        previewCacheDirectory = application.cacheDir.resolve("remote_background_previews"),
        assetStore = RemoteBackgroundAssetStore(application.filesDir.resolve("remote_backgrounds/assets")),
        downloadCacheDirectory = application.cacheDir.resolve("remote_background_downloads")
    )
    val remoteArtworkSaver = AndroidRemoteArtworkSaver(application)
    val academicSessionStore = AcademicSessionStore(application)
    // 统一导入路径后只解析大节课表，个人课表页退化为「取学号」的一跳，
    // 因此不再需要按校区路由的 CompositeScheduleParser / NanningCurrcourseParser。
    val academicScheduleParser: AcademicScheduleParser = GlutAcademicScheduleParser()
    val apiProbeService = ApiProbeService()
    val examParser = GlutExamParser()
    val academicExamService = AcademicExamService(examParser)
    val credentialStore = CredentialStore(application)
    val academicSemesterImportService = AcademicSemesterImportService(apiProbeService, academicScheduleParser)
    val semesterBulkDownloadCoordinator = SemesterBulkDownloadCoordinator(
        scope = applicationScope,
        semestersProvider = { scheduleRepository.semesters.first() },
        sessionProvider = {
            val owner = academicSessionStore.authenticatedStudentNumber.first()
                .ifBlank { credentialStore.getUsername() }
            SemesterDownloadSession(
                ownerStudentNumber = owner,
                cookie = academicSessionStore.academicCookie.first(),
                baseUrl = academicSessionStore.campusBaseUrl.first()
            )
        },
        currentOwnerProvider = {
            academicSessionStore.authenticatedStudentNumber.first()
                .ifBlank { credentialStore.getUsername() }
        },
        download = { semester, session ->
            val baseUrl = session.baseUrl.ifBlank {
                if (semester.campus == com.glut.schedule.data.settings.CampusType.NANNING) {
                    com.glut.schedule.service.academic.AcademicLoginResult.NANNING_URL
                } else {
                    com.glut.schedule.service.academic.AcademicLoginResult.DEFAULT_GUILIN_URL
                }
            }
            academicSemesterImportService.importSemester(
                cookie = session.cookie,
                baseUrl = baseUrl,
                semester = semester,
                studentIdFallback = session.ownerStudentNumber
            )
        },
        commit = { semester, payload ->
            if (payload.updatedCookie.isNotBlank()) {
                academicSessionStore.saveCookie(payload.updatedCookie)
            }
            // Room 的旧 importMode 列仅为数据库兼容保留，领域层不再参与任何分支。
            scheduleRepository.replaceSemesterSchedule(
                semester = semester,
                courses = payload.courses,
                adjustments = payload.adjustments,
                classPeriods = scheduleRepository.currentClassPeriods.first(),
                portalMaxWeek = payload.portalMaxWeek
            )
        },
        updateCacheStatus = scheduleRepository::updateSemesterCacheStatus
    )
    val fitnessStore = FitnessStore(application)
    val fitnessApiService = FitnessApiService()
    val fitnessParser = FitnessParser()
    val financeStore by lazy { FinanceStore(application) }
    private val financeParser by lazy { FinanceParser() }
    val financeApiService by lazy { FinanceApiService(financeParser) }
    private val campusImageCache = CampusImageFileCache(application.filesDir.resolve("campus_images"))
    val campusImageService = CampusImageService(cache = campusImageCache)
    val academicLoginService = AcademicLoginService(academicSessionStore, credentialStore)
    val scoreParser = ScoreParser()
    val gradeExamParser = GradeExamParser()
    val studyPlanParser = StudyPlanParser()
    val updateChecker = UpdateChecker()
    val noticeChecker = NoticeChecker()
    val appUpdater = AppUpdater(application)
    val partnerScheduleStore = PartnerScheduleStore(application)
    val partnerScheduleApiService = PartnerScheduleApiService()
    // 首页课表角标与学期概览共用同一个客户端与同一份按年缓存，避免两处各自拉取、互相覆盖。
    val timorHolidayClient = TimorHolidayClient()
}
