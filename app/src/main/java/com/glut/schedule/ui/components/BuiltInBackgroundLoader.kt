package com.glut.schedule.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.os.Build
import android.util.Log
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
import com.glut.schedule.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

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
    // 中央裁剪取整可能相差1像素，先保持原有的精确布局尺寸，再应用高分屏内存上限。
    val visibleWidth = min(width, region.width)
    val visibleHeight = min(height, region.height)
    val (outputWidth, outputHeight) = calculateDecodeTargetSize(visibleWidth, visibleHeight, visibleWidth, visibleHeight)
    var sample = 1
    // 两个方向都充足才允许下一档采样，避免长屏按宽度采样导致画面变糊。
    while (sample <= Int.MAX_VALUE / 2 &&
        region.width / (sample * 2) >= outputWidth && region.height / (sample * 2) >= outputHeight) {
        sample *= 2
    }
    return BuiltInBackgroundDecodePlan(region, sample, outputWidth, outputHeight)
}

/**
 * 全应用共享内置画作的运行时位图。返回值均为借用：调用者不得 recycle。
 * 淘汰只移除强引用，仍在 Compose 或导出任务中使用的图由 GC 在最后一个引用释放后回收。
 */
object BuiltInBackgroundLoader {
    // 内置原图内容变更时递增，旧的内存键与磁盘派生图随之失效。
    private const val ASSET_REVISION = 3
    private const val RENDER_CACHE_DIRECTORY = "builtin_background_renders"
    private const val RENDER_CACHE_LIMIT_BYTES = 32L * 1024 * 1024
    private const val RENDER_QUALITY = 92

    private val mutex = Mutex()
    // 派生图落盘不参与首帧等待，放到独立后台作用域，避免 JPEG 编码推迟高分图显示。
    private val diskWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 占位图很小（约1MiB），必须在高分图缺席时仍然可用，因此单独缓存，不参与大图 LRU 淘汰。
    private val placeholderCache = object : LruCache<String, Bitmap>(2) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    // 使用布局像素作为键，首帧查询只访问内存，不必先开文件读取原图尺寸。
    // `builtin|` 前缀与自定义背景（`custom|`）共存于同一个共享缓存。
    private fun cacheKey(background: BuiltInScheduleBackground, width: Int, height: Int): String =
        "builtin|${background.drawableRes}|$ASSET_REVISION|$width|$height"

    internal fun getCached(background: BuiltInScheduleBackground, width: Int, height: Int): Bitmap? =
        BackgroundBitmapCache.get(cacheKey(background, width, height))

    /** 内存临界时释放缩略图；它只有约 1MiB，但临界状态下每一兆都值得让出。 */
    internal fun evictPlaceholderCache() {
        placeholderCache.evictAll()
    }

