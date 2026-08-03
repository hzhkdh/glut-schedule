package com.glut.schedule.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.annotation.DrawableRes
import androidx.exifinterface.media.ExifInterface
import com.glut.schedule.data.model.DEFAULT_BACKGROUND_DIM_AMOUNT
import com.glut.schedule.data.model.NormalizedCropRect
import com.glut.schedule.R
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val RecomposeTag = "Recompose"

/** 应用内置背景使用固定标识保存，不会被当作外部 URI 读取。 */
enum class BuiltInScheduleBackground(
    val storageValue: String,
    @param:DrawableRes val drawableRes: Int,
    val displayName: String
) {
    FLOWER("builtin://flower", R.drawable.builtin_flower_background, "花")
    ;

    companion object {
        fun fromStorageValue(value: String): BuiltInScheduleBackground? {
            // 旧版以空值代表默认星空；星空下线后让同一空值自然迁移为默认《花》。
            if (value.isBlank()) return FLOWER
            return entries.firstOrNull { background ->
                background.storageValue.isNotBlank() && background.storageValue == value
            }
        }
    }
}

class ScheduleBackgroundStore(
    private val context: Context
) {
    private val cache = object : LruCache<String, ImageBitmap>(MAX_BACKGROUND_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            backgroundBitmapByteSize(value.width, value.height)
    }

    fun get(uri: String, crop: NormalizedCropRect?, targetWidth: Int, targetHeight: Int): ImageBitmap? =
        cache.get(backgroundCacheKey(uri, crop, targetWidth, targetHeight))

    suspend fun preload(
        uri: String,
        crop: NormalizedCropRect?,
        targetWidth: Int,
        targetHeight: Int
    ): Boolean = withContext(Dispatchers.IO) {
        if (!shouldUseCustomBackground(uri)) return@withContext true
        val cacheKey = backgroundCacheKey(uri, crop, targetWidth, targetHeight)
        if (cache.get(cacheKey) != null) {
            Log.d(RecomposeTag, "background cache hit")
            return@withContext true
        }

        Log.d(RecomposeTag, "background decode start")
        val decoded = runCatching {
            decodeSampledBitmap(uri, crop, targetWidth, targetHeight)?.asImageBitmap()
        }.getOrNull()
        if (decoded != null) {
            cache.put(cacheKey, decoded)
            Log.d(RecomposeTag, "background decode success")
            true
        } else {
            Log.d(RecomposeTag, "background decode failed")
            false
        }
    }

    /** 裁剪页只需一张受控尺寸的方向修正预览，避免把相机原图完整放入 Compose。 */
    suspend fun loadPreview(uri: String, maxDimension: Int = 2048): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching { decodeOrientedPreview(Uri.parse(uri), maxDimension)?.asImageBitmap() }.getOrNull()
    }

    fun evictSource(uri: String) {
        val keys = mutableListOf<String>()
        val snapshot = cache.snapshot()
        snapshot.keys.filterTo(keys) { key -> key.startsWith("$uri|") }
        keys.forEach(cache::remove)
    }

    private fun decodeSampledBitmap(
        uri: String,
        crop: NormalizedCropRect?,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        val parsed = Uri.parse(uri)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, parsed)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val plan = calculateBackgroundDecodePlan(
                    sourceWidth = info.size.width,
                    sourceHeight = info.size.height,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                    normalizedCrop = crop
                )
                if (plan.scaledWidth != info.size.width || plan.scaledHeight != info.size.height) {
                    decoder.setTargetSize(plan.scaledWidth, plan.scaledHeight)
                }
                decoder.setCrop(plan.crop.toRect())
            }
        } else {
            decodeSampledBitmapLegacy(parsed, crop, targetWidth, targetHeight)
        }
    }

    private fun decodeSampledBitmapLegacy(
        uri: Uri,
        crop: NormalizedCropRect?,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val orientation = readImageOrientation(uri)
        val plan = calculateLegacyRegionDecodePlan(
            rawWidth = bounds.outWidth,
            rawHeight = bounds.outHeight,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            crop = crop,
            orientation = orientation
        )

        // Android 8.x 直接从原文件读取选中区域；全景图和高倍率裁剪不会先被整图降采样。
        val decodedRegion = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val decoder = newBitmapRegionDecoder(input) ?: return@use null
                try {
                    decoder.decodeRegion(
                        plan.rawRegion.toRect(),
                        BitmapFactory.Options().apply { inSampleSize = plan.sampleSize }
                    )
                } finally {
                    decoder.recycle()
                }
            }
        }.getOrNull()
        if (decodedRegion == null) {
            // GIF/BMP 等区域解码器不支持的格式仍保留安全整图降级，不让合法图片直接失效。
            return decodeSampledBitmapLegacyFallback(uri, crop, targetWidth, targetHeight)
        }
        val orientedRegion = applyImageOrientation(decodedRegion, orientation)
        val (outputWidth, outputHeight) = calculateDecodeTargetSize(
            orientedRegion.width,
            orientedRegion.height,
            targetWidth,
            targetHeight
        )
        return if (orientedRegion.width == outputWidth && orientedRegion.height == outputHeight) {
            orientedRegion
        } else {
            Bitmap.createScaledBitmap(orientedRegion, outputWidth, outputHeight, true).also {
                orientedRegion.recycle()
            }
        }
    }

    private fun decodeSampledBitmapLegacyFallback(
        uri: Uri,
        crop: NormalizedCropRect?,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        val oriented = decodeOrientedPreview(uri, max(targetWidth, targetHeight) * 2) ?: return null
        val plan = calculateBackgroundDecodePlan(
            sourceWidth = oriented.width,
            sourceHeight = oriented.height,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            normalizedCrop = crop
        )
        val selected = Bitmap.createBitmap(
            oriented,
            plan.sourceCrop.left,
            plan.sourceCrop.top,
            plan.sourceCrop.width,
            plan.sourceCrop.height
        )
        if (selected !== oriented) oriented.recycle()
        return if (selected.width == plan.outputWidth && selected.height == plan.outputHeight) {
            selected
        } else {
            Bitmap.createScaledBitmap(selected, plan.outputWidth, plan.outputHeight, true).also {
                selected.recycle()
            }
        }
    }

    private fun decodeOrientedPreview(uri: Uri, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = calculatePreviewSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
        val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        // GIF、BMP 等可被平台解码但没有 EXIF 容器；方向读取失败时仍应保留已解码图片。
        val orientation = readImageOrientation(uri)
        return applyImageOrientation(bitmap, orientation)
    }

    private fun readImageOrientation(uri: Uri): BackgroundImageOrientation {
        val exifOrientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        return when (exifOrientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> BackgroundImageOrientation.FLIP_HORIZONTAL
            ExifInterface.ORIENTATION_ROTATE_180 -> BackgroundImageOrientation.ROTATE_180
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> BackgroundImageOrientation.FLIP_VERTICAL
            ExifInterface.ORIENTATION_TRANSPOSE -> BackgroundImageOrientation.TRANSPOSE
            ExifInterface.ORIENTATION_ROTATE_90 -> BackgroundImageOrientation.ROTATE_90
            ExifInterface.ORIENTATION_TRANSVERSE -> BackgroundImageOrientation.TRANSVERSE
            ExifInterface.ORIENTATION_ROTATE_270 -> BackgroundImageOrientation.ROTATE_270
            else -> BackgroundImageOrientation.NORMAL
        }
    }

    private fun applyImageOrientation(bitmap: Bitmap, orientation: BackgroundImageOrientation): Bitmap {
        val matrix = exifOrientationMatrix(orientation)
        if (matrix.isIdentity) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }
}

