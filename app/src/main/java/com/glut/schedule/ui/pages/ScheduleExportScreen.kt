package com.glut.schedule.ui.pages

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glut.schedule.R
import com.glut.schedule.data.model.AcademicSemester
import com.glut.schedule.data.model.SemesterCacheStatus
import com.glut.schedule.data.model.CourseBlock
import com.glut.schedule.data.model.SemesterAdjustment
import com.glut.schedule.data.model.countDistinctCourseTitles
import com.glut.schedule.data.model.scheduleWeekForNumber
import com.glut.schedule.data.settings.CampusCapabilities
import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.data.settings.capabilitiesFor
import com.glut.schedule.service.export.ExportSolidBackground
import com.glut.schedule.service.export.ScheduleBinaryExporter
import com.glut.schedule.service.export.ScheduleCsvResult
import com.glut.schedule.service.export.ScheduleExportSaveResult
import com.glut.schedule.service.export.ScheduleIcsResult
import com.glut.schedule.service.export.ScheduleImageExporter
import com.glut.schedule.service.export.PdfBackground
import com.glut.schedule.service.export.PdfPageStyle
import com.glut.schedule.service.export.SchedulePdfWriter
import com.glut.schedule.service.export.ScheduleTextFileExporter
import com.glut.schedule.service.export.buildScheduleCsv
import com.glut.schedule.service.export.buildScheduleIcs
import com.glut.schedule.service.export.scheduleCsvFileName
import com.glut.schedule.service.export.scheduleIcsFileName
import com.glut.schedule.service.export.renderScheduleWeekImage
import com.glut.schedule.service.export.scheduleImageFileName
import com.glut.schedule.service.export.schedulePdfFileName
import com.glut.schedule.ui.components.ScheduleGrid
import com.glut.schedule.ui.components.shouldUseCustomBackground
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val exportDateFormatter = DateTimeFormatter.ofPattern("M月d日")

/**
 * 导出课表页。
 *
 * 图片与日历共用同一份「当前查看学期」的数据：
 * - 图片：把 `ScheduleGrid` 以只读方式铺开，再通过 GraphicsLayer 录制成位图；
 * - 日历：把 `courseBlocksByWeek` 已经算好的课次写成 RFC 5545 事件。
 *
 * 两条路都不重新推导周次与调休规则——屏幕看到什么，导出就是什么。
 */
