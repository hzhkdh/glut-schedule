package com.glut.schedule.service.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.glut.schedule.data.model.readableMarkerColor
import java.io.ByteArrayOutputStream
import java.io.Closeable

/**
 * 把一周课表直接画成 A4 纵向 PDF 页。
 *
 * 不再经过「截图 → 位图 → 贴图」：多页导出时反复截同一张 Compose 画面既脆弱又慢，
 * 直接绘制也能给出更适合打印的白底版式。
 */
private const val PAGE_WIDTH = 595 // 72 dpi
private const val PAGE_HEIGHT = 842
private const val PAGE_MARGIN = 24f
private const val GRID_TOP = 62f
private const val GRID_BOTTOM = PAGE_HEIGHT - 34f
private const val PERIOD_COLUMN_WIDTH = 34f
private const val DAY_HEADER_HEIGHT = 28f

class SchedulePdfWriter(
    private val style: PdfPageStyle = PdfPageStyle()
) : Closeable {
    private val document = PdfDocument()
    private var pageIndex = 0

    fun addPage(page: PdfWeekPage) {
        pageIndex++
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageIndex).create()
        val pdfPage = document.startPage(pageInfo)
        try {
            drawPage(pdfPage.canvas, page, style)
        } finally {
            document.finishPage(pdfPage)
        }
    }

    fun build(): ByteArray {
        val output = ByteArrayOutputStream()
        document.writeTo(output)
        return output.toByteArray()
    }

    override fun close() {
        document.close()
    }
}

