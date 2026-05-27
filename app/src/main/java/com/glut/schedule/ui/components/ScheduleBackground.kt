package com.glut.schedule.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val RecomposeTag = "Recompose"

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
                val (width, height) = calculateDecodeTargetSize(
                    sourceWidth = info.size.width,
                    sourceHeight = info.size.height,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight
                )
                decoder.setTargetSize(width, height)
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

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateBitmapSampleSize(
                sourceWidth = bounds.outWidth,
                sourceHeight = bounds.outHeight,
                targetWidth = targetWidth,
                targetHeight = targetHeight
            )
        }
        val sampled = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: return null
        val (targetBitmapWidth, targetBitmapHeight) = calculateDecodeTargetSize(
            sourceWidth = sampled.width,
            sourceHeight = sampled.height,
            targetWidth = targetWidth,
            targetHeight = targetHeight
        )
        return if (sampled.width == targetBitmapWidth && sampled.height == targetBitmapHeight) {
            sampled
        } else {
            Bitmap.createScaledBitmap(sampled, targetBitmapWidth, targetBitmapHeight, true).also {
                sampled.recycle()
            }
        }
    }
}

@Composable
fun StarryScheduleBackground(
    modifier: Modifier = Modifier,
    customBackgroundUri: String = "",
    customBackgroundBitmap: ImageBitmap? = null
) {
    Log.d(RecomposeTag, "StarryScheduleBackground compose custom=${customBackgroundUri.isNotBlank()}")
    if (shouldUseCustomBackground(customBackgroundUri)) {
        val bitmap = customBackgroundBitmap ?: run {
            Log.d(RecomposeTag, "background not ready - blocked")
            return
        }
        Log.d(RecomposeTag, "custom background image render")
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
        DefaultScheduleBackground(modifier = modifier)
    }
}

@Composable
private fun DefaultScheduleBackground(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF050816),
                    Color(0xFF102A43),
                    Color(0xFF0F766E),
                    Color(0xFF2A1427)
                ),
                start = Offset.Zero,
                end = Offset(size.width, size.height)
            )
        )
        drawCircle(Color(0x33FDE68A), radius = size.minDimension * 0.34f, center = Offset(size.width * 0.18f, size.height * 0.22f))
        drawCircle(Color(0x3322D3EE), radius = size.minDimension * 0.42f, center = Offset(size.width * 0.78f, size.height * 0.72f))
        drawCircle(Color(0x22F472B6), radius = size.minDimension * 0.24f, center = Offset(size.width * 0.62f, size.height * 0.18f))
        val stars = listOf(
            Offset(0.16f, 0.12f), Offset(0.34f, 0.18f), Offset(0.52f, 0.10f),
            Offset(0.82f, 0.20f), Offset(0.74f, 0.42f), Offset(0.28f, 0.66f),
            Offset(0.48f, 0.78f), Offset(0.88f, 0.86f)
        )
        stars.forEachIndexed { index, star ->
            drawCircle(
                color = Color.White.copy(alpha = if (index % 2 == 0) 0.36f else 0.22f),
                radius = 2.2f + index % 3,
                center = Offset(star.x * size.width, star.y * size.height)
            )
        }
        drawRect(Color(0x66000000))
    }
}

fun shouldUseCustomBackground(uri: String): Boolean {
    return uri.isNotBlank()
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
