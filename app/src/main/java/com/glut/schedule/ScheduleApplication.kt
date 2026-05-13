package com.glut.schedule

import android.app.Application
import androidx.room.Room
import com.glut.schedule.data.local.ScheduleDatabase
import com.glut.schedule.data.repository.ScheduleRepository
import com.glut.schedule.data.settings.ScheduleSettingsStore
import com.glut.schedule.service.academic.AcademicImportService
import com.glut.schedule.service.academic.AcademicSessionStore
import com.glut.schedule.service.academic.DebugCaptureService
import com.glut.schedule.service.academic.ApiProbeService
import com.glut.schedule.service.academic.AcademicExamService
import com.glut.schedule.service.academic.AcademicLoginService
import com.glut.schedule.service.academic.CredentialStore
import com.glut.schedule.service.parser.GlutAcademicScheduleParser
import com.glut.schedule.service.parser.GlutExamParser

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

    val scheduleRepository = ScheduleRepository(database.scheduleDao())
    val settingsStore = ScheduleSettingsStore(application)
    val academicSessionStore = AcademicSessionStore(application)
    val academicImportService = AcademicImportService()
    val academicScheduleParser = GlutAcademicScheduleParser()
    val captureService = DebugCaptureService(application)
    val apiProbeService = ApiProbeService()
    val examParser = GlutExamParser()
    val academicExamService = AcademicExamService(examParser)
    val credentialStore = CredentialStore(application)
    val academicLoginService = AcademicLoginService(academicSessionStore, credentialStore)
}