@Composable
fun ScheduleExportScreen(
    uiState: ScheduleUiState,
    adjustments: List<SemesterAdjustment>,
    customBackgroundBitmap: ImageBitmap?,
    onImportClick: () -> Unit,
    onSelectSemester: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    if (!uiState.isInitialized) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color(0xFF3F7DF6))
        }
        return
    }
    if (uiState.allCourses.isEmpty()) {
        ExportEmptyState(onImportClick = onImportClick, modifier = modifier.fillMaxSize())
        return
    }

    val capabilities = remember(uiState.campusType) { capabilitiesFor(uiState.campusType) }
    val blocksByWeek = remember(
        uiState.courses,
        uiState.maxAcademicWeek,
        uiState.semesterStartMonday,
        uiState.manualDayCopies
    ) {
        courseBlocksByWeek(
            courses = uiState.courses,
            maxWeek = uiState.maxAcademicWeek,
            semesterStartMonday = uiState.semesterStartMonday,
            rules = uiState.manualDayCopies
        )
    }
    val semesterName = uiState.viewedSemester?.displayName.orEmpty()
    val semesterSelection = SemesterSelectionState(
        semesters = uiState.semesters,
        viewed = uiState.viewedSemester,
        isHistorical = uiState.isHistoricalSemester,
        onSelect = onSelectSemester,
        onManage = onImportClick
    )

    var tab by remember { mutableIntStateOf(0) }
    var selectedWeek by remember(uiState.viewedSemester?.id) {
        mutableIntStateOf(uiState.week.number)
    }
    val hasCustomBackground = remember(uiState.customBackgroundUri) {
        shouldUseCustomBackground(uiState.customBackgroundUri)
    }
    // 自定义背景可能是私人照片：默认导出纯色底，用户主动打开才把背景烤进图片。
    var useCurrentBackground by remember(uiState.viewedSemester?.id, hasCustomBackground) {
        mutableStateOf(!hasCustomBackground)
    }
    var solidBackground by remember { mutableStateOf(ExportSolidBackground.DEFAULT) }
    var showTeacher by remember { mutableStateOf(true) }
    var showRoom by remember { mutableStateOf(true) }

    val graphicsLayer = rememberGraphicsLayer()
    var imageBusy by remember { mutableStateOf(false) }
    var calendarBusy by remember { mutableStateOf(false) }
    var csvBusy by remember { mutableStateOf(false) }
    var pdfBusy by remember { mutableStateOf(false) }
    var pdfWholeSemester by remember { mutableStateOf(false) }
    var pdfPageIndex by remember { mutableIntStateOf(0) }
    var pdfPageTotal by remember { mutableIntStateOf(0) }
    var imageSaveRetry by remember { mutableIntStateOf(0) }
    var calendarSaveRetry by remember { mutableIntStateOf(0) }
    var csvSaveRetry by remember { mutableIntStateOf(0) }
    var pdfSaveRetry by remember { mutableIntStateOf(0) }
    var pendingPermissionKind by remember { mutableIntStateOf(0) }
    var permissionTrigger by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val kind = pendingPermissionKind
        pendingPermissionKind = 0
        if (granted) {
            when (kind) {
                PERMISSION_IMAGE -> imageSaveRetry++
                PERMISSION_CALENDAR -> calendarSaveRetry++
                PERMISSION_CSV -> csvSaveRetry++
                PERMISSION_PDF -> pdfSaveRetry++
            }
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("没有存储权限，无法保存到文件")
            }
        }
    }
    LaunchedEffect(permissionTrigger) {
        if (permissionTrigger > 0) {
            permissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    val week = scheduleWeekForNumber(
        selectedWeek,
        uiState.semesterStartMonday,
        uiState.maxAcademicWeek
    )
    val imageFileName = scheduleImageFileName(
        semesterName = semesterName,
        weekNumber = selectedWeek,
        weekMonday = week.monday
    )

    val imagePage = remember(
        selectedWeek,
        blocksByWeek,
        uiState.classPeriods,
        uiState.showNoon,
        uiState.showWeekend,
        uiState.hasAuthoritativeCalendar,
        uiState.holidayDates,
        uiState.manualDayCopies,
        adjustments,
        showTeacher,
        showRoom,
        capabilities.hasNoonSections,
        semesterName
    ) {
        buildPdfWeekPage(
            semesterName = semesterName,
            week = week,
            blocks = blocksByWeek[selectedWeek].orEmpty(),
            periods = uiState.classPeriods,
            showNoon = uiState.showNoon,
            hasNoonSections = capabilities.hasNoonSections,
            showWeekend = uiState.showWeekend,
            showCalendarDates = uiState.hasAuthoritativeCalendar,
            holidayDates = uiState.holidayDates,
            manualAdjustmentDates = uiState.manualDayCopies.mapTo(mutableSetOf()) { it.targetDate },
            adjustments = adjustments,
            showTeacher = showTeacher,
            showRoom = showRoom
        )
    }

    suspend fun loadImageExportBackground(): PdfBackground = withContext(Dispatchers.IO) {
        when {
            !useCurrentBackground -> PdfBackground.Solid(
                solidBackground.topColor,
                solidBackground.bottomColor
            )
            hasCustomBackground -> customBackgroundBitmap
                ?.asAndroidBitmap()
                ?.let { PdfBackground.Image(it) }
                ?: PdfBackground.Solid(solidBackground.topColor, solidBackground.bottomColor)
            else -> runCatching {
                android.graphics.BitmapFactory.decodeResource(
                    context.resources,
                    R.drawable.builtin_flower_background
                )
            }.getOrNull()?.let { PdfBackground.Image(it) }
                ?: PdfBackground.Solid(solidBackground.topColor, solidBackground.bottomColor)
        }
    }

    suspend fun saveImage() {
        if (imageBusy) return
        imageBusy = true
        var rendered: android.graphics.Bitmap? = null
        var ownedBackground: android.graphics.Bitmap? = null
        try {
            val background = loadImageExportBackground()
            ownedBackground = (background as? PdfBackground.Image)?.bitmap
                ?.takeIf { useCurrentBackground && !hasCustomBackground }
            rendered = withContext(Dispatchers.Default) {
                renderScheduleWeekImage(imagePage, background, uiState.backgroundDimAmount)
            }
            when (val result = ScheduleImageExporter.saveToGallery(context, rendered, imageFileName)) {
                is ScheduleExportSaveResult.Saved ->
                    snackbarHostState.showSnackbar("已保存到相册")
                ScheduleExportSaveResult.PermissionRequired -> {
                    pendingPermissionKind = PERMISSION_IMAGE
                    permissionTrigger++
                }
                is ScheduleExportSaveResult.Failed ->
                    snackbarHostState.showSnackbar("保存失败：${result.message}")
            }
        } catch (_: Exception) {
            snackbarHostState.showSnackbar("生成图片失败，请重试")
        } finally {
            rendered?.recycle()
            ownedBackground?.recycle()
            imageBusy = false
        }
    }

    suspend fun shareImage() {
        if (imageBusy) return
        imageBusy = true
        var rendered: android.graphics.Bitmap? = null
        var ownedBackground: android.graphics.Bitmap? = null
        try {
            val background = loadImageExportBackground()
            ownedBackground = (background as? PdfBackground.Image)?.bitmap
                ?.takeIf { useCurrentBackground && !hasCustomBackground }
            rendered = withContext(Dispatchers.Default) {
                renderScheduleWeekImage(imagePage, background, uiState.backgroundDimAmount)
            }
            ScheduleImageExporter.share(context, rendered, imageFileName)
                .onFailure { snackbarHostState.showSnackbar("分享失败，请重试") }
        } catch (_: Exception) {
            snackbarHostState.showSnackbar("生成图片失败，请重试")
        } finally {
            rendered?.recycle()
            ownedBackground?.recycle()
            imageBusy = false
        }
    }

    val icsResult = remember(
        semesterName,
        blocksByWeek,
        uiState.classPeriods,
        uiState.semesterStartMonday,
        uiState.maxAcademicWeek,
        adjustments,
        capabilities.hasNoonSections,
        showTeacher,
        showRoom
    ) {
        buildScheduleIcs(
            calendarName = semesterName.ifBlank { "桂工课表" },
            blocksByWeek = blocksByWeek,
            periods = uiState.classPeriods,
            semesterStartMonday = uiState.semesterStartMonday,
            maxWeek = uiState.maxAcademicWeek,
            adjustments = adjustments,
            hasNoonSections = capabilities.hasNoonSections,
            includeRoom = showRoom,
            includeTeacher = showTeacher
        )
    }
    val icsFileName = scheduleIcsFileName(semesterName)

    suspend fun saveCalendar() {
        if (calendarBusy) return
        val success = icsResult as? ScheduleIcsResult.Success ?: return
        calendarBusy = true
        try {
            when (
                val result = ScheduleTextFileExporter.saveToDownloads(
                    context = context,
                    fileName = icsFileName,
                    mimeType = "text/calendar",
                    content = success.content
                )
            ) {
                is ScheduleExportSaveResult.Saved ->
                    snackbarHostState.showSnackbar("已保存到下载目录")
                ScheduleExportSaveResult.PermissionRequired -> {
                    pendingPermissionKind = PERMISSION_CALENDAR
                    permissionTrigger++
                }
                is ScheduleExportSaveResult.Failed ->
                    snackbarHostState.showSnackbar("保存失败：${result.message}")
            }
        } finally {
            calendarBusy = false
        }
    }

    suspend fun shareCalendar() {
        if (calendarBusy) return
        val success = icsResult as? ScheduleIcsResult.Success ?: return
        calendarBusy = true
        try {
            ScheduleTextFileExporter.share(
                context = context,
                fileName = icsFileName,
                mimeType = "text/calendar",
                content = success.content
            ).onFailure { snackbarHostState.showSnackbar("分享失败，请重试") }
        } finally {
            calendarBusy = false
        }
    }

    val csvResult = remember(
        blocksByWeek,
        capabilities.hasNoonSections,
        showTeacher,
        showRoom
    ) {
        buildScheduleCsv(
            blocksByWeek = blocksByWeek,
            hasNoonSections = capabilities.hasNoonSections,
            includeRoom = showRoom,
            includeTeacher = showTeacher
        )
    }
    val csvFileName = scheduleCsvFileName(semesterName)
    val pdfFileName = schedulePdfFileName(
        semesterName = semesterName,
        weekNumber = selectedWeek.takeUnless { pdfWholeSemester },
        wholeSemester = pdfWholeSemester
    )

    suspend fun saveCsv() {
        if (csvBusy) return
        val success = csvResult as? ScheduleCsvResult.Success ?: return
        csvBusy = true
        try {
            when (
                val result = ScheduleTextFileExporter.saveToDownloads(
                    context = context,
                    fileName = csvFileName,
                    mimeType = "text/csv",
                    content = success.content
                )
            ) {
                is ScheduleExportSaveResult.Saved ->
                    snackbarHostState.showSnackbar("已保存到下载目录")
                ScheduleExportSaveResult.PermissionRequired -> {
                    pendingPermissionKind = PERMISSION_CSV
                    permissionTrigger++
                }
                is ScheduleExportSaveResult.Failed ->
                    snackbarHostState.showSnackbar("保存失败：${result.message}")
            }
        } finally {
            csvBusy = false
        }
    }

    suspend fun shareCsv() {
        if (csvBusy) return
        val success = csvResult as? ScheduleCsvResult.Success ?: return
        csvBusy = true
        try {
            ScheduleTextFileExporter.share(
                context = context,
                fileName = csvFileName,
                mimeType = "text/csv",
                content = success.content
            ).onFailure { snackbarHostState.showSnackbar("分享失败，请重试") }
        } finally {
            csvBusy = false
        }
    }

    suspend fun exportPdf(share: Boolean) {
        if (pdfBusy) return
        val weeks = if (pdfWholeSemester) {
            (1..uiState.maxAcademicWeek).toList()
        } else {
            listOf(selectedWeek)
        }
        if (weeks.isEmpty()) return
        pdfBusy = true
        pdfPageIndex = 0
        pdfPageTotal = weeks.size
        // 背景与「图片」页保持一致：自定义图用已解码的位图，内置背景现解一张。
        val background = withContext(Dispatchers.IO) {
            when {
                !useCurrentBackground -> PdfBackground.Solid(
                    solidBackground.topColor,
                    solidBackground.bottomColor
                )
                hasCustomBackground -> customBackgroundBitmap
                    ?.asAndroidBitmap()
                    ?.let { PdfBackground.Image(it) }
                    ?: PdfBackground.Solid(solidBackground.topColor, solidBackground.bottomColor)
                else -> runCatching {
                    android.graphics.BitmapFactory.decodeResource(
                        context.resources,
                        R.drawable.builtin_flower_background
                    )
                }.getOrNull()?.let { PdfBackground.Image(it) }
                    ?: PdfBackground.Solid(solidBackground.topColor, solidBackground.bottomColor)
            }
        }
        val decodedBuiltin = useCurrentBackground && !hasCustomBackground &&
            background is PdfBackground.Image
        val writer = SchedulePdfWriter(
            PdfPageStyle(background = background, dimAmount = uiState.backgroundDimAmount)
        )
        try {
            weeks.forEach { weekNumber ->
                val weekInfo = scheduleWeekForNumber(
                    weekNumber,
                    uiState.semesterStartMonday,
                    uiState.maxAcademicWeek
                )
                writer.addPage(
                    buildPdfWeekPage(
                        semesterName = semesterName,
                        week = weekInfo,
                        blocks = blocksByWeek[weekNumber].orEmpty(),
                        periods = uiState.classPeriods,
                        showNoon = uiState.showNoon,
                        hasNoonSections = capabilities.hasNoonSections,
                        showWeekend = uiState.showWeekend,
                        showCalendarDates = uiState.hasAuthoritativeCalendar,
                        holidayDates = uiState.holidayDates,
                        manualAdjustmentDates = uiState.manualDayCopies
                            .mapTo(mutableSetOf()) { it.targetDate },
                        adjustments = adjustments,
                        showTeacher = showTeacher,
                        showRoom = showRoom,
                        pageNumber = pdfPageIndex + 1,
                        pageCount = weeks.size
                    )
                )
                pdfPageIndex++
            }
            val bytes = withContext(Dispatchers.Default) { writer.build() }
            if (share) {
                ScheduleBinaryExporter.share(
                    context = context,
                    fileName = pdfFileName,
                    mimeType = "application/pdf",
                    bytes = bytes
                ).onFailure { snackbarHostState.showSnackbar("分享失败，请重试") }
            } else {
                when (
                    val result = ScheduleBinaryExporter.saveToDownloads(
                        context = context,
                        fileName = pdfFileName,
                        mimeType = "application/pdf",
                        bytes = bytes
                    )
                ) {
                    is ScheduleExportSaveResult.Saved ->
                        snackbarHostState.showSnackbar("已保存到下载目录")
                    ScheduleExportSaveResult.PermissionRequired -> {
                        pendingPermissionKind = PERMISSION_PDF
                        permissionTrigger++
                    }
                    is ScheduleExportSaveResult.Failed ->
                        snackbarHostState.showSnackbar("保存失败：${result.message}")
                }
            }
        } catch (error: Exception) {
            android.util.Log.e("ScheduleExport", "PDF export failed", error)
            snackbarHostState.showSnackbar("生成 PDF 失败：${error.message ?: error.javaClass.simpleName}")
        } finally {
            writer.close()
            if (decodedBuiltin) {
                (background as? PdfBackground.Image)?.bitmap?.recycle()
            }
            pdfBusy = false
        }
    }

    LaunchedEffect(imageSaveRetry) {
        if (imageSaveRetry > 0) saveImage()
    }
    LaunchedEffect(csvSaveRetry) {
        if (csvSaveRetry > 0) saveCsv()
    }
    LaunchedEffect(pdfSaveRetry) {
        if (pdfSaveRetry > 0) exportPdf(share = false)
    }
    LaunchedEffect(calendarSaveRetry) {
        if (calendarSaveRetry > 0) saveCalendar()
    }

    Box(modifier.fillMaxSize()) {
        Column(modifier.fillMaxSize()) {
            PrimaryTabRow(
                selectedTabIndex = tab,
                containerColor = Color(0xFFE8E4D6),
                contentColor = Color(0xFF141821)
            ) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("图片") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("PDF") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("表格") })
                Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("日历") })
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    0 -> ImageExportTab(
                        uiState = uiState,
                        semesterSelection = semesterSelection,
                        capabilities = capabilities,
                        week = week,
                        selectedWeek = selectedWeek,
                        onSelectWeek = { selectedWeek = it },
                        blocks = blocksByWeek[selectedWeek].orEmpty(),
                        adjustments = adjustments,
                        useCurrentBackground = useCurrentBackground,
                        onUseCurrentBackgroundChange = { useCurrentBackground = it },
                        hasCustomBackground = hasCustomBackground,
                        solidBackground = solidBackground,
                        onSolidBackgroundChange = { solidBackground = it },
                        customBackgroundBitmap = customBackgroundBitmap,
                        showTeacher = showTeacher,
                        onShowTeacherChange = { showTeacher = it },
                        showRoom = showRoom,
                        onShowRoomChange = { showRoom = it },
                        graphicsLayer = graphicsLayer,
                        isBusy = imageBusy,
                        onSave = { scope.launch { saveImage() } },
                        onShare = { scope.launch { shareImage() } }
                    )
                    1 -> PdfExportTab(
                        semesterSelection = semesterSelection,
                        selectedWeek = selectedWeek,
                        wholeSemester = pdfWholeSemester,
                        onWholeSemesterChange = { pdfWholeSemester = it },
                        currentWeekNumber = uiState.currentWeekNumber,
                        maxAcademicWeek = uiState.maxAcademicWeek,
                        weekMonday = week.monday,
                        onSelectWeek = { selectedWeek = it },
                        useCurrentBackground = useCurrentBackground,
                        onUseCurrentBackgroundChange = { useCurrentBackground = it },
                        hasCustomBackground = hasCustomBackground,
                        solidBackground = solidBackground,
                        onSolidBackgroundChange = { solidBackground = it },
                        showTeacher = showTeacher,
                        onShowTeacherChange = { showTeacher = it },
                        showRoom = showRoom,
                        onShowRoomChange = { showRoom = it },
                        pageCount = if (pdfWholeSemester) uiState.maxAcademicWeek else 1,
                        isBusy = pdfBusy,
                        onSave = { scope.launch { exportPdf(share = false) } },
                        onShare = { scope.launch { exportPdf(share = true) } }
                    )
                    2 -> CsvExportTab(
                        semesterSelection = semesterSelection,
                        result = csvResult,
                        showTeacher = showTeacher,
                        onShowTeacherChange = { showTeacher = it },
                        showRoom = showRoom,
                        onShowRoomChange = { showRoom = it },
                        isBusy = csvBusy,
                        onSave = { scope.launch { saveCsv() } },
                        onShare = { scope.launch { shareCsv() } }
                    )
                    else -> CalendarExportTab(
                        uiState = uiState,
                        semesterSelection = semesterSelection,
                        isGraduate = uiState.campusType == CampusType.GRADUATE,
                        courseCount = uiState.courses.countDistinctCourseTitles(),
                        result = icsResult,
                        showTeacher = showTeacher,
                        onShowTeacherChange = { showTeacher = it },
                        showRoom = showRoom,
                        onShowRoomChange = { showRoom = it },
                        isBusy = calendarBusy,
                        onSave = { scope.launch { saveCalendar() } },
                        onShare = { scope.launch { shareCalendar() } }
                    )
                }
            }
        }
        if (pdfBusy) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xF2F6F4EF)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF3F7DF6))
                    Spacer(Modifier.height(12.dp))
                    Text("正在生成 PDF…", color = Color(0xFF141821), fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("$pdfPageIndex / $pdfPageTotal 页", color = Color(0xFF566072), fontSize = 13.sp)
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
        )
    }
}

