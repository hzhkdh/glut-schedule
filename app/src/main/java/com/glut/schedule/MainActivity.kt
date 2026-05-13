package com.glut.schedule

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glut.schedule.ui.pages.AcademicImportScreen
import com.glut.schedule.ui.pages.AcademicImportViewModel
import com.glut.schedule.ui.pages.AcademicImportViewModelFactory
import com.glut.schedule.ui.pages.ExamScreen
import com.glut.schedule.ui.pages.ExamViewModel
import com.glut.schedule.ui.pages.ExamViewModelFactory
import com.glut.schedule.ui.pages.ScheduleScreen
import com.glut.schedule.ui.pages.ScheduleViewModel
import com.glut.schedule.ui.pages.ScheduleViewModelFactory
import com.glut.schedule.ui.theme.GlutScheduleTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as ScheduleApplication).appContainer

        setContent {
            GlutScheduleTheme {
                var currentScreen by remember { mutableStateOf<MainScreen>(MainScreen.Schedule) }
                val scheduleViewModel: ScheduleViewModel = viewModel(
                    factory = ScheduleViewModelFactory(
                        repository = container.scheduleRepository,
                        settingsStore = container.settingsStore
                    )
                )
                val academicImportViewModel: AcademicImportViewModel = viewModel(
                    factory = AcademicImportViewModelFactory(
                        sessionStore = container.academicSessionStore,
                        importService = container.academicImportService,
                        scheduleRepository = container.scheduleRepository,
                        settingsStore = container.settingsStore,
                        parser = container.academicScheduleParser,
                        captureService = container.captureService,
                        apiProbeService = container.apiProbeService
                    )
                )
                val examViewModel: ExamViewModel = viewModel(
                    factory = ExamViewModelFactory(
                        repository = container.scheduleRepository,
                        sessionStore = container.academicSessionStore,
                        examService = container.academicExamService
                    )
                )

                when (currentScreen) {
                    MainScreen.Schedule -> ScheduleScreen(
                        viewModel = scheduleViewModel,
                        onImportClick = { currentScreen = MainScreen.AcademicImport },
                        onExamClick = { currentScreen = MainScreen.ExamSchedule }
                    )
                    MainScreen.AcademicImport -> AcademicImportScreen(
                        viewModel = academicImportViewModel,
                        onBack = { currentScreen = MainScreen.Schedule }
                    )
                    MainScreen.ExamSchedule -> ExamScreen(
                        viewModel = examViewModel,
                        onBack = { currentScreen = MainScreen.Schedule }
                    )
                }
            }
        }
    }
}

sealed class MainScreen {
    data object Schedule : MainScreen()
    data object AcademicImport : MainScreen()
    data object ExamSchedule : MainScreen()
}
