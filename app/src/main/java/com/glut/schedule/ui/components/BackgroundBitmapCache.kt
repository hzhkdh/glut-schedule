package com.glut.schedule.ui.components

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.util.LruCache

/**
 * 内置画作与自定义背景共用的字节级 LRU。
 *
 * 两套背景各自持有 24MiB 时，总上限会随使用路径漂到 48MiB；统一预算后，
 * 同一时刻只保留真正在屏幕上使用的那一张图（以及少量缩略图）。
 * 键由调用方加前缀区分：内置 `builtin|...`、自定义 `custom|...`。
 */
internal object BackgroundBitmapCache {
    // 单张 1440×3168 背景约 18.2MiB，32MiB 足够容纳它再加一张缩略图/预览图。
    private const val MAX_BYTES = 32 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }

    fun remove(key: String) {
        cache.remove(key)
    }

    fun keysMatching(predicate: (String) -> Boolean): List<String> =
        cache.snapshot().keys.filter(predicate)

    fun evictAll() {
        cache.evictAll()
    }
}

/**
 * 系统内存紧张时释放背景位图。
 *
 * 背景是纯装饰资源，随时都能从磁盘派生图或原图重新解码；主动让出内存
 * 可以降低进程被系统杀掉的概率，避免下一次变成慢冷启动。
 */
fun trimBackgroundCaches(level: Int) {
    BackgroundBitmapCache.evictAll()
    // 缩略图只有约 1MiB，普通 UI_HIDDEN 不清理，避免返回前台时重新解码；
    // 只有系统明确报告内存临界时才释放。
    if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
        level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE
    ) {
        BuiltInBackgroundLoader.evictPlaceholderCache()
    }
}