private const val PERMISSION_IMAGE = 1
private const val PERMISSION_CALENDAR = 2
private const val PERMISSION_CSV = 3
private const val PERMISSION_PDF = 4

@Composable
private fun ImageExportTab(
    uiState: ScheduleUiState,
    semesterSelection: SemesterSelectionState,
    capabilities: CampusCapabilities,
    week: com.glut.schedule.data.model.ScheduleWeek,
    selectedWeek: Int,
    onSelectWeek: (Int) -> Unit,
    blocks: List<CourseBlock>,
    adjustments: List<SemesterAdjustment>,
    useCurrentBackground: Boolean,
    onUseCurrentBackgroundChange: (Boolean) -> Unit,
    hasCustomBackground: Boolean,
    solidBackground: ExportSolidBackground,
    onSolidBackgroundChange: (ExportSolidBackground) -> Unit,
    customBackgroundBitmap: ImageBitmap?,
    showTeacher: Boolean,
    onShowTeacherChange: (Boolean) -> Unit,
    showRoom: Boolean,
    onShowRoomChange: (Boolean) -> Unit,
    graphicsLayer: androidx.compose.ui.graphics.layer.GraphicsLayer,
    isBusy: Boolean,
    onSave: () -> Unit,
    onShare: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp)
        ) {
            SemesterSelectionCard(semesterSelection)

            WeekSelectionCard(
                selectedWeek = selectedWeek,
                currentWeekNumber = uiState.currentWeekNumber,
                maxAcademicWeek = uiState.maxAcademicWeek,
                weekMonday = week.monday,
                onSelectWeek = onSelectWeek
            )

            BackgroundChoiceCard(
                useCurrentBackground = useCurrentBackground,
                onUseCurrentBackgroundChange = onUseCurrentBackgroundChange,
                hasCustomBackground = hasCustomBackground,
                solidBackground = solidBackground,
                onSolidBackgroundChange = onSolidBackgroundChange
            )

            ContentTogglesCard(
                showTeacher = showTeacher,
                onShowTeacherChange = onShowTeacherChange,
                showRoom = showRoom,
                onShowRoomChange = onShowRoomChange
            )

            SectionCard {
                Text(
                    text = "预览",
                    color = Color(0xFF141821),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(8.dp))
                ScheduleCaptureBox(graphicsLayer) {
                    ExportSheet(
                        uiState = uiState,
                        capabilities = capabilities,
                        week = week,
                        blocks = blocks,
                        adjustments = adjustments,
                        useCurrentBackground = useCurrentBackground,
                        solidBackground = solidBackground,
                        customBackgroundBitmap = customBackgroundBitmap,
                        showTeacher = showTeacher,
                        showRoom = showRoom
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "预览使用与导出版式相同的网格数据，字体渲染可能与图片略有差异。冲突课仅显示当前这张，右下角数字表示冲突门数。",
                    color = Color(0xFF7A8291),
                    fontSize = 11.sp
                )
            }

        Spacer(Modifier.height(12.dp))
        }
        ExportActionBar {
            ExportActionButtons(
                saveLabel = "保存到相册",
                shareLabel = "分享",
                isBusy = isBusy,
                onSave = onSave,
                onShare = onShare
            )
        }
    }
}

