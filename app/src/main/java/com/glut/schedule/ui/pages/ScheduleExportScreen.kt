package com.glut.schedule.ui.pages

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
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
import com.glut.schedule.data.settings.ScheduleSettingsStore
import com.glut.schedule.data.settings.capabilitiesFor
import com.glut.schedule.service.export.csvPreviewRows
import com.glut.schedule.service.export.calendarPreviewEvents
import com.glut.schedule.service.export.ExportSolidBackground
import com.glut.schedule.service.export.openSavedExport
import com.glut.schedule.service.export.openSavedExportLocation
import com.glut.schedule.service.export.PdfWeekPage
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
import com.glut.schedule.service.export.pdfExportFooter
import com.glut.schedule.service.export.ExportDisplayOptions
import com.glut.schedule.service.export.resolveExportDisplayOptions
import com.glut.schedule.service.export.initialExportWeek
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
    var savedReceipt by remember { mutableStateOf<ScheduleExportSaveResult.Saved?>(null) }
    var receiptActionError by remember { mutableStateOf("") }

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
    // 默认显示范围和历史首周必须检查完整学期，不能受用户隐藏课程的渲染设置影响。
    val fullBlocksForDefaults = remember(
        uiState.allCourses,
        uiState.maxAcademicWeek,
        uiState.semesterStartMonday,
        uiState.manualDayCopies
    ) {
        courseBlocksByWeek(
            courses = uiState.allCourses,
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
    val defaultWeek = initialExportWeek(
        uiState.isHistoricalSemester, uiState.currentWeekNumber, uiState.maxAcademicWeek, fullBlocksForDefaults
    )
    var selectedWeek by remember(uiState.viewedSemester?.id) {
        mutableIntStateOf(defaultWeek)
    }
    var weekManuallySelected by remember(uiState.viewedSemester?.id) { mutableStateOf(false) }
    LaunchedEffect(uiState.viewedSemester?.id, defaultWeek) {
        // 校历、当前周或历史课程异步到达时校正默认周；用户已经手选则保留选择。
        if (!weekManuallySelected) selectedWeek = defaultWeek
    }
    val exportSettings = remember(context) { ScheduleSettingsStore(context.applicationContext) }
    val savedDisplay by exportSettings.exportDisplayOptions.collectAsState(initial = ExportDisplayOptions())
    val exportDisplay = remember(savedDisplay, fullBlocksForDefaults, capabilities.hasNoonSections) {
        resolveExportDisplayOptions(savedDisplay, fullBlocksForDefaults, capabilities.hasNoonSections)
    }
    val exportShowNoon = exportDisplay.showNoon == true
    val exportShowWeekend = exportDisplay.showWeekend == true
    LaunchedEffect(exportSettings, fullBlocksForDefaults, capabilities.hasNoonSections) {
        exportSettings.initializeExportDisplayOptions(
            resolveExportDisplayOptions(ExportDisplayOptions(), fullBlocksForDefaults, capabilities.hasNoonSections)
        )
    }
    val hasCustomBackground = remember(uiState.customBackgroundUri) {
        shouldUseCustomBackground(uiState.customBackgroundUri)
    }
    // 自定义背景可能是私人照片：默认导出纯色底，用户主动打开才把背景烤进图片。
    var useCurrentBackground by remember(uiState.viewedSemester?.id, hasCustomBackground) {
        mutableStateOf(false)
    }
    var solidBackground by remember { mutableStateOf(ExportSolidBackground.WHITE) }
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
        exportShowNoon,
        exportShowWeekend,
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
            showNoon = exportShowNoon,
            hasNoonSections = capabilities.hasNoonSections,
            showWeekend = exportShowWeekend,
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
        uiState.hasAuthoritativeCalendar,
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
            includeTeacher = showTeacher,
            hasAuthoritativeCalendar = uiState.hasAuthoritativeCalendar
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
                    run { receiptActionError = ""; savedReceipt = result }
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
                    run { receiptActionError = ""; savedReceipt = result }
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
                        showNoon = exportShowNoon,
                        hasNoonSections = capabilities.hasNoonSections,
                        showWeekend = exportShowWeekend,
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
                        run { receiptActionError = ""; savedReceipt = result }
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

    savedReceipt?.let { receipt ->
        AlertDialog(
            onDismissRequest = { savedReceipt = null },
            containerColor = Color(0xFFFFFEFB),
            titleContentColor = Color(0xFF141821),
            textContentColor = Color(0xFF667085),
            title = { Text("文件已保存", fontSize = 19.sp, color = Color(0xFF141821)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(receipt.displayName, fontWeight = FontWeight.SemiBold, color = Color(0xFF141821))
                    Text("位置：" + receipt.location, color = Color(0xFF667085))
                    Text("大小：" + ((receipt.byteCount + 1023) / 1024) + " KB", color = Color(0xFF667085))
                    if (receiptActionError.isNotBlank()) Text(receiptActionError, color = Color(0xFFB3261E), fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF3268D7)), onClick = { scope.launch {
                            if (!openSavedExport(context, receipt)) receiptActionError = "没有可打开此格式的应用，请使用分享"
                        } }) { Text("打开文件") }
                        TextButton(colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF3268D7)), onClick = {
                            if (!openSavedExportLocation(context, receipt)) scope.launch {
                                receiptActionError = "无法打开文件管理器，请按保存位置查找"
                            }
                        }) { Text("查看位置") }
                    }
                }
            },
            confirmButton = { Button(colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3F7DF6), contentColor = Color.White), onClick = { savedReceipt = null }) { Text("完成", color = Color.White) } },
            dismissButton = { TextButton(colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF3268D7)), onClick = { scope.launch {
                if (!openSavedExport(context, receipt, share = true)) receiptActionError = "无法分享文件，请稍后重试"
            } }) { Text("分享") } }
        )
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
                        showNoon = exportShowNoon,
                        showWeekend = exportShowWeekend,
                        onShowNoonChange = { scope.launch { exportSettings.setExportShowNoon(it) } },
                        onShowWeekendChange = { scope.launch { exportSettings.setExportShowWeekend(it) } },
                        previewPage = imagePage,
                        uiState = uiState,
                        semesterSelection = semesterSelection,
                        capabilities = capabilities,
                        week = week,
                        selectedWeek = selectedWeek,
                        onSelectWeek = { weekManuallySelected = true; selectedWeek = it },
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
                        showNoon = exportShowNoon,
                        showWeekend = exportShowWeekend,
                        hasNoonSections = capabilities.hasNoonSections,
                        onShowNoonChange = { scope.launch { exportSettings.setExportShowNoon(it) } },
                        onShowWeekendChange = { scope.launch { exportSettings.setExportShowWeekend(it) } },
                        uiState = uiState,
                        previewPage = imagePage,
                        customBackgroundBitmap = customBackgroundBitmap,
                        semesterSelection = semesterSelection,
                        selectedWeek = selectedWeek,
                        wholeSemester = pdfWholeSemester,
                        onWholeSemesterChange = { pdfWholeSemester = it },
                        currentWeekNumber = uiState.currentWeekNumber,
                        maxAcademicWeek = uiState.maxAcademicWeek,
                        weekMonday = week.monday,
                        onSelectWeek = { weekManuallySelected = true; selectedWeek = it },
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
    showNoon: Boolean,
    showWeekend: Boolean,
    onShowNoonChange: (Boolean) -> Unit,
    onShowWeekendChange: (Boolean) -> Unit,
    previewPage: PdfWeekPage,
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
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            ExportContextCard(semesterSelection, selectedWeek, uiState.currentWeekNumber, uiState.maxAcademicWeek, onSelectWeek)
            ExportBitmapPreview(uiState, previewPage, useCurrentBackground, solidBackground, customBackgroundBitmap)
            CollapsibleExportSection("外观与内容", (if (useCurrentBackground) "当前背景" else solidBackground.label) + " · " + contentSummary(showTeacher, showRoom)) {
                BackgroundChoiceCard(useCurrentBackground, onUseCurrentBackgroundChange, hasCustomBackground, solidBackground, onSolidBackgroundChange)
                Spacer(Modifier.height(12.dp))
                ContentTogglesCard(showTeacher, onShowTeacherChange, showRoom, onShowRoomChange) {
                    ExportDisplayToggles(showNoon, showWeekend, capabilities.hasNoonSections, onShowNoonChange, onShowWeekendChange)
                }
            }
            CollapsibleExportSection("导出说明", "第 $selectedWeek 周 · 点击预览可放大") {
                Text("导出所选学期、第 $selectedWeek 周的完整图片，预览可上下滚动或点击放大。“内容”分组中的中午、周末开关仅用于图片和 PDF，与主课表、情侣课表的设置独立；关闭后不导出对应行或列。背景、教师和教室按所选内容导出。冲突课显示第一门，并标注数量。页脚注明实际导出日期。", color = Color(0xFF667085), fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
        }
        ExportActionBar { ExportActionButtons("保存图片", "分享", isBusy, onSave = onSave, onShare = onShare) }
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
                text = pdfExportFooter(),
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
    val success = result as? ScheduleIcsResult.Success
    val events = remember(success?.content) { calendarPreviewEvents(success?.content.orEmpty()) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            ExportContextCard(semesterSelection)
            SectionCard {
                Text("日历预览", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                events.forEach { event ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                        Text(event.title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(event.time, color = Color(0xFF667085), fontSize = 12.sp)
                        if (event.location.isNotBlank()) Text(event.location, color = Color(0xFF667085), fontSize = 11.sp)
                    }
                    HorizontalDivider(color = Color(0xFFE8EBF0))
                }
                Spacer(Modifier.height(8.dp))
                Text("$courseCount 门课 · " + (success?.eventCount ?: 0) + " 个事件", fontSize = 13.sp, color = Color(0xFF667085))
                if (success == null) Text(when(result) {
                    ScheduleIcsResult.MissingCalendar -> "缺少可靠校历，请先刷新或重新导入课表。"
                    ScheduleIcsResult.MissingClassTimes -> if (isGraduate) "研究生请先在设置中补全 12 节上课时间。" else "请先在设置中补全上课时间。"
                    else -> "当前学期没有可写入日历的课次。"
                }, fontSize = 12.sp, color = Color(0xFF925B20))
            }
            CollapsibleExportSection("显示内容", contentSummary(showTeacher, showRoom)) {
                ContentTogglesCard(showTeacher, onShowTeacherChange, showRoom, onShowRoomChange)
            }
            CollapsibleExportSection("导出说明", "整学期 · 导入系统日历使用") {
                Text("导出所选学期的 ICS 文件，包含中午和周末课程，不受图片、PDF 的显示开关影响；教师和教室按“显示内容”选择生成。停课课次不生成事件，调课、补课按实际日期生成。需要可靠校历和完整上课时间。保存后可打开文件交给日历应用导入；导入后不会自动随课表更新，提醒与重复导入由日历应用管理。", fontSize = 12.sp, color = Color(0xFF667085))
            }
        }
        ExportActionBar { ExportActionButtons("保存 ICS", "分享", isBusy, enabled = success != null, onSave = onSave, onShare = onShare) }
    }
}