    /**
     * 同图缩略图：同步解码约数毫秒，只用于高分图就绪前的首帧。
     * 它让启动窗口立刻有一张构图相同的画，而不是空底色或黑屏。
     */
    fun placeholder(context: Context, background: BuiltInScheduleBackground): Bitmap? {
        placeholderCache.get(background.storageValue)?.let { return it }
        val bitmap = runCatching {
            BitmapFactory.decodeResource(
                context.applicationContext.resources,
                background.placeholderRes,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                }
            )
        }.getOrNull() ?: return null
        placeholderCache.put(background.storageValue, bitmap)
        return bitmap
    }

    /** 派生图文件名带上版本与目标尺寸：升级换原图、换设备分辨率后都不会误用旧缓存。 */
    private fun renderCacheFile(
        context: Context,
        background: BuiltInScheduleBackground,
        width: Int,
        height: Int
    ): File {
        val directory = File(context.applicationContext.cacheDir, RENDER_CACHE_DIRECTORY)
        if (!directory.isDirectory) directory.mkdirs()
        return File(
            directory,
            "${background.storageValue.hashCode()}-v${BuildConfig.VERSION_CODE}-r$ASSET_REVISION-${width}x$height.jpg"
        )
    }

    /** 读取上次冷启动落盘的屏幕尺寸派生图；尺寸不符时直接丢弃，避免把旧图拉伸上屏。 */
    private fun readRenderCache(
        context: Context,
        background: BuiltInScheduleBackground,
        width: Int,
        height: Int
    ): Bitmap? {
        val file = renderCacheFile(context, background, width, height)
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        // 源图可能小于布局目标（例如降采样后的原图），派生图只会"不大于目标"；
        // 因此校验宽高不超过目标、且宽高比一致，不再要求精确相等。
        val cachedAspect = bounds.outWidth.toDouble() / bounds.outHeight
        val targetAspect = width.toDouble() / height
        if (bounds.outWidth !in 1..width || bounds.outHeight !in 1..height ||
            abs(cachedAspect - targetAspect) > 0.02
        ) {
            file.delete()
            return null
        }
        return runCatching {
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                }
            )
        }.getOrNull()
    }

    private fun writeRenderCache(
        context: Context,
        background: BuiltInScheduleBackground,
        width: Int,
        height: Int,
        bitmap: Bitmap
    ) {
        val target = renderCacheFile(context, background, width, height)
        val temp = File(target.parentFile, "${target.name}.tmp")
        // 先写临时文件再原子改名，进程被杀时不会留下半张图。
        val written = temp.outputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, RENDER_QUALITY, output)
        }
        if (!written) {
            temp.delete()
            return
        }
        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) {
                temp.delete()
                return
            }
        }
        trimRenderCache(context)
    }

    /** 派生存量按最旧优先淘汰，缓存目录本来也可能被系统随时清理。 */
    private fun trimRenderCache(context: Context) {
        val directory = File(context.applicationContext.cacheDir, RENDER_CACHE_DIRECTORY)
        val files = directory.listFiles()?.filter { it.isFile && it.name.endsWith(".jpg") } ?: return
        var total = files.sumOf { it.length() }
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= RENDER_CACHE_LIMIT_BYTES) break
            total -= file.length()
            file.delete()
        }
    }

    suspend fun borrow(
        context: Context,
        background: BuiltInScheduleBackground = BuiltInScheduleBackground.WOMAN_WITH_A_PARASOL,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        val requestStart = android.os.SystemClock.uptimeMillis()
        mutex.withLock {
            val resources = context.applicationContext.resources
            val key = cacheKey(background, targetWidth, targetHeight)
            BackgroundBitmapCache.get(key)?.let { return@withLock it }

            // 第二步查上一次冷启动落盘的屏幕尺寸小图：通常只要几十毫秒，远快于原图。
            readRenderCache(context, background, targetWidth, targetHeight)?.let { cached ->
                BackgroundBitmapCache.put(key, cached)
                Log.i(
                    "ScheduleStartup",
                    "builtin background disk hit ${cached.width}x${cached.height} " +
                        "in ${android.os.SystemClock.uptimeMillis() - requestStart}ms"
                )
                return@withLock cached
            }

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
            Log.i(
                "ScheduleStartup",
                "builtin background decoded ${if (bitmap == null) "failed" else "${bitmap.width}x${bitmap.height}"} " +
                    "in ${android.os.SystemClock.uptimeMillis() - requestStart}ms"
            )
            bitmap?.also {
                BackgroundBitmapCache.put(key, it)
                // 首次成功解码后落盘；后续冷启动直接走上面的小图路径。
                diskWriteScope.launch {
                    runCatching {
                        writeRenderCache(context.applicationContext, background, targetWidth, targetHeight, it)
                    }.onFailure {
                        Log.w("ScheduleStartup", "内置背景派生图落盘失败", it)
                    }
                }
            }
        }
    }
}

/** 真实布局尺寸确定后在 IO 线程解码，主课表、搭子页及设置缩略图共用同一缓存。 */
@Composable
fun BuiltInBackgroundImage(
    background: BuiltInScheduleBackground = BuiltInScheduleBackground.WOMAN_WITH_A_PARASOL,
    modifier: Modifier = Modifier,
    onLoadFinished: () -> Unit = {}
) {
    val context = LocalContext.current
    val latestOnLoadFinished by rememberUpdatedState(onLoadFinished)
    // 高分图解码期间先画同一幅画的缩略图；同步解码只有几毫秒，不会重新把启动拖慢。
    val placeholder = remember(background) {
        BuiltInBackgroundLoader.placeholder(context, background)
    }
    BoxWithConstraints(modifier) {
        // 在子内容首次组合时就取得真实布局尺寸，避免 onSizeChanged 后才启动加载而先画空帧。
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val cached = remember(background, width, height) {
            BuiltInBackgroundLoader.getCached(background, width, height)
        }
        val bitmap by produceState<Bitmap?>(cached, background, width, height) {
            try {
                value = cached ?: if (width > 0 && height > 0) {
                    BuiltInBackgroundLoader.borrow(context, background, width, height)
                } else null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // 资源打开、尺寸读取等也可能失败，不能只保护解码核心而漏掉启动完成通知。
                Log.w("ScheduleStartup", "内置背景加载失败，使用缩略图或纯色页面", error)
                value = null
            } catch (error: OutOfMemoryError) {
                Log.w("ScheduleStartup", "内置背景内存不足，使用缩略图或纯色页面")
                value = null
            }
            // 成功或确定失败都结束启动等待；首帧不再依赖这次解码。
            if (width > 0 && height > 0) latestOnLoadFinished()
        }
        // 高分图缺席时用缩略图顶上，两者构图一致，切换时不会闪成另一种颜色。
        val shown = bitmap ?: placeholder
        shown?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), alignment = Alignment.Center,
                contentScale = ContentScale.Crop)
        }
    }
}
