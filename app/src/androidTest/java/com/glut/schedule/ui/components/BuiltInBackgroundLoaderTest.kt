package com.glut.schedule.ui.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.Rule

/** 使用真实 JPG 和设备解码器，覆盖高分辨率手机返回课表时的缓存复用。 */
class BuiltInBackgroundLoaderTest {
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
