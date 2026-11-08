package com.glut.schedule.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Test

/** 使用桌面同样的 RemoteViews 加载路径，防止预览混入只支持应用内使用的控件。 */
class ScheduleWidgetPreviewRenderingTest {
    @Test fun compactTodayPreviewCanBeInflatedByLauncher() = assertPreviewRenders("CompactTodayWidgetReceiver")

    @Test fun todayTomorrowPreviewCanBeInflatedByLauncher() = assertPreviewRenders("TodayTomorrowWidgetReceiver")

    @Test fun colorTimelinePreviewCanBeInflatedByLauncher() = assertPreviewRenders("ColorTimelineWidgetReceiver")

    private fun assertPreviewRenders(receiverName: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val component = ComponentName(context.packageName, "com.glut.schedule.widget.$receiverName")
        val provider = AppWidgetManager.getInstance(context).installedProviders.single { it.provider == component }
        // 从系统注册信息读取预览资源，覆盖 XML 与提供器配置之间的真实关联。
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            // 把主线程加载异常转回测试线程，保证三种预览均能执行并分别报告结果。
            failure = runCatching {
                val preview = RemoteViews(context.packageName, provider.previewLayout)
                    .apply(context, FrameLayout(context))
                assertNotNull("$receiverName 的预览必须能由桌面加载", preview)
            }.exceptionOrNull()
        }
        failure?.let { throw AssertionError("$receiverName 的预览无法由桌面加载", it) }
    }
}