/** 相机照片可能带旋转或镜像标记；预览与最终裁剪必须使用同一视觉方向。 */
private fun exifOrientationMatrix(orientation: BackgroundImageOrientation): Matrix = Matrix().apply {
    when (orientation) {
        BackgroundImageOrientation.FLIP_HORIZONTAL -> setScale(-1f, 1f)
        BackgroundImageOrientation.ROTATE_180 -> setRotate(180f)
        BackgroundImageOrientation.FLIP_VERTICAL -> setScale(1f, -1f)
        BackgroundImageOrientation.TRANSPOSE -> {
            setRotate(90f)
            postScale(-1f, 1f)
        }
        BackgroundImageOrientation.ROTATE_90 -> setRotate(90f)
        BackgroundImageOrientation.TRANSVERSE -> {
            setRotate(-90f)
            postScale(-1f, 1f)
        }
        BackgroundImageOrientation.ROTATE_270 -> setRotate(-90f)
        BackgroundImageOrientation.NORMAL -> Unit
    }
}

@Suppress("DEPRECATION")
private fun newBitmapRegionDecoder(input: InputStream): BitmapRegionDecoder? =
    BitmapRegionDecoder.newInstance(input, false)

private const val MAX_BACKGROUND_CACHE_BYTES = 24 * 1024 * 1024