@Composable
private fun PdfExportTab(
    showNoon: Boolean,
    showWeekend: Boolean,
    hasNoonSections: Boolean,
    onShowNoonChange: (Boolean) -> Unit,
    onShowWeekendChange: (Boolean) -> Unit,
    uiState: ScheduleUiState,
    previewPage: PdfWeekPage,
    customBackgroundBitmap: ImageBitmap?,
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
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            ExportContextCard(semesterSelection, if (wholeSemester) null else selectedWeek, currentWeekNumber, maxAcademicWeek, onSelectWeek)
            SectionCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ExportChip(!wholeSemester, { onWholeSemesterChange(false) }, "当前周")
                    ExportChip(wholeSemester, { onWholeSemesterChange(true) }, "整学期")
                    Text(if (wholeSemester) "$pageCount 页" else "单页", fontSize = 12.sp, color = Color(0xFF667085))
                }
            }
            ExportBitmapPreview(uiState, previewPage.copy(footer = pdfExportFooter()), useCurrentBackground, solidBackground, customBackgroundBitmap)
            CollapsibleExportSection("外观与内容", (if (useCurrentBackground) "当前背景" else solidBackground.label) + " · " + contentSummary(showTeacher, showRoom)) {
                BackgroundChoiceCard(useCurrentBackground, onUseCurrentBackgroundChange, hasCustomBackground, solidBackground, onSolidBackgroundChange)
                Spacer(Modifier.height(12.dp))
                ContentTogglesCard(showTeacher, onShowTeacherChange, showRoom, onShowRoomChange) {
                    ExportDisplayToggles(showNoon, showWeekend, hasNoonSections, onShowNoonChange, onShowWeekendChange)
                }
            }
            CollapsibleExportSection("导出说明", if (wholeSemester) "整学期 · 每周一页" else "第 $selectedWeek 周 · 单页") {
                Text("A4 纵向。上方预览为第 $selectedWeek 周；单周导出所选周，整学期按每周一页导出。“内容”分组中的中午、周末开关与图片共用，独立于主课表和情侣课表；关闭后不导出对应行或列。背景、教师和教室按所选内容导出。冲突课显示第一门并标注数量，页脚注明实际导出日期。", color = Color(0xFF667085), fontSize = 12.sp)
            }
        }
        ExportActionBar { ExportActionButtons("保存 PDF", "分享", isBusy, onSave = onSave, onShare = onShare) }
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
    val success = result as? ScheduleCsvResult.Success
    val rows = remember(success?.content) { csvPreviewRows(success?.content.orEmpty(), limit = 4) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            ExportContextCard(semesterSelection)
            SectionCard {
                Text("表格预览", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                // 课程列固定且较宽，其余列横向滚动；首屏仅展示三条课次。
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.width(132.dp)) {
                        rows.forEachIndexed { index, cells -> CsvPreviewCell(cells.firstOrNull().orEmpty(), index == 0, 132) }
                    }
                    Column(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                        rows.forEachIndexed { index, cells ->
                            Row { cells.drop(1).forEach { CsvPreviewCell(it, index == 0, 90) } }
                        }
                    }
                }
                Text("左右滑动查看完整列", color = Color(0xFF7A8291), fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(8.dp))
                Text("共 " + (success?.rowCount ?: 0) + " 行 · Excel / WPS 可打开", color = Color(0xFF667085), fontSize = 12.sp)
            }
            CollapsibleExportSection("显示内容", contentSummary(showTeacher, showRoom)) {
                ContentTogglesCard(showTeacher, onShowTeacherChange, showRoom, onShowRoomChange)
            }
            CollapsibleExportSection("导出说明", "整学期 · 按课次去重") {
                Text("导出所选学期的 CSV 表格，一行一条课次，去重并保留原始周次。包含中午和周末课程，不受图片、PDF 的显示开关影响；教师和教室按“显示内容”选择导出。使用 UTF-8（含 BOM），可用 Excel 或 WPS 打开；节次写为“第3-4节”，避免被识别成日期。", fontSize = 12.sp, color = Color(0xFF667085))
            }
        }
        ExportActionBar { ExportActionButtons("保存 CSV", "分享", isBusy, enabled = success != null, onSave = onSave, onShare = onShare) }
    }
}


