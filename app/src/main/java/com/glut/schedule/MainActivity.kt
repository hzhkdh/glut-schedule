package com.glut.schedule

import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as ScheduleApplication).appContainer
        val (backgroundTargetWidth, backgroundTargetHeight) = backgroundTargetSize()
        val initialCustomBackgroundUri = runBlocking(Dispatchers.IO) {
            container.settingsStore.customBackgroundUri.first()
        }
        if (initialCustomBackgroundUri.isNotBlank()) {
            Log.d("Recompose", "MainActivity background preload start")
            val loaded = container.backgroundStore.preloadBlocking(
                uri = initialCustomBackgroundUri,
                targetWidth = backgroundTargetWidth,
                targetHeight = backgroundTargetHeight
            )
            if (loaded) {
                Log.d("Recompose", "MainActivity background preload success")
            } else {
                Log.d("Recompose", "MainActivity background preload failure")
                runBlocking(Dispatchers.IO) {
                    container.settingsStore.setCustomBackgroundUri("")
                }
            }
        }

        setContent {
            GlutScheduleTheme {
                var currentScreen by remember { mutableStateOf<MainScreen>(MainScreen.Schedule) }
                val scheduleViewModel: ScheduleViewModel = viewModel(
                    factory = ScheduleViewModelFactory(
                        repository = container.scheduleRepository,
                        settingsStore = container.settingsStore,
                        sessionStore = container.academicSessionStore,
                        loginService = container.academicLoginService,
                        apiProbeService = container.apiProbeService,
                        parser = container.academicScheduleParser
                    )
                )
                val academicImportViewModel: AcademicImportViewModel = viewModel(
                    factory = AcademicImportViewModelFactory(
                        sessionStore = container.academicSessionStore,
                        scheduleRepository = container.scheduleRepository,
                        settingsStore = container.settingsStore,
                        parser = container.academicScheduleParser,
                        captureService = container.captureService,
                        apiProbeService = container.apiProbeService,
                        credentialStore = container.credentialStore
                    )
                )
                val examViewModel: ExamViewModel = viewModel(
                    factory = ExamViewModelFactory(
                        repository = container.scheduleRepository,
                        sessionStore = container.academicSessionStore,
                        examService = container.academicExamService,
                        loginService = container.academicLoginService
                    )
                )
                val examUiState by examViewModel.uiState.collectAsState()
                val scheduleUiState by scheduleViewModel.uiState.collectAsState()
                val scheduleBackgroundBitmap = if (scheduleUiState.customBackgroundUri.isNotBlank()) {
                    container.backgroundStore.get(scheduleUiState.customBackgroundUri)
                } else {
                    null
                }

                DisposableEffect(currentScreen) {
                    applySystemBarStyle(currentScreen)
                    onDispose { }
                }

                LaunchedEffect(examUiState.needsInteractiveLogin) {
                    if (examUiState.needsInteractiveLogin && currentScreen == MainScreen.ExamSchedule) {
                        examViewModel.consumeInteractiveLoginRequest()
                        currentScreen = MainScreen.AcademicImport
                    }
                }

                LaunchedEffect(scheduleUiState.needsInteractiveLogin) {
                    if (scheduleUiState.needsInteractiveLogin && currentScreen == MainScreen.Schedule) {
                        scheduleViewModel.consumeInteractiveLoginRequest()
                        currentScreen = MainScreen.AcademicImport
                    }
                }

                when (currentScreen) {
                    MainScreen.Schedule -> {
                        if (
                            scheduleUiState.customBackgroundUri.isNotBlank() &&
                            scheduleBackgroundBitmap == null
                        ) {
                            Log.d("Recompose", "ScheduleScreen blocked until background ready")
                        } else {
                            ScheduleScreen(
                                viewModel = scheduleViewModel,
                                backgroundStore = container.backgroundStore,
                                customBackgroundBitmap = scheduleBackgroundBitmap,
                                onImportClick = { currentScreen = MainScreen.AcademicImport },
                                onExamClick = { currentScreen = MainScreen.ExamSchedule }
                            )
                        }
                    }
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

    @Suppress("DEPRECATION")
    private fun applySystemBarStyle(screen: MainScreen) {
        val lightStatusBarFlag = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (screen == MainScreen.ExamSchedule) {
            window.statusBarColor = AndroidColor.rgb(246, 244, 239)
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility or lightStatusBarFlag
        } else {
            window.statusBarColor = AndroidColor.TRANSPARENT
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility and lightStatusBarFlag.inv()
        }
        window.navigationBarColor = AndroidColor.rgb(17, 24, 39)
    }

    private fun backgroundTargetSize(): Pair<Int, Int> {
        val metrics = resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }
}

sealed class MainScreen {
    data object Schedule : MainScreen()
    data object AcademicImport : MainScreen()
    data object ExamSchedule : MainScreen()
}