/**
 * ARGB 位图按每像素 4 字节估算缓存成本，并对极端尺寸做饱和处理防止整数溢出。
 */
fun backgroundBitmapByteSize(width: Int, height: Int): Int {
    if (width <= 0 || height <= 0) return 0
    val pixels = width.toLong() * height.toLong()
    if (pixels > Int.MAX_VALUE.toLong() / 4L) return Int.MAX_VALUE
    return (pixels * 4L).toInt()
}

@Composable
fun ScheduleBackgroundImage(
    modifier: Modifier = Modifier,
    customBackgroundUri: String = "",
    customBackgroundBitmap: ImageBitmap? = null,
    dimAmount: Float = DEFAULT_BACKGROUND_DIM_AMOUNT
) {
    if (shouldUseCustomBackground(customBackgroundUri)) {
        val bitmap = customBackgroundBitmap ?: return
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Canvas(modifier = modifier.fillMaxSize()) {
            drawRect(Color.Black.copy(alpha = dimAmount.coerceIn(0f, 0.8f)))
        }
    } else {
        // 空值和内置标识都使用默认《花》，不走外部图片解码流程。
        BuiltInScheduleBackgroundImage(
            background = BuiltInScheduleBackground.fromStorageValue(customBackgroundUri)
                ?: BuiltInScheduleBackground.FLOWER,
            modifier = modifier,
            dimAmount = dimAmount
        )
    }
}

@Composable
private fun BuiltInScheduleBackgroundImage(
    background: BuiltInScheduleBackground,
    modifier: Modifier = Modifier,
    dimAmount: Float
) {
    Image(
        painter = painterResource(background.drawableRes),
        contentDescription = null,
        modifier = modifier.fillMaxSize(),
        contentScale = ContentScale.Crop
    )
    // 与外部自定义背景统一：按用户设置叠加黑色蒙层保障文字对比度。
    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(Color.Black.copy(alpha = dimAmount.coerceIn(0f, 0.8f)))
    }
}

fun shouldUseCustomBackground(uri: String): Boolean {
    return uri.isNotBlank() && BuiltInScheduleBackground.fromStorageValue(uri) == null
}

enum class BackgroundSwitchResult {
    Commit,
    KeepCurrent,
    Clear
}

fun shouldCommitCustomBackgroundUri(uri: String, preloadSucceeded: Boolean): BackgroundSwitchResult {
    return when {
        uri.isBlank() -> BackgroundSwitchResult.Clear
        preloadSucceeded -> BackgroundSwitchResult.Commit
        else -> BackgroundSwitchResult.KeepCurrent
    }
}

data class ImageCropRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    internal fun toRect() = Rect(left, top, right, bottom)
}

data class BackgroundDecodePlan(
    val sourceCrop: ImageCropRegion,
    val scaledWidth: Int,
    val scaledHeight: Int,
    val crop: ImageCropRegion
) {
    val outputWidth: Int get() = crop.width
    val outputHeight: Int get() = crop.height
}