/** 上下文集中在同一条工具栏，避免学期与周次分别占据一张大卡片。 */
@Composable
private fun ExportContextCard(
    state: SemesterSelectionState,
    selectedWeek: Int? = null,
    currentWeek: Int = 1,
    maxWeek: Int = 1,
    onSelectWeek: (Int) -> Unit = {}
) {
    var semesterMenu by remember { mutableStateOf(false) }
    var weekMenu by remember { mutableStateOf(false) }
    val semesterRotation by animateFloatAsState(if (semesterMenu) 180f else 0f, tween(180), label = "导出学期箭头")
    val weekRotation by animateFloatAsState(if (weekMenu) 180f else 0f, tween(180), label = "导出周次箭头")
    val semesterOptions = state.semesters.filter { it.isCurrent || it.cacheStatus == SemesterCacheStatus.CACHED }
    val semesterScroll = rememberScrollState()
    val weekScroll = rememberScrollState()
    val rowPixels = with(LocalDensity.current) { 48.dp.roundToPx() }
    // 打开时定位当前项，菜单只显示有限行数，不再从第一周开始铺满屏幕。
    LaunchedEffect(weekMenu, selectedWeek) {
        if (weekMenu) weekScroll.scrollTo(((selectedWeek ?: 1) - 3).coerceAtLeast(0) * rowPixels)
    }
    LaunchedEffect(semesterMenu, state.viewed?.id) {
        if (semesterMenu) semesterScroll.scrollTo((semesterOptions.indexOfFirst { it.id == state.viewed?.id } - 2).coerceAtLeast(0) * rowPixels)
    }
    SectionCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { semesterMenu = true }, verticalAlignment = Alignment.CenterVertically) {
                    Text(state.viewed?.displayName ?: "选择学期", fontSize = 13.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Icon(Icons.Outlined.KeyboardArrowDown, "切换学期", Modifier.size(18.dp).rotate(semesterRotation), tint = Color(0xFF3F7DF6))
                }
                DropdownMenu(expanded = semesterMenu, onDismissRequest = { semesterMenu = false },
                    modifier = Modifier.width(248.dp).heightIn(max = 320.dp), scrollState = semesterScroll,
                    containerColor = Color(0xFFFFFEFB)) {
                    semesterOptions.forEach { semester ->
                        val selected = semester.id == state.viewed?.id
                        DropdownMenuItem(text = { Text(semester.displayName, color = if (selected) Color(0xFF3268D7) else Color(0xFF141821), fontSize = 13.sp) },
                            trailingIcon = { if (selected) Icon(Icons.Outlined.Check, "已选择", tint = Color(0xFF3268D7), modifier = Modifier.size(18.dp)) },
                            modifier = Modifier.background(if (selected) Color(0xFFEDF3FF) else Color.Transparent).heightIn(min = 48.dp),
                            onClick = { semesterMenu = false; state.onSelect(semester.id) })
                    }
                    HorizontalDivider(color = Color(0xFFEDE8DE))
                    DropdownMenuItem(text = { Text("管理与下载其他学期", color = Color(0xFF3268D7), fontSize = 13.sp) }, onClick = { semesterMenu = false; state.onManage() })
                }
            }
            Spacer(Modifier.width(15.dp))
            Box {
                Row(Modifier.heightIn(min = 48.dp).clickable(enabled = selectedWeek != null) { weekMenu = true }, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (selectedWeek == null) "整学期" else "第 $selectedWeek 周", color = Color(0xFF3268D7), fontSize = 13.sp)
                    if (selectedWeek != null) Icon(Icons.Outlined.KeyboardArrowDown, "切换周次", Modifier.size(18.dp).rotate(weekRotation), tint = Color(0xFF3268D7))
                }
                DropdownMenu(expanded = weekMenu, onDismissRequest = { weekMenu = false },
                    modifier = Modifier.width(180.dp).heightIn(max = 320.dp), scrollState = weekScroll,
                    containerColor = Color(0xFFFFFEFB)) {
                    (1..maxWeek).forEach { week ->
                        val selected = week == selectedWeek
                        DropdownMenuItem(text = { Text("第 $week 周" + if (week == currentWeek) "（本周）" else "", color = if (selected) Color(0xFF3268D7) else Color(0xFF141821), fontSize = 13.sp) },
                            trailingIcon = { if (selected) Icon(Icons.Outlined.Check, "已选择", tint = Color(0xFF3268D7), modifier = Modifier.size(18.dp)) },
                            modifier = Modifier.background(if (selected) Color(0xFFEDF3FF) else Color.Transparent).heightIn(min = 48.dp),
                            onClick = { weekMenu = false; onSelectWeek(week) })
                    }
                }
            }
        }
        if (state.isHistorical) Text("历史学期只读快照", color = Color(0xFF8A6D3B), fontSize = 10.sp)
    }
}

