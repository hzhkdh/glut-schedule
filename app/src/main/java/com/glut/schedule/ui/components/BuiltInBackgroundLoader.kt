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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
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
    private const val CACHE_BYTES = 16 * 1024 * 1024
    private val mutex = Mutex()
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    suspend fun borrow(
        context: Context,
        background: BuiltInScheduleBackground = BuiltInScheduleBackground.FLOWER,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val resources = context.applicationContext.resources
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
            resources.openRawResource(background.drawableRes).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withLock null
            val plan = calculateBuiltInBackgroundDecodePlan(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
            // 同一裁剪比例也可能来自不同像素尺寸的预览，键必须包含最终输出尺寸。
            val key = "${background.drawableRes}|$plan"
            cache.get(key)?.let { return@withLock it }
            // PNG 支持区域解码，禁止退回完整原图解码，以免重新引入约 70MiB 的分配。
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
    var size by remember { mutableStateOf(IntSize.Zero) }
    val latestOnLoadFinished by rememberUpdatedState(onLoadFinished)
    val bitmap by produceState<Bitmap?>(null, background, size) {
        value = if (size.width > 0 && size.height > 0) {
            BuiltInBackgroundLoader.borrow(context, background, size.width, size.height)
        } else null
        // 成功后放行真实画面；确定解码失败也须结束启动等待，避免启动窗口永久停留。
        if (size.width > 0 && size.height > 0) latestOnLoadFinished()
    }
    Box(modifier.onSizeChanged { size = it }) {
        bitmap?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), alignment = Alignment.Center,
                contentScale = ContentScale.Crop)
        }
    }
}
