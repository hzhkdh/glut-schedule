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
                var showImport by remember { mutableStateOf(false) }
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

                if (showImport) {
                    AcademicImportScreen(
                        viewModel = academicImportViewModel,
                        onBack = { showImport = false }
                    )
                } else {
                    ScheduleScreen(
                        viewModel = scheduleViewModel,
                        onImportClick = { showImport = true }
                    )
                }
            }
        }
    }
}
