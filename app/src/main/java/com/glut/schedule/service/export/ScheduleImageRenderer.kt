package com.glut.schedule.service.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.glut.schedule.data.model.readableMarkerColor

/**
 * 课表图片渲染器。
 *
 * 与 PDF 共用同一份 `PdfWeekPage`（行、列、卡片位置都已算好），只是换成手机竖屏比例：
 * 直接画到 Bitmap，不依赖 GraphicsLayer 截图——预览在屏幕外时截图会得到空白。
 *
 * 尺寸按 360dp 宽、74dp 行高在设计稿上等比放大到目标宽度。
 */
fun renderScheduleWeekImage(
    page: PdfWeekPage,
    background: PdfBackground,
    dimAmount: Float,
    widthPx: Int = 1080
): Bitmap {
    val scale = widthPx / 360f
    val headerHeight = 60f * scale
    val periodColumnWidth = 52f * scale
    val rowHeight = 74f * scale
    val footerHeight = 30f * scale
    val dayCount = page.days.size.coerceAtLeast(1)
    val rowCount = page.rows.size.coerceAtLeast(1)
    val heightPx = (headerHeight + rowHeight * rowCount + footerHeight).toInt()

    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawImageBackground(canvas, background, dimAmount, widthPx, heightPx)

    val light = when (background) {
        is PdfBackground.Solid -> relativeLuminanceOf(background.topColor) > 0.55
        is PdfBackground.Image -> false
    }
    val primary = if (light) Color.rgb(20, 24, 33) else Color.WHITE
    val muted = if (light) Color.argb(120, 20, 24, 33) else Color.argb(120, 255, 255, 255)
    val lineColor = if (light) Color.argb(28, 0, 0, 0) else Color.argb(26, 255, 255, 255)

    val dayWidth = (widthPx - periodColumnWidth) / dayCount
    val gridTop = headerHeight

    // 日期表头
    val monthPaint = paint(12f * scale, if (light) Color.argb(200, 20, 24, 33) else Color.argb(230, 255, 255, 255), bold = true)
    canvas.drawText(
        page.monthText,
        periodColumnWidth / 2f - monthPaint.measureText(page.monthText) / 2f,
        gridTop / 2f + 4f * scale,
        monthPaint
    )
    val dayPaint = paint(14f * scale, primary, bold = true)
    val datePaint = paint(12f * scale, muted)
    page.days.forEachIndexed { index, day ->
        val centerX = periodColumnWidth + dayWidth * (index + 0.5f)
        drawCentered(canvas, day.weekday, centerX, gridTop / 2f - 2f * scale, dayPaint)
        day.dateText?.let { date ->
            drawCentered(canvas, date, centerX, gridTop / 2f + 16f * scale, datePaint)
        }
        day.marker?.let { marker ->
            val markerColor = if (marker == "调") Color.rgb(229, 116, 17) else Color.rgb(45, 154, 114)
            canvas.drawText(
                marker,
                periodColumnWidth + dayWidth * (index + 1) - 12f * scale,
                gridTop / 2f - 8f * scale,
                paint(10f * scale, markerColor, bold = true, italic = true)
            )
        }
    }

    // 节次列
    val periodPaint = paint(14f * scale, primary, bold = true)
    val timePaint = paint(9f * scale, muted)
    page.rows.forEachIndexed { index, row ->
        val top = gridTop + rowHeight * index
        canvas.drawText(
            row.label,
            6f * scale,
            top + 22f * scale,
            periodPaint
        )
        if (row.timeText.isNotBlank()) {
            // 起止时间各占一行，窄时间列也不会挤进课程区域。
            row.timeText.lines().forEachIndexed { line, time ->
                canvas.drawText(time, 6f * scale, top + (36f + line * 12f) * scale, timePaint)
            }
        }
    }

    // 网格线
    val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor
        strokeWidth = 0.7f * scale
        style = Paint.Style.STROKE
    }
    for (index in 0..rowCount) {
        val y = gridTop + rowHeight * index
        canvas.drawLine(0f, y, widthPx.toFloat(), y, linePaint)
    }
    for (index in 0..dayCount) {
        val x = periodColumnWidth + dayWidth * index
        canvas.drawLine(x, 0f, x, gridTop + rowHeight * rowCount, linePaint)
    }

    // 课程卡片
    page.slots.forEach { slot ->
        drawImageSlot(canvas, slot, scale, periodColumnWidth, dayWidth, rowHeight, gridTop)
    }

    // 页脚
    val footer = paint(10f * scale, if (light) Color.rgb(86, 96, 114) else Color.argb(140, 255, 255, 255))
    drawCentered(
        canvas,
        pdfExportFooter(),
        widthPx / 2f,
        heightPx - footerHeight / 2f + 4f * scale,
        footer
    )
    return bitmap
}