private fun drawPage(canvas: Canvas, page: PdfWeekPage, style: PdfPageStyle) {
    drawBackground(canvas, style)

    val light = when (val background = style.background) {
        is PdfBackground.Solid -> relativeLuminanceOf(background.topColor) > 0.55
        is PdfBackground.Image -> false
    }
    val titleColor = if (light) Color.rgb(20, 24, 33) else Color.WHITE
    val subtitleColor = if (light) Color.rgb(90, 98, 112) else Color.argb(190, 255, 255, 255)
    val dayColor = if (light) Color.rgb(20, 24, 33) else Color.WHITE
    val dateColor = if (light) Color.argb(150, 20, 24, 33) else Color.argb(160, 255, 255, 255)
    val periodColor = if (light) Color.rgb(20, 24, 33) else Color.WHITE
    val timeColor = if (light) Color.argb(160, 20, 24, 33) else Color.argb(170, 255, 255, 255)
    val gridColor = if (light) Color.argb(50, 0, 0, 0) else Color.argb(60, 255, 255, 255)
    val footerColor = if (light) Color.argb(140, 90, 98, 112) else Color.argb(140, 255, 255, 255)
    val markerGreen = if (light) Color.rgb(45, 154, 114) else Color.rgb(90, 210, 160)

    val titlePaint = paint(size = 13f, color = titleColor, bold = true)
    val subtitlePaint = paint(size = 8f, color = subtitleColor)
    val titleLeft = PAGE_MARGIN + 10f
    // 标题左侧的强调条：给页眉一个视觉锚点，纯文字标题在打印稿上容易显得散。
    canvas.drawRoundRect(
        RectF(PAGE_MARGIN, 22f, PAGE_MARGIN + 3f, 40f),
        1.5f, 1.5f,
        paint(size = 1f, color = Color.rgb(63, 125, 246))
    )
    canvas.drawText(page.title, titleLeft, 32f, titlePaint)
    canvas.drawText(page.subtitle, titleLeft, 46f, subtitlePaint)

    val dayCount = page.days.size.coerceAtLeast(1)
    val rowCount = page.rows.size.coerceAtLeast(1)
    val gridLeft = PAGE_MARGIN
    val gridRight = PAGE_WIDTH - PAGE_MARGIN
    val dayWidth = (gridRight - gridLeft - PERIOD_COLUMN_WIDTH) / dayCount
    val rowHeight = (GRID_BOTTOM - GRID_TOP - DAY_HEADER_HEIGHT) / rowCount

    val dayPaint = paint(size = 9f, color = dayColor, bold = true)
    val datePaint = paint(size = 7f, color = dateColor)
    val periodPaint = paint(size = 7.5f, color = periodColor, bold = true)
    val timePaint = paint(size = 5.8f, color = timeColor)
    val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gridColor
        strokeWidth = 0.6f
        this.style = Paint.Style.STROKE
    }

    // 日期表头底带
    canvas.drawRect(
        gridLeft,
        GRID_TOP,
        gridRight,
        GRID_TOP + DAY_HEADER_HEIGHT,
        paint(size = 1f, color = if (light) Color.argb(20, 0, 0, 0) else Color.argb(22, 255, 255, 255))
    )

    // 隔行底色：白底与深底都只加一点点，帮助横向读行
    val zebraPaint = paint(
        size = 1f,
        color = if (light) Color.argb(12, 0, 0, 0) else Color.argb(14, 255, 255, 255)
    )
    page.rows.forEachIndexed { index, _ ->
        if (index % 2 == 1) {
            val top = GRID_TOP + DAY_HEADER_HEIGHT + index * rowHeight
            canvas.drawRect(gridLeft, top, gridRight, top + rowHeight, zebraPaint)
        }
    }

    // 日期表头
    page.days.forEachIndexed { index, day ->
        val left = gridLeft + PERIOD_COLUMN_WIDTH + index * dayWidth
        drawCentered(canvas, day.weekday, left + dayWidth / 2, GRID_TOP + 11f, dayPaint)
        day.dateText?.let { text ->
            drawCentered(canvas, text, left + dayWidth / 2, GRID_TOP + 21f, datePaint)
        }
        day.marker?.let { text ->
            val color = if (text == "调") Color.rgb(229, 116, 17) else markerGreen
            canvas.drawText(
                text,
                left + dayWidth - 8f,
                GRID_TOP + 10f,
                paint(size = 6.5f, color = color, bold = true, italic = true)
            )
        }
    }

    // 节次列
    page.rows.forEachIndexed { index, row ->
        val top = GRID_TOP + DAY_HEADER_HEIGHT + index * rowHeight
        drawCentered(canvas, row.label, gridLeft + PERIOD_COLUMN_WIDTH / 2, top + rowHeight / 2 - 1f, periodPaint)
        if (row.timeText.isNotBlank()) {
            drawCentered(canvas, row.timeText, gridLeft + PERIOD_COLUMN_WIDTH / 2, top + rowHeight / 2 + 7f, timePaint)
        }
    }

    // 网格
    for (index in 0..rowCount) {
        val y = GRID_TOP + DAY_HEADER_HEIGHT + index * rowHeight
        canvas.drawLine(gridLeft, y, gridRight, y, linePaint)
    }
    for (index in 0..dayCount) {
        val x = gridLeft + PERIOD_COLUMN_WIDTH + index * dayWidth
        canvas.drawLine(x, GRID_TOP, x, GRID_BOTTOM, linePaint)
    }
    canvas.drawLine(gridLeft, GRID_TOP + DAY_HEADER_HEIGHT, gridRight, GRID_TOP + DAY_HEADER_HEIGHT, linePaint)
    canvas.drawLine(gridLeft, GRID_TOP, gridLeft, GRID_BOTTOM, linePaint)

    // 课程卡片
    page.slots.forEach { slot ->
        drawSlot(canvas, slot, gridLeft, gridTop = GRID_TOP + DAY_HEADER_HEIGHT, dayWidth, rowHeight)
    }

    val footerPaint = paint(size = 7f, color = footerColor)
    canvas.drawLine(
        PAGE_MARGIN,
        PAGE_HEIGHT - 28f,
        PAGE_WIDTH - PAGE_MARGIN,
        PAGE_HEIGHT - 28f,
        paint(size = 1f, color = gridColor).apply { strokeWidth = 0.5f }
    )
    canvas.drawText(page.footer, PAGE_MARGIN, PAGE_HEIGHT - 16f, footerPaint)
    val pageText = "第 ${page.pageNumber} / ${page.pageCount} 页"
    canvas.drawText(
        pageText,
        PAGE_WIDTH - PAGE_MARGIN - footerPaint.measureText(pageText),
        PAGE_HEIGHT - 16f,
        footerPaint
    )
}