@Composable
private fun ExportSheet(
    uiState: ScheduleUiState,
    capabilities: CampusCapabilities,
    week: com.glut.schedule.data.model.ScheduleWeek,
    blocks: List<CourseBlock>,
    adjustments: List<SemesterAdjustment>,
    useCurrentBackground: Boolean,
    solidBackground: ExportSolidBackground,
    customBackgroundBitmap: ImageBitmap?,
    showTeacher: Boolean,
    showRoom: Boolean
) {
    val hasCustom = shouldUseCustomBackground(uiState.customBackgroundUri)
    val lightContent = !useCurrentBackground && solidBackground.isLight
    Box(Modifier.fillMaxWidth()) {
        when {
            useCurrentBackground && !hasCustom -> Image(
                painter = painterResource(R.drawable.builtin_flower_background),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop
            )
            useCurrentBackground && customBackgroundBitmap != null -> Image(
                bitmap = customBackgroundBitmap,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop
            )
            else -> Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(solidBackground.topColor),
                                Color(solidBackground.bottomColor)
                            )
                        )
                    )
            )
        }
        if (useCurrentBackground) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = uiState.backgroundDimAmount))
            )
        }
        Column(Modifier.fillMaxWidth()) {
            ScheduleGrid(
                week = week,
                today = week.monday,
                periods = uiState.classPeriods,
                blocks = blocks,
                showWeekend = uiState.showWeekend,
                showNoon = uiState.showNoon,
                showGridLines = uiState.showGridLines,
                scrollable = false,
                highlightToday = false,
                showTeacher = showTeacher,
                showRoom = showRoom,
                hasNoonSections = capabilities.hasNoonSections,
                blockEndSections = capabilities.blockEndSections,
                showCalendarDates = uiState.hasAuthoritativeCalendar,
                holidayDates = uiState.holidayDates,
                manualAdjustmentDates = uiState.manualDayCopies.mapTo(mutableSetOf()) { it.targetDate },
                adjustments = adjustments,
                lightContent = lightContent
            )
            Text(
                text = "${uiState.viewedSemester?.displayName?.takeIf { it.isNotBlank() } ?: "当前学期"} · 第 ${week.number} 周 · 由「桂工课表」导出",
                color = if (lightContent) {
                    Color(0xFF566072)
                } else {
                    Color.White.copy(alpha = 0.55f)
                },
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun CalendarExportTab(
    uiState: ScheduleUiState,
    semesterSelection: SemesterSelectionState,
    isGraduate: Boolean,
    courseCount: Int,
    result: ScheduleIcsResult,
    showTeacher: Boolean,
    onShowTeacherChange: (Boolean) -> Unit,
    showRoom: Boolean,
    onShowRoomChange: (Boolean) -> Unit,
    isBusy: Boolean,
    onSave: () -> Unit,
    onShare: () -> Unit
) {
    val eventCount = (result as? ScheduleIcsResult.Success)?.eventCount
    val enabled = result is ScheduleIcsResult.Success && !isBusy
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp)
        ) {
            SemesterSelectionCard(semesterSelection)

            SectionCard {
                Text(
                    text = "$courseCount 门课 · ${eventCount?.toString() ?: "—"} 个事件",
                    color = Color(0xFF141821),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "第 1 – ${uiState.maxAcademicWeek} 周 · " +
                        "${uiState.semesterStartMonday} → ${uiState.semesterEndDate}",
                    color = Color(0xFF566072),
                    fontSize = 12.sp
                )
            }

            ContentTogglesCard(
                showTeacher = showTeacher,
                onShowTeacherChange = onShowTeacherChange,
                showRoom = showRoom,
                onShowRoomChange = onShowRoomChange
            )

            SectionCard {
                Text(
                    text = when (result) {
                        is ScheduleIcsResult.MissingClassTimes -> if (isGraduate) {
                            "研究生课表没有上课时间\n\n门户只下发节次，不下发钟点，导出日历会得到错误的时间。如果你在设置里补全了 12 节的时间，这里会自动可用。"
                        } else {
                            "当前作息没有可用的上课时间，无法生成日历。请到设置里检查上课时间。"
                        }
                        is ScheduleIcsResult.NoEvents -> "当前学期没有可写入日历的课次"
                        else -> "· 停课的周次不会生成事件\n· 调课、补课按实际周次写入\n· 节假日不单独生成事件\n· 研究生门户不提供上课时间，无法生成日历"
                    },
                    color = if (result is ScheduleIcsResult.Success) Color(0xFF566072) else Color(0xFF8A6D3B),
                    fontSize = 12.sp
                )
            }

        Spacer(Modifier.height(12.dp))
        }
        ExportActionBar {
            ExportActionButtons(
                saveLabel = "保存 .ics 文件",
                shareLabel = "分享 .ics",
                isBusy = isBusy,
                enabled = enabled,
                onSave = onSave,
                onShare = onShare
            )
        }
    }
}

