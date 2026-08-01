package com.glut.schedule.ui.components

import com.glut.schedule.data.model.NormalizedCropRect
import kotlin.math.max
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** 与 EXIF Orientation 的八种视觉方向一一对应。 */
enum class BackgroundImageOrientation(val swapsAxes: Boolean) {
    NORMAL(false),
    FLIP_HORIZONTAL(false),
    ROTATE_180(false),
    FLIP_VERTICAL(false),
    TRANSPOSE(true),
    ROTATE_90(true),
    TRANSVERSE(true),
    ROTATE_270(true)
}

data class LegacyRegionDecodePlan(
    val rawRegion: ImageCropRegion,
    val orientedCropWidth: Int,
    val orientedCropHeight: Int,
    val sampleSize: Int
)

/**
 * 裁剪器保存的是“用户看到的正向图片”坐标；旧版区域解码需要反算回文件中的原始坐标。
 */
fun mapOrientedCropToRaw(
    crop: NormalizedCropRect,
    orientation: BackgroundImageOrientation
): NormalizedCropRect {
    val safeCrop = crop.sanitized() ?: NormalizedCropRect(0f, 0f, 1f, 1f)
    val corners = listOf(
        safeCrop.left to safeCrop.top,
        safeCrop.right to safeCrop.top,
        safeCrop.left to safeCrop.bottom,
        safeCrop.right to safeCrop.bottom
    ).map { (visualX, visualY) ->
        when (orientation) {
            BackgroundImageOrientation.NORMAL -> visualX to visualY
            BackgroundImageOrientation.FLIP_HORIZONTAL -> (1f - visualX) to visualY
            BackgroundImageOrientation.ROTATE_180 -> (1f - visualX) to (1f - visualY)
            BackgroundImageOrientation.FLIP_VERTICAL -> visualX to (1f - visualY)
            BackgroundImageOrientation.TRANSPOSE -> visualY to visualX
            BackgroundImageOrientation.ROTATE_90 -> visualY to (1f - visualX)
            BackgroundImageOrientation.TRANSVERSE -> (1f - visualY) to (1f - visualX)
            BackgroundImageOrientation.ROTATE_270 -> (1f - visualY) to visualX
        }
    }
    return NormalizedCropRect(
        left = corners.minOf { it.first }.coerceIn(0f, 1f),
        top = corners.minOf { it.second }.coerceIn(0f, 1f),
        right = corners.maxOf { it.first }.coerceIn(0f, 1f),
        bottom = corners.maxOf { it.second }.coerceIn(0f, 1f)
    )
}

/** API 26–27 必须按最终裁剪区域计算采样率，不能先按整张全景图降采样。 */
fun calculateLegacyRegionDecodePlan(
    rawWidth: Int,
    rawHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
    crop: NormalizedCropRect?,
    orientation: BackgroundImageOrientation
): LegacyRegionDecodePlan {
    val safeRawWidth = max(1, rawWidth)
    val safeRawHeight = max(1, rawHeight)
    val orientedWidth = if (orientation.swapsAxes) safeRawHeight else safeRawWidth
    val orientedHeight = if (orientation.swapsAxes) safeRawWidth else safeRawHeight
    val visualCrop = crop?.sanitized() ?: calculateNormalizedCenterCrop(
        orientedWidth,
        orientedHeight,
        max(1, targetWidth),
        max(1, targetHeight)
    )
    val rawCrop = mapOrientedCropToRaw(visualCrop, orientation)
    val left = floor(rawCrop.left * safeRawWidth).toInt().coerceIn(0, safeRawWidth - 1)
    val top = floor(rawCrop.top * safeRawHeight).toInt().coerceIn(0, safeRawHeight - 1)
    val right = ceil(rawCrop.right * safeRawWidth).toInt().coerceIn(left + 1, safeRawWidth)
    val bottom = ceil(rawCrop.bottom * safeRawHeight).toInt().coerceIn(top + 1, safeRawHeight)
    val rawRegion = ImageCropRegion(left, top, right, bottom)
    val cropWidth = if (orientation.swapsAxes) rawRegion.height else rawRegion.width
    val cropHeight = if (orientation.swapsAxes) rawRegion.width else rawRegion.height
    return LegacyRegionDecodePlan(
        rawRegion = rawRegion,
        orientedCropWidth = cropWidth,
        orientedCropHeight = cropHeight,
        sampleSize = calculateBitmapSampleSize(
            cropWidth,
            cropHeight,
            max(1, targetWidth),
            max(1, targetHeight)
        )
    )
}

data class BackgroundCropTransform(
    val zoom: Float,
    val offsetX: Float,
    val offsetY: Float
)