private fun drawBackground(canvas: Canvas, style: PdfPageStyle) {
    when (val background = style.background) {
        is PdfBackground.Solid -> {
            if (background.topColor == background.bottomColor) {
                canvas.drawColor(background.topColor)
            } else {
                val shader = LinearGradient(
                    0f, 0f, 0f, PAGE_HEIGHT.toFloat(),
                    intArrayOf(background.topColor, background.bottomColor),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(
                    0f, 0f, PAGE_WIDTH.toFloat(), PAGE_HEIGHT.toFloat(),
                    paint(size = 1f).apply { this.shader = shader }
                )
            }
        }
        is PdfBackground.Image -> {
            val bitmap = background.bitmap
            if (bitmap.width > 0 && bitmap.height > 0) {
                val scale = maxOf(PAGE_WIDTH.toFloat() / bitmap.width, PAGE_HEIGHT.toFloat() / bitmap.height)
                val srcWidth = PAGE_WIDTH / scale
                val srcHeight = PAGE_HEIGHT / scale
                val srcLeft = (bitmap.width - srcWidth) / 2f
                val srcTop = (bitmap.height - srcHeight) / 2f
                val src = android.graphics.Rect(
                    srcLeft.toInt(),
                    srcTop.toInt(),
                    (srcLeft + srcWidth).toInt(),
                    (srcTop + srcHeight).toInt()
                )
                canvas.drawBitmap(
                    bitmap,
                    src,
                    RectF(0f, 0f, PAGE_WIDTH.toFloat(), PAGE_HEIGHT.toFloat()),
                    paint(size = 1f)
                )
            }
            val dimAlpha = (style.dimAmount.coerceIn(0f, 0.8f) * 255).toInt()
            if (dimAlpha > 0) {
                canvas.drawRect(
                    0f, 0f, PAGE_WIDTH.toFloat(), PAGE_HEIGHT.toFloat(),
                    paint(size = 1f, color = Color.argb(dimAlpha, 0, 0, 0))
                )
            }
        }
    }
}

private fun drawSlot(
    canvas: Canvas,
    slot: PdfCourseSlot,
    gridLeft: Float,
    gridTop: Float,
    dayWidth: Float,
    rowHeight: Float
) {
    val left = gridLeft + PERIOD_COLUMN_WIDTH + slot.columnIndex * dayWidth + 1.5f
    val top = gridTop + slot.rowIndex * rowHeight + 1.5f
    val right = left + dayWidth - 3f
    val bottom = top + rowHeight * slot.rowSpan.coerceAtLeast(1) - 3f
    if (right <= left || bottom <= top) return

    val background = runCatching { Color.parseColor(slot.colorHex) }.getOrDefault(Color.rgb(63, 125, 246))
    val foreground = runCatching { Color.parseColor(readableMarkerColor(slot.colorHex)) }
        .getOrDefault(Color.WHITE)
    canvas.drawRoundRect(RectF(left, top, right, bottom), 3f, 3f, paint(size = 1f, color = background).apply {
        style = Paint.Style.FILL
    })

    val textLeft = left + 3f
    val textWidth = (right - left) - 6f
    if (textWidth <= 4f) return
    val lineHeight = 8.2f
    val titlePaint = paint(size = 7.5f, color = foreground, bold = true)
    var cursorY = top + 8.5f
    val maxTitleLines = if (bottom - top >= 34f) 3 else 2
    cursorY = drawWrapped(canvas, slot.title, titlePaint, textLeft, cursorY, textWidth, maxTitleLines, lineHeight)

    val metaPaint = paint(size = 6.2f, color = foreground)
    if (slot.room.isNotBlank() && cursorY + 7f < bottom - 7f) {
        cursorY = drawWrapped(canvas, "@${slot.room}", metaPaint, textLeft, cursorY, textWidth, 1, 7.2f)
    }
    if (slot.teacher.isNotBlank() && cursorY + 7f < bottom - 7f) {
        drawWrapped(canvas, slot.teacher, metaPaint, textLeft, cursorY, textWidth, 1, 7.2f)
    }

    slot.marker?.let { marker ->
        val markerPaint = paint(size = 6.5f, color = foreground, bold = true, italic = true)
        canvas.drawText(marker, left + 3f, bottom - 3f, markerPaint)
    }

    if (slot.conflictCount > 1) {
        val cx = right - 6f
        val cy = bottom - 6f
        canvas.drawCircle(cx, cy, 4.5f, paint(size = 1f, color = Color.rgb(225, 29, 72)))
        drawCentered(canvas, slot.conflictCount.toString(), cx, cy + 2.2f, paint(size = 5.5f, color = Color.WHITE, bold = true))
    }
}

/** 按宽度折行，返回下一行的基线 y。 */
private fun drawWrapped(
    canvas: Canvas,
    text: String,
    paint: Paint,
    x: Float,
    baselineY: Float,
    maxWidth: Float,
    maxLines: Int,
    lineHeight: Float
): Float {
    if (text.isBlank() || maxWidth <= 1f) return baselineY
    var remaining = text.trim()
    var y = baselineY
    var line = 0
    while (remaining.isNotEmpty() && line < maxLines) {
        val count = paint.breakText(remaining, true, maxWidth, null).coerceAtLeast(1)
        var chunk = remaining.substring(0, count)
        if (count < remaining.length) {
            // 优先在空格处断开，避免把英文单词劈开。
            val space = chunk.lastIndexOf(' ')
            if (space > 0) chunk = chunk.substring(0, space)
        }
        canvas.drawText(chunk.trimEnd(), x, y, paint)
        remaining = remaining.substring(chunk.length).trimStart()
        y += lineHeight
        line++
    }
    return y
}

private fun drawCentered(canvas: Canvas, text: String, centerX: Float, baselineY: Float, paint: Paint) {
    canvas.drawText(text, centerX - paint.measureText(text) / 2f, baselineY, paint)
}

private fun paint(
    size: Float,
    color: Int = Color.BLACK,
    bold: Boolean = false,
    italic: Boolean = false
): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = size
    this.color = color
    style = Paint.Style.FILL
    typeface = Typeface.create(Typeface.DEFAULT, when {
        bold && italic -> Typeface.BOLD_ITALIC
        bold -> Typeface.BOLD
        italic -> Typeface.ITALIC
        else -> Typeface.NORMAL
    })
}