@Composable
private fun PdfExportTab(
    semesterSelection: SemesterSelectionState,
    selectedWeek: Int,
    wholeSemester: Boolean,
    onWholeSemesterChange: (Boolean) -> Unit,
    currentWeekNumber: Int,
    maxAcademicWeek: Int,
    weekMonday: java.time.LocalDate,
    onSelectWeek: (Int) -> Unit,
    useCurrentBackground: Boolean,
    onUseCurrentBackgroundChange: (Boolean) -> Unit,
    hasCustomBackground: Boolean,
    solidBackground: ExportSolidBackground,
    onSolidBackgroundChange: (ExportSolidBackground) -> Unit,
    showTeacher: Boolean,
    onShowTeacherChange: (Boolean) -> Unit,
    showRoom: Boolean,
    onShowRoomChange: (Boolean) -> Unit,
    pageCount: Int,
    isBusy: Boolean,
    onSave: () -> Unit,
    onShare: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp)
        ) {
            SemesterSelectionCard(semesterSelection)

            SectionCard {
                SectionTitle("范围")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExportChip(
                        selected = !wholeSemester,
                        onClick = { onWholeSemesterChange(false) },
                        label = "当前周"
                    )
                    ExportChip(
                        selected = wholeSemester,
                        onClick = { onWholeSemesterChange(true) },
                        label = "整学期"
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (wholeSemester) {
                        "共 $pageCount 页，每周一页"
                    } else {
                        "第 $selectedWeek 周，单页"
                    },
                    color = Color(0xFF566072),
                    fontSize = 13.sp
                )
            }

            if (!wholeSemester) {
                WeekSelectionCard(
                    selectedWeek = selectedWeek,
                    currentWeekNumber = currentWeekNumber,
                    maxAcademicWeek = maxAcademicWeek,
                    weekMonday = weekMonday,
                    onSelectWeek = onSelectWeek
                )
            }

            BackgroundChoiceCard(
                useCurrentBackground = useCurrentBackground,
                onUseCurrentBackgroundChange = onUseCurrentBackgroundChange,
                hasCustomBackground = hasCustomBackground,
                solidBackground = solidBackground,
                onSolidBackgroundChange = onSolidBackgroundChange
            )

            ContentTogglesCard(
                showTeacher = showTeacher,
                onShowTeacherChange = onShowTeacherChange,
                showRoom = showRoom,
                onShowRoomChange = onShowRoomChange
            )

            SectionCard {
                SectionTitle("版式")
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "A4 纵向，背景与图片页同一套选择；每周一页。冲突课显示第一张，并在右下角标注冲突门数。",
                    color = Color(0xFF566072),
                    fontSize = 12.sp
                )
            }
        }
        ExportActionBar {
            ExportActionButtons(
                saveLabel = "保存 PDF",
                shareLabel = "分享 PDF",
                isBusy = isBusy,
                onSave = onSave,
                onShare = onShare
            )
        }
    }
}

