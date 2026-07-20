package com.glut.schedule

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleWidgetPreviewContractTest {
    @Test
    fun everyWidgetProvidesLegacyAndScalablePreviews() {
        val variants = listOf(
            "compact_today",
            "today_tomorrow",
            "color_timeline"
        )

        variants.forEach { variant ->
            val provider = appFile("src/main/res/xml/widget_${variant}_info.xml").readText()

            assertTrue(
                "$variant must provide an OEM-compatible static preview",
                provider.contains("android:previewImage=\"@drawable/widget_preview_$variant\"")
            )
            assertTrue(
                "$variant must provide an Android 12+ scalable preview",
                provider.contains("android:previewLayout=\"@layout/widget_preview_$variant\"")
            )
            assertFalse(
                "$variant must not use the launcher icon as its preview",
                provider.contains("@mipmap/ic_launcher")
            )
            assertTrue(
                "$variant static preview is missing",
                appFile("src/main/res/drawable-nodpi/widget_preview_$variant.png").isFile
            )
            assertTrue(
                "$variant scalable preview is missing",
                appFile("src/main/res/layout/widget_preview_$variant.xml").isFile
            )
        }
    }

    @Test
    fun applicationStartupDoesNotDiscardTheInitialWidgetSnapshot() {
        val application = appFile("src/main/java/com/glut/schedule/ScheduleApplication.kt").readText()
        val observer = application
            .substringAfter("private fun observeWidgetDataChanges()")
            .substringBefore("class AppContainer")

        assertFalse(observer.contains(".drop(1)"))
        assertTrue(observer.contains("ScheduleWidgetUpdater.updateAll"))
    }

    @Test
    fun widgetDataLoadingPropagatesCoroutineCancellation() {
        val dataSource = appFile(
            "src/main/java/com/glut/schedule/widget/ScheduleWidgetDataSource.kt"
        ).readText()

        val cancellationCatch = dataSource.indexOf("catch (error: CancellationException)")
        val genericCatch = dataSource.indexOf("catch (error: Exception)")

        assertTrue(cancellationCatch >= 0)
        assertTrue(genericCatch > cancellationCatch)
        assertTrue(dataSource.contains("throw error"))
    }

    private fun appFile(relativePath: String): File {
        val moduleFile = File(relativePath)
        return if (moduleFile.exists()) moduleFile else File("app/$relativePath")
    }
}