fun calculateBackgroundDecodePlan(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
    normalizedCrop: NormalizedCropRect? = null
): BackgroundDecodePlan {
    val safeSourceWidth = max(1, sourceWidth)
    val safeSourceHeight = max(1, sourceHeight)
    val safeTargetWidth = max(1, targetWidth)
    val safeTargetHeight = max(1, targetHeight)
    val sourceCrop = normalizedCrop?.sanitized()?.let { crop ->
        val left = (crop.left * safeSourceWidth).roundToInt().coerceIn(0, safeSourceWidth - 1)
        val top = (crop.top * safeSourceHeight).roundToInt().coerceIn(0, safeSourceHeight - 1)
        val right = (crop.right * safeSourceWidth).roundToInt().coerceIn(left + 1, safeSourceWidth)
        val bottom = (crop.bottom * safeSourceHeight).roundToInt().coerceIn(top + 1, safeSourceHeight)
        ImageCropRegion(left, top, right, bottom)
    } ?: calculateCenterCropRegion(
        safeSourceWidth,
        safeSourceHeight,
        safeTargetWidth,
        safeTargetHeight
    )
    val (outputWidth, outputHeight) = calculateDecodeTargetSize(
        sourceCrop.width,
        sourceCrop.height,
        safeTargetWidth,
        safeTargetHeight
    )
    val scale = min(
        outputWidth.toFloat() / sourceCrop.width,
        outputHeight.toFloat() / sourceCrop.height
    ).coerceAtMost(1f)
    val scaledWidth = max(1, (safeSourceWidth * scale).roundToInt())
    val scaledHeight = max(1, (safeSourceHeight * scale).roundToInt())
    val scaledLeft = (sourceCrop.left * scale).roundToInt().coerceIn(0, max(0, scaledWidth - outputWidth))
    val scaledTop = (sourceCrop.top * scale).roundToInt().coerceIn(0, max(0, scaledHeight - outputHeight))
    val crop = ImageCropRegion(
        scaledLeft,
        scaledTop,
        (scaledLeft + outputWidth).coerceAtMost(scaledWidth),
        (scaledTop + outputHeight).coerceAtMost(scaledHeight)
    )
    return BackgroundDecodePlan(sourceCrop, scaledWidth, scaledHeight, crop)
}

internal fun calculateCenterCropRegion(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int
): ImageCropRegion {
    val sourceAspect = sourceWidth.toDouble() / sourceHeight
    val targetAspect = targetWidth.toDouble() / targetHeight
    return if (sourceAspect > targetAspect) {
        val width = (sourceHeight * targetAspect).roundToInt().coerceIn(1, sourceWidth)
        val left = (sourceWidth - width) / 2
        ImageCropRegion(left, 0, left + width, sourceHeight)
    } else {
        val height = (sourceWidth / targetAspect).roundToInt().coerceIn(1, sourceHeight)
        val top = (sourceHeight - height) / 2
        ImageCropRegion(0, top, sourceWidth, top + height)
    }
}

fun calculateDecodeTargetSize(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int
): Pair<Int, Int> {
    if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
        return max(1, sourceWidth) to max(1, sourceHeight)
    }
    if (sourceWidth <= targetWidth && sourceHeight <= targetHeight) {
        return sourceWidth to sourceHeight
    }

    val scale = min(targetWidth.toFloat() / sourceWidth, targetHeight.toFloat() / sourceHeight)
        .coerceAtMost(1f)
    return max(1, (sourceWidth * scale).roundToInt()) to max(1, (sourceHeight * scale).roundToInt())
}

fun calculateBitmapSampleSize(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int
): Int {
    var sampleSize = 1
    if (sourceHeight > targetHeight || sourceWidth > targetWidth) {
        var halfHeight = sourceHeight / 2
        var halfWidth = sourceWidth / 2
        while (halfHeight / sampleSize >= targetHeight || halfWidth / sampleSize >= targetWidth) {
            sampleSize *= 2
        }
    }
    return sampleSize.coerceAtLeast(1)
}

/** 裁剪预览优先限制内存，不要求维持最终背景的输出分辨率。 */
fun calculatePreviewSampleSize(sourceWidth: Int, sourceHeight: Int, maxDimension: Int): Int {
    val longestSide = max(max(1, sourceWidth), max(1, sourceHeight))
    val safeMaximum = max(1, maxDimension)
    var sampleSize = 1
    while (longestSide / sampleSize > safeMaximum && sampleSize <= Int.MAX_VALUE / 2) {
        sampleSize *= 2
    }
    return sampleSize
}