@Composable
private fun CsvExportTab(
    semesterSelection: SemesterSelectionState,
    result: ScheduleCsvResult,
    showTeacher: Boolean,
    onShowTeacherChange: (Boolean) -> Unit,
    showRoom: Boolean,
    onShowRoomChange: (Boolean) -> Unit,
    isBusy: Boolean,
    onSave: () -> Unit,
    onShare: () -> Unit
) {
    val rowCount = (result as? ScheduleCsvResult.Success)?.rowCount ?: 0
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp)
        ) {
            SemesterSelectionCard(semesterSelection)

            SectionCard {
                SectionTitle("表格")
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "$rowCount 行 · 可用 Excel / WPS 打开",
                    color = Color(0xFF141821),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "UTF-8（含 BOM），一行一条课次，周次保留教务原文；节次写成「第3-4节」，避免 Excel 把它识别成日期。",
                    color = Color(0xFF566072),
                    fontSize = 12.sp
                )
            }

            ContentTogglesCard(
                showTeacher = showTeacher,
                onShowTeacherChange = onShowTeacherChange,
                showRoom = showRoom,
                onShowRoomChange = onShowRoomChange
            )

            SectionCard {
                SectionTitle("列")
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildList {
                        add("课程")
                        add("星期")
                        add("节次")
                        add("周次")
                        if (showRoom) add("教室")
                        if (showTeacher) add("教师")
                    }.joinToString("、"),
                    color = Color(0xFF566072),
                    fontSize = 13.sp
                )
            }
        }
        ExportActionBar {
            ExportActionButtons(
                saveLabel = "保存 CSV",
                shareLabel = "分享 CSV",
                isBusy = isBusy,
                enabled = rowCount > 0,
                onSave = onSave,
                onShare = onShare
            )
        }
    }
}

