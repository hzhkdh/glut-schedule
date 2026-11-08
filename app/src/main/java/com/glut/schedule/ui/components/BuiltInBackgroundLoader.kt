package com.glut.schedule.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.ceil

internal data class BuiltInBackgroundDecodePlan(
    val region: ImageCropRegion,
    val sampleSize: Int,
    val decodedWidth: Int,
    val decodedHeight: Int
) {
    val byteSize: Long get() = decodedWidth.toLong() * decodedHeight * 4
}

/** 原文件保持原字节；只解码目标可见的中央区域，采样后仍须覆盖每个目标像素。 */
internal fun calculateBuiltInBackgroundDecodePlan(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int
): BuiltInBackgroundDecodePlan {
    val width = max(1, targetWidth)
    val height = max(1, targetHeight)
    val region = calculateCenterCropRegion(max(1, sourceWidth), max(1, sourceHeight), width, height)
    var sample = 1
    // 两个方向都充足才允许下一档采样，避免长屏按宽度采样导致画面变糊。
    while (sample <= Int.MAX_VALUE / 2 &&
        region.width / (sample * 2) >= width && region.height / (sample * 2) >= height) {
        sample *= 2
    }
    return BuiltInBackgroundDecodePlan(region, sample, min(width, region.width), min(height, region.height))
}

/**
 * 全应用共享内置画作的运行时位图。返回值均为借用：调用者不得 recycle。
 * 淘汰只移除强引用，仍在 Compose 或导出任务中使用的图由 GC 在最后一个引用释放后回收。
 */
object BuiltInBackgroundLoader {
    private const val CACHE_BYTES = 24 * 1024 * 1024
    private val mutex = Mutex()
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    // 使用布局像素作为键，首帧查询只访问内存，不必先开文件读取原图尺寸。
    private fun cacheKey(background: BuiltInScheduleBackground, width: Int, height: Int): String =
        "${background.drawableRes}|$width|$height"

    internal fun getCached(background: BuiltInScheduleBackground, width: Int, height: Int): Bitmap? =
        cache.get(cacheKey(background, width, height))

    suspend fun borrow(
        context: Context,
        background: BuiltInScheduleBackground = BuiltInScheduleBackground.FLOWER,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val resources = context.applicationContext.resources
            // 1440×3168 的背景约 17.4 MiB，旧 16 MiB 缓存会在 put 后立即将其淘汰。
            // 至少容纳一张设备全屏图，并给设置缩略图留出余量；更大屏幕按实际像素扩容。
            val metrics = resources.displayMetrics
            val screenBudget = (metrics.widthPixels.toLong() * metrics.heightPixels * 4 + 4 * 1024 * 1024)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val budget = max(CACHE_BYTES, screenBudget)
            if (cache.maxSize() != budget) cache.resize(budget)
            val key = cacheKey(background, targetWidth, targetHeight)
            cache.get(key)?.let { return@withLock it }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
            resources.openRawResource(background.drawableRes).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withLock null
            val plan = calculateBuiltInBackgroundDecodePlan(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
            // 内置原始 JPEG 支持区域解码，禁止退回整图解码，以免重新引入约 70MiB 的分配。
            val bitmap = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // 新平台直接输出目标像素的中央区域，避免持有采样档位之间的大中间图。
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(resources, background.drawableRes)) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val scale = max(plan.decodedWidth.toDouble() / plan.region.width,
                            plan.decodedHeight.toDouble() / plan.region.height).coerceAtMost(1.0)
                        val width = ceil(bounds.outWidth * scale).toInt()
                        val height = ceil(bounds.outHeight * scale).toInt()
                        decoder.setTargetSize(width, height)
                        val left = (width - plan.decodedWidth) / 2
                        val top = (height - plan.decodedHeight) / 2
                        decoder.setCrop(Rect(left, top, left + plan.decodedWidth, top + plan.decodedHeight))
                    }
                } else resources.openRawResource(background.drawableRes).use { input ->
                    @Suppress("DEPRECATION")
                    val decoder = BitmapRegionDecoder.newInstance(input, false) ?: return@use null
                    try {
                        val region = decoder.decodeRegion(plan.region.toRect(), BitmapFactory.Options().apply {
                            inSampleSize = plan.sampleSize
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                            inScaled = false
                        }) ?: return@use null
                        if (region.width == plan.decodedWidth && region.height == plan.decodedHeight) region
                        else try {
                            // 旧平台只能先读中央区域；仅回收本次私有中间图，不触碰共享缓存。
                            Bitmap.createScaledBitmap(region, plan.decodedWidth, plan.decodedHeight, true)
                        } finally { region.recycle() }
                    } finally { decoder.recycle() }
                }
            }.getOrNull()
            bitmap?.also { cache.put(key, it) }
        }
    }
}

/** 真实布局尺寸确定后在 IO 线程解码，主课表、搭子页及设置缩略图共用同一缓存。 */
@Composable
fun BuiltInBackgroundImage(
    background: BuiltInScheduleBackground = BuiltInScheduleBackground.FLOWER,
    modifier: Modifier = Modifier,
    onLoadFinished: () -> Unit = {}
) {
    val context = LocalContext.current
    val latestOnLoadFinished by rememberUpdatedState(onLoadFinished)
    BoxWithConstraints(modifier) {
        // 在子内容首次组合时就取得真实布局尺寸，避免 onSizeChanged 后才启动加载而先画空帧。
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val cached = remember(background, width, height) {
            BuiltInBackgroundLoader.getCached(background, width, height)
        }
        val bitmap by produceState<Bitmap?>(cached, background, width, height) {
            value = cached ?: if (width > 0 && height > 0) {
                BuiltInBackgroundLoader.borrow(context, background, width, height)
            } else null
            // 成功或确定失败都结束启动等待；命中缓存时首帧直接绘图，不依赖协程调度。
            if (width > 0 && height > 0) latestOnLoadFinished()
        }
        bitmap?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), alignment = Alignment.Center,
                contentScale = ContentScale.Crop)
        }
    }
}