private fun drawImageBackground(
    canvas: Canvas,
    background: PdfBackground,
    dimAmount: Float,
    width: Int,
    height: Int
) {
    when (background) {
        is PdfBackground.Solid -> {
            if (background.topColor == background.bottomColor) {
                canvas.drawColor(background.topColor)
            } else {
                val shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    intArrayOf(background.topColor, background.bottomColor),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(
                    0f, 0f, width.toFloat(), height.toFloat(),
                    paint(1f).apply { this.shader = shader }
                )
            }
        }
        is PdfBackground.Image -> {
            val source = background.bitmap
            if (source.width > 0 && source.height > 0) {
                val scale = maxOf(width.toFloat() / source.width, height.toFloat() / source.height)
                val srcWidth = width / scale
                val srcHeight = height / scale
                val left = (source.width - srcWidth) / 2f
                val top = (source.height - srcHeight) / 2f
                canvas.drawBitmap(
                    source,
                    Rect(
                        left.toInt(),
                        top.toInt(),
                        (left + srcWidth).toInt(),
                        (top + srcHeight).toInt()
                    ),
                    RectF(0f, 0f, width.toFloat(), height.toFloat()),
                    paint(1f)
                )
            }
            val alpha = (dimAmount.coerceIn(0f, 0.8f) * 255).toInt()
            if (alpha > 0) {
                canvas.drawRect(
                    0f, 0f, width.toFloat(), height.toFloat(),
                    paint(1f, Color.argb(alpha, 0, 0, 0))
                )
            }
        }
    }
}

private fun drawImageSlot(
    canvas: Canvas,
    slot: PdfCourseSlot,
    scale: Float,
    periodColumnWidth: Float,
    dayWidth: Float,
    rowHeight: Float,
    gridTop: Float
) {
    val inset = 2f * scale
    val left = periodColumnWidth + slot.columnIndex * dayWidth + inset
    val top = gridTop + slot.rowIndex * rowHeight + inset
    val right = left + dayWidth - inset * 2
    val bottom = top + rowHeight * slot.rowSpan.coerceAtLeast(1) - inset * 2
    if (right <= left || bottom <= top) return

    val background = runCatching { Color.parseColor(slot.colorHex) }.getOrDefault(Color.rgb(63, 125, 246))
    val foreground = runCatching { Color.parseColor(readableMarkerColor(slot.colorHex)) }
        .getOrDefault(Color.WHITE)
    val corner = 11f * scale
    canvas.drawRoundRect(RectF(left, top, right, bottom), corner, corner, paint(1f, background))

    val padding = 4f * scale
    val textLeft = left + padding
    val textWidth = (right - left) - padding * 2
    if (textWidth <= 1f) return

    val titlePaint = paint(11f * scale, foreground, bold = true)
    val metaPaint = paint(10f * scale, foreground)
    var baseline = top + padding + 11f * scale
    val maxTitleLines = when {
        slot.rowSpan >= 3 -> 4
        slot.rowSpan == 2 -> 3
        else -> 2
    }
    baseline = drawWrapped(
        canvas,
        slot.title,
        titlePaint,
        textLeft,
        baseline,
        textWidth,
        maxTitleLines,
        12f * scale
    )
    if (slot.room.isNotBlank() && baseline + 10f * scale < bottom - padding) {
        baseline = drawWrapped(
            canvas,
            "@${slot.room}",
            metaPaint,
            textLeft,
            baseline + 1f * scale,
            textWidth,
            1,
            11f * scale
        )
    }
    if (slot.teacher.isNotBlank() && baseline + 10f * scale < bottom - padding) {
        drawWrapped(
            canvas,
            slot.teacher,
            metaPaint,
            textLeft,
            baseline + 1f * scale,
            textWidth,
            1,
            11f * scale
        )
    }

    slot.marker?.let { marker ->
        canvas.drawText(
            marker,
            left + padding,
            bottom - padding,
            paint(10f * scale, foreground, bold = true, italic = true)
        )
    }

    if (slot.conflictCount > 1) {
        val cx = right - 9f * scale
        val cy = bottom - 9f * scale
        canvas.drawCircle(cx, cy, 9f * scale, paint(1f, Color.rgb(225, 29, 72)))
        drawCentered(
            canvas,
            slot.conflictCount.toString(),
            cx,
            cy + 4f * scale,
            paint(9f * scale, Color.WHITE, bold = true)
        )
    }
}

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