private fun contentSummary(teacher: Boolean, room: Boolean): String = buildList {
    if (teacher) add("教师")
    if (room) add("教室")
}.joinToString(" / ").ifBlank { "不含教师、教室" }

/** 图片与 PDF 共享导出专属偏好，直排节次校区不展示中午开关。 */
@Composable
private fun ExportDisplayToggles(
    showNoon: Boolean,
    showWeekend: Boolean,
    hasNoonSections: Boolean,
    onShowNoonChange: (Boolean) -> Unit,
    onShowWeekendChange: (Boolean) -> Unit
) {
    // 显示范围归入“内容”，与教师、教室开关连续排列。
    if (hasNoonSections) SwitchRow("显示中午", showNoon, onShowNoonChange)
    SwitchRow("显示周末", showWeekend, onShowWeekendChange)
}

/** 降低首屏信息密度，选项仍然可发现并可直接编辑，按钮操作区域不少于 48dp。 */
@Composable
private fun CollapsibleExportSection(title: String, summary: String, content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(180), label = "导出折叠箭头")
    SectionCard {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(summary, fontSize = 11.sp, color = Color(0xFF667085), maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.2f))
            Icon(Icons.Outlined.KeyboardArrowDown, if (expanded) "收起$title" else "展开$title", Modifier.size(18.dp).rotate(rotation), tint = Color(0xFF667085))
        }
        // 内容高度和箭头同步过渡，让展开、收起都有连续反馈。
        AnimatedVisibility(expanded, enter = expandVertically(tween(180)) + fadeIn(tween(180)), exit = shrinkVertically(tween(180)) + fadeOut(tween(180))) {
            Column { Spacer(Modifier.height(10.dp)); content() }
        }
    }
}

