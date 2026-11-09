package com.glut.schedule.ui.components

import android.os.SystemClock
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/** 重现真实启动窗口禁止首帧时，首次背景解码能否完成；不读取任何课表或账号数据。 */
class BackgroundStartupGateTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun coldBackgroundCompletesWhileWindowPreDrawIsHeld() {
        val completed = AtomicBoolean(false)
        val gate = ViewTreeObserver.OnPreDrawListener { completed.get() }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.activityRule.scenario.onActivity { activity ->
            activity.window.decorView.viewTreeObserver.addOnPreDrawListener(gate)
            activity.setContent {
                BuiltInBackgroundImage(
                    modifier = Modifier.fillMaxSize(),
                    onLoadFinished = { completed.set(true) }
                )
            }
        }
        try {
            // 不使用 Compose 的 waitForIdle：被测对象本来就在阻止首帧，需从测试线程独立计时。
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (!completed.get() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
            assertTrue("禁止首帧时背景加载回调仍须完成，否则真实冷启动会永久黑白屏", completed.get())
        } finally {
            instrumentation.runOnMainSync {
                compose.activity.window.decorView.viewTreeObserver.removeOnPreDrawListener(gate)
                compose.activity.window.decorView.postInvalidateOnAnimation()
            }
        }
    }
}
