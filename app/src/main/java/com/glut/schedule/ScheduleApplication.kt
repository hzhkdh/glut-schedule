package com.glut.schedule

import android.app.Application
import androidx.room.Room
import com.glut.schedule.data.local.ScheduleDatabase
import com.glut.schedule.data.repository.ScheduleRepository
import com.glut.schedule.data.model.historicalAcademicMaxWeek
import com.glut.schedule.data.settings.ScheduleSettingsStore
import com.glut.schedule.service.academic.AcademicSessionStore
import com.glut.schedule.service.academic.importSourceForCampusBaseUrl
import com.glut.schedule.service.academic.ApiProbeService
import com.glut.schedule.service.academic.AcademicExamService
import com.glut.schedule.service.academic.AcademicLoginService
import com.glut.schedule.service.academic.CaptchaLoginSession
import com.glut.schedule.service.academic.SessionRenewalCoordinator
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
import com.glut.schedule.service.holiday.AutoDayCopyCoordinator
import com.glut.schedule.service.holiday.MakeupTableClient
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
                // v2：停课不再从课程周次里移除（改为保留卡片 + 左下角「停」角标）。旧快照里
                // 停课周的 occurrence 已被物理剥掉，不重导恢复不了，所以升标记键让所有已缓存
                // 学期定向失效、重新导入一次。
                migrateScheduleSourceIfNeeded(
                    alreadyMigrated = migrationFlags.getBoolean(
                        "show_timetable_source_v2",
                        false
                    ),
                    invalidateCaches = appContainer.scheduleRepository::invalidateLegacyImportCaches,
                    markMigrated = {
                        check(
                            migrationFlags.edit()
                                .putBoolean("show_timetable_source_v2", true)
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
            // 调休规则（手动 + 自动）也要参与触发：否则用户在 App 内新增/删除规则、
            // 或打开自动调课开关之后，小组件要等到下一次定时刷新才会跟着变。
            val currentSemesterDayCopies = combine(
                appContainer.scheduleRepository.currentSemester,
                appContainer.settingsStore.manualDayCopies,
                appContainer.settingsStore.autoDayCopies
            ) { semester, manualRules, autoRules ->
                val semesterId = semester?.id.orEmpty()
                manualRules[semesterId].orEmpty() +
                    autoRules[semesterId].orEmpty().map { it.asManualRule() }
            }
            combine(
                appContainer.scheduleRepository.currentCourses,
                appContainer.scheduleRepository.currentClassPeriods,
                appContainer.settingsStore.semesterStartMonday,
                appContainer.settingsStore.semesterEndDate,
                currentSemesterDayCopies
            ) { courses, periods, semesterStart, semesterEnd, dayCopies ->
                listOf(courses, periods, semesterStart, semesterEnd, dayCopies)
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
        guilinSubCampus = settingsStore.guilinSubCampus,
        onCurrentScheduleReplaced = settingsStore::setAutomaticScheduleDisplay
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
            if (semester.campus == com.glut.schedule.data.settings.CampusType.GRADUATE) {
                // 研究生历史学期同样只有门户那一个网关调用；走本专科的导入服务会打到
                // jw.glut.edu.cn，拿回一堆与本次请求无关的页面。这里单独适配，
                // 结果包装成本专科导入服务的返回类型，让批量下载的调度与提交逻辑保持不变。
                com.glut.schedule.service.graduate.GraduateImportService.downloadSemester(
                    cookie = session.cookie,
                    semester = semester,
                    studentId = session.ownerStudentNumber
                ).map { payload ->
                    com.glut.schedule.service.academic.AcademicSemesterImportPayload(
                        courses = payload.courses,
                        // 研究生的调课不并入课表，历史学期同样不例外。
                        adjustments = emptyList(),
                        currcourseHtml = "",
                        timetableHtml = "",
                        responseKind = com.glut.schedule.service.academic.AcademicSemesterResponseKind
                            .VALID_NON_EMPTY_SCHEDULE,
                        // 没有权威校历时不得臆造开学日；周数按「反推」口径补齐但**绝不为 null**：
                        // CourseTimeStats 会把 portalMaxWeek 为 null 的学期整学期判为不可统计。
                        portalMaxWeek = historicalAcademicMaxWeek(
                            semester.portalMaxWeek,
                            payload.courses
                        ),
                        semesterStartMonday = payload.semesterStartMonday,
                        updatedCookie = "",
                        unparsedWeekTextCount = payload.unparsedWeekTextCount,
                        skippedRowCount = payload.skippedRowCount
                    )
                }
            } else {
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
            }
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
            // 与其余导入路径一致：调课记录刚落库，自动调课的让位判定要跟着重算。
            // 批量下载通常是历史学期，那时 recompose 是 no-op。
            autoDayCopyCoordinator.recompose(semester.id)
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
    // 续期门卫：南宁/研究生会话过期时在菜单内原地弹验证码，而不是把用户赶去导入页。
    // 单例——多个菜单同时刷新时只弹一个窗（协调器内部单飞）。
    val captchaLoginSession = CaptchaLoginSession()
    val sessionRenewalCoordinator = SessionRenewalCoordinator(
        sessionWriter = academicSessionStore,
        credentialReader = credentialStore,
        captchaLogin = captchaLoginSession,
        // 来源取「上次成功导入的来源」：南宁与研究生共用同一个弹窗，取错就会给错来源取验证码。
        // 来源由**会话主机**反推，而不是读 lastImportSource：后者要等整份课表导入成功才落库，
        // 而 cookie 与 campusBaseUrl 在登录成功那一刻就写好了。两者在「登录成功、导入失败」时
        // 会分叉，按旧来源分派会让南宁会话被当成桂林——不弹窗、静默换票必失败、功能静默失效。
        currentSource = { importSourceForCampusBaseUrl(academicSessionStore.campusBaseUrl.first()) },
        // 桂林仍然走静默换票；它是唯一能免密补登录的来源。
        silentLogin = { academicLoginService.silentLogin() },
        scope = applicationScope
    )
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
    // 调休补课对照表：取数与重算刻意分开——取数只在用户主动刷新时发生（同节假日），
    // 重算是纯本地的纯函数调用，可以随时重跑。
    val makeupTableClient = MakeupTableClient()
    val autoDayCopyCoordinator = AutoDayCopyCoordinator(settingsStore, scheduleRepository)
}
