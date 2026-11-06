package com.glut.schedule.service.export

import kotlin.math.pow

/**
 * 「简洁纯色」背景的可选配色。
 *
 * 深色项配白字，浅色项（纯白）由使用方切换到深色文字——`isLight` 是唯一判据。
 * 每项是上下两端的渐变，色差很小，接近纯色但有层次。
 */
enum class ExportSolidBackground(
    val label: String,
    val topColor: Int,
    val bottomColor: Int
) {
    WHITE("纯白", 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt()),
    NIGHT_BLUE("夜蓝", 0xFF1A2740.toInt(), 0xFF071018.toInt()),
    INK("墨黑", 0xFF202226.toInt(), 0xFF0B0C0F.toInt()),
    SLATE("石板", 0xFF2B3543.toInt(), 0xFF111720.toInt()),
    TEAL("深青", 0xFF103A3A.toInt(), 0xFF061B1B.toInt()),
    FOREST("深绿", 0xFF153A2B.toInt(), 0xFF08170F.toInt()),
    PLUM("深紫", 0xFF2C1C40.toInt(), 0xFF120B1E.toInt()),
    WINE("酒红", 0xFF3B1A23.toInt(), 0xFF1B0B10.toInt()),
    GRAPHITE("石墨", 0xFF2A2A2E.toInt(), 0xFF101013.toInt());

    /** 浅色底需要把表头、节次和网格线切成深色。 */
    val isLight: Boolean get() = relativeLuminanceOf(topColor) > 0.55

    companion object {
        val DEFAULT: ExportSolidBackground = NIGHT_BLUE
    }
}

/** WCAG 相对亮度；用于判断背景是深色还是浅色。 */
fun relativeLuminanceOf(color: Int): Double {
    fun channel(raw: Int): Double {
        val value = (raw and 0xFF) / 255.0
        return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }
    val red = channel(color shr 16)
    val green = channel(color shr 8)
    val blue = channel(color)
    return 0.2126 * red + 0.7152 * green + 0.0722 * blue
}
