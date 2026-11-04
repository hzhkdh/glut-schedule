package com.glut.schedule.service.export

import android.graphics.Bitmap

/** PDF 页面模型：只描述「画什么」，不依赖 Compose。由 UI 层按当前课表组装。 */

data class PdfDayHeader(
    val weekday: String,
    val dateText: String?,
    val marker: String?
)

data class PdfPeriodRow(
    val label: String,
    val timeText: String
)

data class PdfCourseSlot(
    val rowIndex: Int,
    val rowSpan: Int,
    val columnIndex: Int,
    val title: String,
    val room: String,
    val teacher: String,
    val colorHex: String,
    val marker: String?,
    val conflictCount: Int
)

data class PdfWeekPage(
    val title: String,
    val subtitle: String,
    val days: List<PdfDayHeader>,
    val rows: List<PdfPeriodRow>,
    val slots: List<PdfCourseSlot>,
    val footer: String,
    val monthText: String = "",
    val pageNumber: Int = 1,
    val pageCount: Int = 1
)

/** PDF 页背景：与课表页一致，支持当前背景图或深色纯色。 */
sealed interface PdfBackground {
    data class Solid(
        val topColor: Int = ExportSolidBackground.DEFAULT.topColor,
        val bottomColor: Int = ExportSolidBackground.DEFAULT.bottomColor
    ) : PdfBackground

    data class Image(val bitmap: Bitmap) : PdfBackground
}

data class PdfPageStyle(
    val background: PdfBackground = PdfBackground.Solid(),
    val dimAmount: Float = 0.35f
)
