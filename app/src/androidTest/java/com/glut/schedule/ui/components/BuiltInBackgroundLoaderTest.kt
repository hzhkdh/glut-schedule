package com.glut.schedule.ui.components

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.glut.schedule.R
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.Rule
import java.io.File

/** 使用真实 JPG 和设备解码器，覆盖高分辨率手机返回课表时的缓存复用。 */
class BuiltInBackgroundLoaderTest {
    @Test fun oversizedCustomBackgroundRemainsAvailableAfterSuccessfulPreload(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("anonymous-background-", ".jpg", context.cacheDir)
        try {
            // 纯色匿名测试图，原像素输出会超过24MiB缓存，不触及手机中的照片或课表。
            val bitmap = Bitmap.createBitmap(3200, 2000, Bitmap.Config.ARGB_8888)
            try {
                source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            } finally { bitmap.recycle() }
            val store = ScheduleBackgroundStore(context)
            val uri = Uri.fromFile(source).toString()
            assertTrue(store.preload(uri, null, 3200, 2000))
            assertNotNull("加载成功后必须能从缓存拿到背景，否则启动会永久等待", store.get(uri, null, 3200, 2000))
            store.evictSource(uri)
        } finally { source.delete() }
    }

    @Test fun placeholderIsSmallAndAvailableBeforeHighResolutionDecode() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val placeholder = BuiltInBackgroundLoader.placeholder(context, BuiltInScheduleBackground.WOMAN_WITH_A_PARASOL)
        assertNotNull("首帧占位图必须能同步解码，否则启动会退回空底色", placeholder)
        // 缩略图只为首帧兜底，必须足够小，避免又把启动内存拖回原图规模。
        assertTrue(placeholder!!.width <= 512 && placeholder.height <= 640)
        assertSame(
            "占位图应复用同一实例，避免每次组合重复解码",
            placeholder,
            BuiltInBackgroundLoader.placeholder(context, BuiltInScheduleBackground.WOMAN_WITH_A_PARASOL)
        )
    }

    @Test fun bundledBackgroundAssetStaysWithinScreenScaleBudget() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.resources.openRawResource(R.drawable.builtin_monet_woman_with_a_parasol).use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        // 原图只需覆盖最大常见屏幕再留一点余量；再大就是白白增加 APK 与首次解码成本。
        assertTrue("内置背景原图不应超过 2400 宽，实际 ${bounds.outWidth}", bounds.outWidth in 1..2400)
        assertTrue("内置背景原图不应超过 800 万像素", bounds.outWidth.toLong() * bounds.outHeight <= 8_000_000L)
    }

    @Test fun trimMemoryReleasesSharedBackgroundCache() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = BuiltInBackgroundLoader.borrow(context, targetWidth = 240, targetHeight = 480)
        assertNotNull(first)
        trimBackgroundCaches(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        val second = BuiltInBackgroundLoader.borrow(context, targetWidth = 240, targetHeight = 480)
        assertNotNull(second)
        assertNotSame("内存回收后必须重新解码，而不是返回已被淘汰的旧实例", first, second)
    }

    @Test fun highResolutionBackgroundSurvivesPreviewAndPageReentry() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val original = BuiltInBackgroundLoader.borrow(context, targetWidth = 1440, targetHeight = 3168)
        assertNotNull("默认背景必须能成功解码", original)
        // 缩略图不应把正在使用的全屏背景挤出缓存，否则返回页面又要解码。
        BuiltInBackgroundLoader.borrow(context, targetWidth = 240, targetHeight = 480)
        val returned = BuiltInBackgroundLoader.borrow(context, targetWidth = 1440, targetHeight = 3168)
        assertSame("高分屏背景在页面返回时必须直接复用，不能因超过 16 MiB 而自我淘汰", original, returned)
    }
}

/** 只截取匿名测试组件的首个绘制帧，避免等待空白帧被后续加载覆盖。 */
class BuiltInBackgroundRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cachedBackgroundIsVisibleOnFirstDrawAfterReentry() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        runBlocking {
            assertNotNull(BuiltInBackgroundLoader.borrow(context, targetWidth = 240, targetHeight = 480))
        }
        val recorded = AtomicBoolean(false)
        val firstFrame = AtomicReference<ImageBitmap?>()
        val density = context.resources.displayMetrics.density
        compose.setContent {
            val layer = rememberGraphicsLayer()
            val scope = rememberCoroutineScope()
            BuiltInBackgroundImage(modifier = Modifier
                .requiredSize((240 / density).dp, (480 / density).dp)
                .drawWithContent {
                    if (recorded.compareAndSet(false, true)) {
                        layer.record { this@drawWithContent.drawContent() }
                        drawLayer(layer)
                        scope.launch { firstFrame.set(layer.toImageBitmap()) }
                    } else drawContent()
                })
        }
        compose.waitUntil(10_000) { firstFrame.get() != null }
        val pixels = firstFrame.get()!!.toPixelMap()
        assertTrue("返回页面的首帧必须已经画出缓存背景，不能先露出空白底色", pixels[120, 240].alpha > 0.99f)
    }
}