/** 根据原图和目标屏幕比例生成与旧版本一致的居中裁剪区域。 */
fun calculateNormalizedCenterCrop(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int
): NormalizedCropRect {
    val sourceW = max(1, sourceWidth)
    val sourceH = max(1, sourceHeight)
    val region = calculateCenterCropRegion(sourceW, sourceH, max(1, targetWidth), max(1, targetHeight))
    return NormalizedCropRect(
        left = region.left.toFloat() / sourceW,
        top = region.top.toFloat() / sourceH,
        right = region.right.toFloat() / sourceW,
        bottom = region.bottom.toFloat() / sourceH
    )
}

/** 把已保存的裁剪区域还原为 Compose 预览所需的缩放与平移量。 */
fun calculateCropTransform(
    crop: NormalizedCropRect?,
    imageWidth: Float,
    imageHeight: Float,
    viewportWidth: Float,
    viewportHeight: Float
): BackgroundCropTransform {
    if (imageWidth <= 0f || imageHeight <= 0f || viewportWidth <= 0f || viewportHeight <= 0f) {
        return BackgroundCropTransform(1f, 0f, 0f)
    }
    val baseScale = max(viewportWidth / imageWidth, viewportHeight / imageHeight)
    val safeCrop = crop?.sanitized()
        ?: calculateNormalizedCenterCrop(
            imageWidth.roundToInt(),
            imageHeight.roundToInt(),
            viewportWidth.roundToInt(),
            viewportHeight.roundToInt()
        )
    val cropWidth = safeCrop.width * imageWidth
    val cropHeight = safeCrop.height * imageHeight
    val requestedScale = max(viewportWidth / cropWidth, viewportHeight / cropHeight)
    val zoom = (requestedScale / baseScale).coerceIn(1f, 5f)
    val scale = baseScale * zoom
    val displayedWidth = imageWidth * scale
    val displayedHeight = imageHeight * scale
    val offsetX = (displayedWidth - viewportWidth) / 2f - safeCrop.left * imageWidth * scale
    val offsetY = (displayedHeight - viewportHeight) / 2f - safeCrop.top * imageHeight * scale
    return clampCropTransform(
        imageWidth,
        imageHeight,
        viewportWidth,
        viewportHeight,
        zoom,
        offsetX,
        offsetY
    )
}

/** 限制手势变换，确保固定裁剪框内始终由图片完全覆盖。 */
fun clampCropTransform(
    imageWidth: Float,
    imageHeight: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    zoom: Float,
    offsetX: Float,
    offsetY: Float
): BackgroundCropTransform {
    if (imageWidth <= 0f || imageHeight <= 0f || viewportWidth <= 0f || viewportHeight <= 0f) {
        return BackgroundCropTransform(1f, 0f, 0f)
    }
    val safeZoom = zoom.coerceIn(1f, 5f)
    val baseScale = max(viewportWidth / imageWidth, viewportHeight / imageHeight)
    val displayedWidth = imageWidth * baseScale * safeZoom
    val displayedHeight = imageHeight * baseScale * safeZoom
    val maxOffsetX = max(0f, (displayedWidth - viewportWidth) / 2f)
    val maxOffsetY = max(0f, (displayedHeight - viewportHeight) / 2f)
    return BackgroundCropTransform(
        zoom = safeZoom,
        offsetX = offsetX.coerceIn(-maxOffsetX, maxOffsetX),
        offsetY = offsetY.coerceIn(-maxOffsetY, maxOffsetY)
    )
}

/** 将当前预览变换反算为方向修正后原图上的归一化裁剪区域。 */
fun cropRectFromTransform(
    imageWidth: Float,
    imageHeight: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    zoom: Float,
    offsetX: Float,
    offsetY: Float
): NormalizedCropRect {
    val transform = clampCropTransform(
        imageWidth,
        imageHeight,
        viewportWidth,
        viewportHeight,
        zoom,
        offsetX,
        offsetY
    )
    val baseScale = max(viewportWidth / imageWidth, viewportHeight / imageHeight)
    val scale = baseScale * transform.zoom
    val displayedWidth = imageWidth * scale
    val displayedHeight = imageHeight * scale
    val left = ((displayedWidth - viewportWidth) / 2f - transform.offsetX) / scale / imageWidth
    val top = ((displayedHeight - viewportHeight) / 2f - transform.offsetY) / scale / imageHeight
    return NormalizedCropRect(
        left = left.coerceIn(0f, 1f),
        top = top.coerceIn(0f, 1f),
        right = (left + viewportWidth / scale / imageWidth).coerceIn(0f, 1f),
        bottom = (top + viewportHeight / scale / imageHeight).coerceIn(0f, 1f)
    )
}

fun backgroundCacheKey(
    uri: String,
    crop: NormalizedCropRect?,
    targetWidth: Int,
    targetHeight: Int
): String {
    val safeCrop = crop?.sanitized()
    val cropPart = if (safeCrop == null) {
        "center"
    } else {
        listOf(safeCrop.left, safeCrop.top, safeCrop.right, safeCrop.bottom)
            .joinToString(",") { value -> value.toRawBits().toString(16) }
    }
    return "$uri|$cropPart|${max(1, targetWidth)}x${max(1, targetHeight)}"
}
