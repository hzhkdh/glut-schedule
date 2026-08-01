package com.glut.schedule.data.model

import kotlin.math.roundToInt

const val DEFAULT_BACKGROUND_DIM_AMOUNT = 0.5f
const val MAX_BACKGROUND_DIM_AMOUNT = 0.8f
const val BACKGROUND_DIM_STEP = 0.05f

/**
 * 相对于方向修正后原图的裁剪区域。使用归一化坐标持久化，避免屏幕分辨率变化后失效。
 */
data class NormalizedCropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun sanitized(): NormalizedCropRect? {
        val safeLeft = left.coerceIn(0f, 1f)
        val safeTop = top.coerceIn(0f, 1f)
        val safeRight = right.coerceIn(0f, 1f)
        val safeBottom = bottom.coerceIn(0f, 1f)
        return if (safeRight - safeLeft > 0.0001f && safeBottom - safeTop > 0.0001f) {
            NormalizedCropRect(safeLeft, safeTop, safeRight, safeBottom)
        } else {
            null
        }
    }
}

data class ScheduleBackgroundPreferences(
    val uri: String = "",
    val crop: NormalizedCropRect? = null,
    val dimAmount: Float = DEFAULT_BACKGROUND_DIM_AMOUNT
)

fun snapBackgroundDimAmount(value: Float): Float {
    val clamped = value.coerceIn(0f, MAX_BACKGROUND_DIM_AMOUNT)
    return ((clamped / BACKGROUND_DIM_STEP).roundToInt() * BACKGROUND_DIM_STEP)
        .coerceIn(0f, MAX_BACKGROUND_DIM_AMOUNT)
}
