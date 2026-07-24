package com.glut.schedule.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
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
import com.glut.schedule.R
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
    STARRY("", R.drawable.builtin_starry_background, "星空"),
    FLOWER("builtin://flower", R.drawable.builtin_flower_background, "花")
    ;

    companion object {
        fun fromStorageValue(value: String): BuiltInScheduleBackground? =
            entries.firstOrNull { background ->
                background.storageValue.isNotBlank() && background.storageValue == value
            }
    }
}

class ScheduleBackgroundStore(
    private val context: Context
) {
    private val cache = object : LruCache<String, ImageBitmap>(2) {}

    fun get(uri: String): ImageBitmap? = cache.get(uri)

    suspend fun preload(uri: String, targetWidth: Int, targetHeight: Int): Boolean = withContext(Dispatchers.IO) {
        if (!shouldUseCustomBackground(uri)) return@withContext true
        if (cache.get(uri) != null) {
            Log.d(RecomposeTag, "background cache hit")
            return@withContext true
        }

        Log.d(RecomposeTag, "background decode start")
        val decoded = runCatching {
            decodeSampledBitmap(uri, targetWidth, targetHeight)?.asImageBitmap()
        }.getOrNull()
        if (decoded != null) {
            cache.put(uri, decoded)
            Log.d(RecomposeTag, "background decode success")
            true
        } else {
            Log.d(RecomposeTag, "background decode failed")
            false
        }
    }

    fun preloadBlocking(uri: String, targetWidth: Int, targetHeight: Int): Boolean {
        return runBlocking {
            preload(uri, targetWidth, targetHeight)
        }
    }

    private fun decodeSampledBitmap(uri: String, targetWidth: Int, targetHeight: Int): Bitmap? {
        val parsed = Uri.parse(uri)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, parsed)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val plan = calculateBackgroundDecodePlan(
                    sourceWidth = info.size.width,
                    sourceHeight = info.size.height,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight
                )
                if (plan.scaledWidth != info.size.width || plan.scaledHeight != info.size.height) {
                    decoder.setTargetSize(plan.scaledWidth, plan.scaledHeight)
                }
                decoder.setCrop(plan.crop.toRect())
            }
        } else {
            decodeSampledBitmapLegacy(parsed, targetWidth, targetHeight)
        }
    }

    private fun decodeSampledBitmapLegacy(uri: Uri, targetWidth: Int, targetHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val plan = calculateBackgroundDecodePlan(
            sourceWidth = bounds.outWidth,
            sourceHeight = bounds.outHeight,
            targetWidth = targetWidth,
            targetHeight = targetHeight
        )

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateBitmapSampleSize(
                sourceWidth = plan.sourceCrop.width,
                sourceHeight = plan.sourceCrop.height,
                targetWidth = plan.outputWidth,
                targetHeight = plan.outputHeight
            )
        }
        val sampled = context.contentResolver.openInputStream(uri)?.use { input ->
            val decoder = newBitmapRegionDecoder(input) ?: return@use null
            try {
                decoder.decodeRegion(plan.sourceCrop.toRect(), options)
            } finally {
                decoder.recycle()
            }
        } ?: return null
        return if (sampled.width == plan.outputWidth && sampled.height == plan.outputHeight) {
            sampled
        } else {
            Bitmap.createScaledBitmap(sampled, plan.outputWidth, plan.outputHeight, true).also {
                sampled.recycle()
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun newBitmapRegionDecoder(input: InputStream): BitmapRegionDecoder? =
    BitmapRegionDecoder.newInstance(input, false)

@Composable
fun StarryScheduleBackground(
    modifier: Modifier = Modifier,
    customBackgroundUri: String = "",
    customBackgroundBitmap: ImageBitmap? = null
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
            drawRect(Color(0x99000000))
        }
    } else {
        // 空值代表默认星空；内置标识只切换资源，不走外部图片解码流程。
        BuiltInScheduleBackgroundImage(
            background = BuiltInScheduleBackground.fromStorageValue(customBackgroundUri)
                ?: BuiltInScheduleBackground.STARRY,
            modifier = modifier
        )
    }
}

@Composable
private fun BuiltInScheduleBackgroundImage(
    background: BuiltInScheduleBackground,
    modifier: Modifier = Modifier
) {
    Image(
        painter = painterResource(background.drawableRes),
        contentDescription = null,
        modifier = modifier.fillMaxSize(),
        contentScale = ContentScale.Crop
    )
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
    targetHeight: Int
): BackgroundDecodePlan {
    val safeSourceWidth = max(1, sourceWidth)
    val safeSourceHeight = max(1, sourceHeight)
    val safeTargetWidth = max(1, targetWidth)
    val safeTargetHeight = max(1, targetHeight)
    val sourceCrop = calculateCenterCropRegion(
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
    val crop = calculateCenterCropRegion(
        scaledWidth,
        scaledHeight,
        outputWidth,
        outputHeight
    )
    return BackgroundDecodePlan(sourceCrop, scaledWidth, scaledHeight, crop)
}

private fun calculateCenterCropRegion(
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
