package com.glut.schedule.ui.components

import android.os.SystemClock
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.glut.schedule.MainActivity
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/** 真实Activity使用系统时钟运行，避免Compose测试时钟影响启动副作用。 */
class LaunchWindowTimeoutTest {
    @Test fun missingBackgroundCallbackCannotKeepWindowBlockedForever() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            lateinit var ready: AtomicBoolean
            // 只通过反射制造生产环境的“回调未完成”，其余计时与清理均执行真实Activity代码。
            scenario.onActivity { activity ->
                val readyField = MainActivity::class.java.getDeclaredField("firstFrameReady").apply { isAccessible = true }
                ready = readyField.get(activity) as AtomicBoolean
            }
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (!ready.get() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
            assertTrue("匿名正常启动应先完成", ready.get())
            scenario.onActivity {
                ready.set(false)
                MainActivity::class.java.getDeclaredMethod("holdLaunchWindowUntilFirstFrameIsReady")
                    .apply { isAccessible = true }.invoke(it)
            }
            try {
                val timeout = SystemClock.uptimeMillis() + 5_000
                while (!ready.get() && SystemClock.uptimeMillis() < timeout) SystemClock.sleep(20)
                assertTrue("未收到任何背景完成通知时，启动窗口也必须按时释放", ready.get())
                scenario.onActivity {
                    val listener = MainActivity::class.java.getDeclaredField("firstFramePreDrawListener")
                        .apply { isAccessible = true }.get(it)
                    assertNull("超时后必须移除阻塞监听器，避免泄漏Activity", listener)
                }
            } finally {
                // 验证旧包失败时也释放模拟屏障，测试本身不能留下卡死Activity。
                scenario.onActivity {
                    MainActivity::class.java.getDeclaredMethod("releaseFirstFrame")
                        .apply { isAccessible = true }.invoke(it)
                }
            }
        }
    }
}