@Composable
private fun ScheduleCaptureBox(
    graphicsLayer: GraphicsLayer,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .drawWithContent {
                graphicsLayer.record(
                    this,
                    layoutDirection,
                    IntSize(size.width.roundToInt(), size.height.roundToInt())
                ) {
                    this@drawWithContent.drawContent()
                }
                drawLayer(graphicsLayer)
            },
        content = content
    )
}

private data class SemesterSelectionState(
    val semesters: List<AcademicSemester>,
    val viewed: AcademicSemester?,
    val isHistorical: Boolean,
    val onSelect: (String) -> Unit,
    val onManage: () -> Unit
)

@Composable
private fun SemesterSelectionCard(state: SemesterSelectionState) {
    var expanded by remember { mutableStateOf(false) }
    val options = state.semesters.filter {
        it.isCurrent || it.cacheStatus == SemesterCacheStatus.CACHED
    }
    SectionCard {
        SectionTitle("学期")
        Spacer(Modifier.height(8.dp))
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFF3F5F9))
                    .border(1.dp, Color(0xFFD6DDE8), RoundedCornerShape(10.dp))
                    .clickable { expanded = true }
                    .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = state.viewed?.displayName ?: "未导入学期",
                    color = Color(0xFF141821),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "切换",
                    color = Color(0xFF1B5FE0),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.width(2.dp))
                Icon(
                    Icons.Outlined.KeyboardArrowDown,
                    contentDescription = "切换学期",
                    tint = Color(0xFF1B5FE0),
                    modifier = Modifier.size(18.dp)
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = Color(0xFFF6F4EF)
            ) {
                options.forEach { semester ->
                    val viewing = semester.id == state.viewed?.id
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = semester.displayName,
                                    color = Color(0xFF141821),
                                    fontSize = 14.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = when {
                                        viewing -> "查看中"
                                        semester.isCurrent -> "当前学期"
                                        else -> "已缓存"
                                    },
                                    color = if (viewing) Color(0xFF3F7DF6) else Color(0xFF7A8291),
                                    fontSize = 12.sp
                                )
                            }
                        },
                        onClick = {
                            expanded = false
                            state.onSelect(semester.id)
                        }
                    )
                }
                HorizontalDivider(color = Color(0xFFDDE2EA))
                DropdownMenuItem(
                    text = {
                        Text("管理与下载其他学期", color = Color(0xFF3F7DF6), fontSize = 14.sp)
                    },
                    onClick = {
                        expanded = false
                        state.onManage()
                    }
                )
            }
        }
        if (state.isHistorical) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "历史学期为只读快照，导出内容不含调休调课",
                color = Color(0xFF8A6D3B),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun ExportChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color(0xFFF3F5F9),
            labelColor = Color(0xFF141821),
            selectedContainerColor = Color(0xFF3F7DF6),
            selectedLabelColor = Color.White
        )
    )
}

