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
import com.glut.schedule.service.academic.CredentialStore
import com.glut.schedule.service.parser.CompositeScheduleParser
import com.glut.schedule.service.parser.GlutAcademicScheduleParser
import com.glut.schedule.service.parser.GlutExamParser
import com.glut.schedule.service.parser.NanningCurrcourseParser
import com.glut.schedule.service.parser.ScoreParser
import com.glut.schedule.service.UpdateChecker
import com.glut.schedule.ui.components.ScheduleBackgroundStore

class ScheduleApplication : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
    }
}

class AppContainer(application: Application) {
    private val database = Room.databaseBuilder(
        application,
        ScheduleDatabase::class.java,
        "glut_schedule.db"
    ).fallbackToDestructiveMigration(false)
     .build()

    val settingsStore = ScheduleSettingsStore(application)
    val scheduleRepository = ScheduleRepository(database.scheduleDao(), settingsStore.campusType)
    val backgroundStore = ScheduleBackgroundStore(application)
    val academicSessionStore = AcademicSessionStore(application)
    // Nanning parser first: it checks for infolist_common and returns empty
    // for non-Nanning HTML. Guilin parser handles everything else.
    val academicScheduleParser = CompositeScheduleParser(
        listOf(NanningCurrcourseParser(), GlutAcademicScheduleParser())
    )
    val apiProbeService = ApiProbeService()
    val examParser = GlutExamParser()
    val academicExamService = AcademicExamService(examParser)
    val credentialStore = CredentialStore(application)
    val academicLoginService = AcademicLoginService(academicSessionStore, credentialStore)
    val scoreParser = ScoreParser()
    val updateChecker = UpdateChecker()
}