/** 用实际导出渲染器生成可缩放预览，课程内容与保存文件共用同一份页面模型。 */
@Composable
private fun ExportBitmapPreview(
    uiState: ScheduleUiState,
    page: PdfWeekPage,
    useCurrentBackground: Boolean,
    solid: ExportSolidBackground,
    custom: ImageBitmap?
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var previewFailed by remember { mutableStateOf(false) }
    val bitmap by produceState<android.graphics.Bitmap?>(null, page, useCurrentBackground, solid, custom, uiState.backgroundDimAmount, uiState.customBackgroundUri) {
        previewFailed = false
        value = null
        value = withContext(Dispatchers.Default) {
            var owned: android.graphics.Bitmap? = null
            try {
                val background = when {
                    !useCurrentBackground -> PdfBackground.Solid(solid.topColor, solid.bottomColor)
                    custom != null -> PdfBackground.Image(custom.asAndroidBitmap())
                    !shouldUseCustomBackground(uiState.customBackgroundUri) -> {
                        owned = android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.builtin_flower_background)
                        owned?.let { PdfBackground.Image(it) } ?: PdfBackground.Solid(solid.topColor, solid.bottomColor)
                    }
                    else -> PdfBackground.Solid(solid.topColor, solid.bottomColor)
                }
                renderScheduleWeekImage(page, background, uiState.backgroundDimAmount)
            } catch (_: Exception) { null }
            finally { owned?.recycle() }
        }
        previewFailed = value == null
    }
    DisposableEffect(bitmap) { val current = bitmap; onDispose { current?.recycle() } }
    SectionCard {
        Box(Modifier.fillMaxWidth().height(238.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = bitmap != null) { expanded = true }, contentAlignment = Alignment.Center) {
            bitmap?.let { image ->
                // 按宽度显示上段局部，纵向滚动查看后续节次，避免整张长图缩成细条。
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Image(image.asImageBitmap(), "课表导出预览",
                        Modifier.fillMaxWidth().aspectRatio(image.width.toFloat() / image.height), contentScale = ContentScale.FillWidth)
                }
            }
                ?: Text(if (previewFailed) "预览暂不可用，可尝试重新选择背景" else "正在生成预览…", color = Color(0xFF667085), fontSize = 12.sp)
        }
        Text("可上下滚动 · 点击放大全图", color = Color(0xFF7A8291), fontSize = 10.sp, modifier = Modifier.align(Alignment.End))
    }
    if (expanded && bitmap != null) {
        Dialog({ expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(Color(0xFF17202D)).padding(16.dp)) {
                Image(bitmap!!.asImageBitmap(), "完整课表预览", Modifier.weight(1f).fillMaxWidth(), contentScale = ContentScale.Fit)
                TextButton(colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF3268D7)), onClick = { expanded = false }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("关闭预览", color = Color.White) }
            }
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
private fun BackgroundChoiceCard(
    useCurrentBackground: Boolean,
    onUseCurrentBackgroundChange: (Boolean) -> Unit,
    hasCustomBackground: Boolean,
    solidBackground: ExportSolidBackground,
    onSolidBackgroundChange: (ExportSolidBackground) -> Unit
) {
    Column {
        SectionTitle("背景")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExportChip(selected = !useCurrentBackground, onClick = {
                onSolidBackgroundChange(ExportSolidBackground.WHITE)
                onUseCurrentBackgroundChange(false)
            }, label = "纯白")
            ExportChip(selected = useCurrentBackground, onClick = { onUseCurrentBackgroundChange(true) }, label = "当前背景")
        }
        if (hasCustomBackground) Text("选择当前背景后，导出将包含你的自定义图片。", color = Color(0xFF667085), fontSize = 11.sp)
    }
}