@Composable
private fun SectionTitle(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFF3F7DF6))
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = text,
            color = Color(0xFF141821),
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun WeekSelectionCard(
    selectedWeek: Int,
    currentWeekNumber: Int,
    maxAcademicWeek: Int,
    weekMonday: java.time.LocalDate,
    onSelectWeek: (Int) -> Unit
) {
    SectionCard {
        SectionTitle("周次")
        Spacer(Modifier.height(6.dp))
        Text(
            text = "第 $selectedWeek 周 · " +
                "${weekMonday.format(exportDateFormatter)} – " +
                "${weekMonday.plusDays(6).format(exportDateFormatter)}",
            color = Color(0xFF566072),
            fontSize = 13.sp
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(end = 4.dp)
        ) {
            item {
                ExportChip(
                    selected = selectedWeek == currentWeekNumber,
                    onClick = { onSelectWeek(currentWeekNumber) },
                    label = "本周"
                )
            }
            items((1..maxAcademicWeek).toList()) { number ->
                ExportChip(
                    selected = selectedWeek == number,
                    onClick = { onSelectWeek(number) },
                    label = "$number"
                )
            }
        }
    }
}

@Composable
private fun BackgroundChoiceCard(
    useCurrentBackground: Boolean,
    onUseCurrentBackgroundChange: (Boolean) -> Unit,
    hasCustomBackground: Boolean,
    solidBackground: ExportSolidBackground,
    onSolidBackgroundChange: (ExportSolidBackground) -> Unit
) {
    SectionCard {
        SectionTitle("背景")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExportChip(
                selected = useCurrentBackground,
                onClick = { onUseCurrentBackgroundChange(true) },
                label = "当前背景"
            )
            ExportChip(
                selected = !useCurrentBackground,
                onClick = { onUseCurrentBackgroundChange(false) },
                label = "简洁纯色"
            )
        }
        if (!useCurrentBackground) {
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(ExportSolidBackground.entries.toList()) { option ->
                    val selected = option == solidBackground
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color(option.topColor), Color(option.bottomColor))
                                    )
                                )
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) Color(0xFF3F7DF6) else Color(0x33000000),
                                    shape = CircleShape
                                )
                                .clickable { onSolidBackgroundChange(option) }
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = option.label,
                            color = if (selected) Color(0xFF3F7DF6) else Color(0xFF566072),
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
        if (hasCustomBackground) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "自定义背景可能包含私人照片，默认不导出",
                color = Color(0xFF8A6D3B),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun ContentTogglesCard(
    showTeacher: Boolean,
    onShowTeacherChange: (Boolean) -> Unit,
    showRoom: Boolean,
    onShowRoomChange: (Boolean) -> Unit
) {
    SectionCard {
        SectionTitle("内容")
        Spacer(Modifier.height(4.dp))
        SwitchRow("显示教师", showTeacher, onShowTeacherChange)
        SwitchRow("显示教室", showRoom, onShowRoomChange)
    }
}

@Composable
private fun ExportActionButtons(
    saveLabel: String,
    shareLabel: String,
    isBusy: Boolean,
    enabled: Boolean = true,
    onSave: () -> Unit,
    onShare: () -> Unit
) {
    Button(
        onClick = onSave,
        enabled = enabled && !isBusy,
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF3F7DF6),
            contentColor = Color.White,
            disabledContainerColor = Color(0xFFB8C6E6),
            disabledContentColor = Color.White
        )
    ) {
        Icon(
            Icons.Outlined.SaveAlt,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (isBusy) "正在生成…" else saveLabel,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp
        )
    }
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = onShare,
        enabled = enabled && !isBusy,
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFFE8F0FE),
            contentColor = Color(0xFF1B5FE0),
            disabledContainerColor = Color(0xFFEFF2F7),
            disabledContentColor = Color(0xFF9AA3B2)
        )
    ) {
        Icon(
            Icons.Outlined.IosShare,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = shareLabel,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp
        )
    }
}

@Composable
private fun ExportActionBar(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        color = Color(0xFFF6F4EF),
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            content = content
        )
    }
}

@Composable
private fun SectionCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        tonalElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(14.dp), content = content)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color(0xFF141821),
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ExportEmptyState(onImportClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("还没有课表", color = Color(0xFF141821), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("导入后就可以导出图片或日历文件", color = Color(0xFF566072), fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onImportClick) { Text("去导入") }
        }
    }
}