@Composable
private fun ContentTogglesCard(
    showTeacher: Boolean,
    onShowTeacherChange: (Boolean) -> Unit,
    showRoom: Boolean,
    onShowRoomChange: (Boolean) -> Unit,
    displayOptions: @Composable () -> Unit = {}
) {
    Column {
        SectionTitle("内容")
        Spacer(Modifier.height(4.dp))
        // 图片和 PDF 注入显示范围；表格、日历只保留原有内容开关。
        displayOptions()
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onSave, enabled = enabled && !isBusy,
            modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3F7DF6), contentColor = Color.White)) {
            Icon(Icons.Outlined.SaveAlt, null, Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text(if (isBusy) "正在生成…" else saveLabel, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Button(onClick = onShare, enabled = enabled && !isBusy,
            modifier = Modifier.height(48.dp).width(76.dp), shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE8F0FE), contentColor = Color(0xFF1B5FE0))) {
            Icon(Icons.Outlined.IosShare, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("分享", fontSize = 12.sp)
        }
    }
}

@Composable
private fun ExportActionBar(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        color = Color(0xFFF6F4EF),
        shadowElevation = 0.dp,
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
        Column(modifier = Modifier.padding(12.dp), content = content)
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

/** 表头和内容使用固定行高，让固定课程列与横向滚动区域始终对齐。 */
@Composable
private fun CsvPreviewCell(value: String, header: Boolean, width: Int) {
    Column(Modifier.width(width.dp).height(if (header) 36.dp else 48.dp).background(if (header) Color(0xFFF0F3F8) else Color.Transparent)) {
        Text(value, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 7.dp, vertical = 7.dp),
            color = if (header) Color(0xFF566072) else Color(0xFF141821),
            fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal)
        HorizontalDivider(color = Color(0xFFE8EBF0))
    }
}
