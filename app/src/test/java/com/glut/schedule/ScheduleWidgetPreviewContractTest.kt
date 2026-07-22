package com.glut.schedule

import java.io.File
import org.junit.Assert.assertEquals
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
    fun widgetsAlwaysObserveTheCurrentSemesterInsteadOfTheViewedHistory() {
        val application = appFile("src/main/java/com/glut/schedule/ScheduleApplication.kt").readText()
        val dataSource = appFile(
            "src/main/java/com/glut/schedule/widget/ScheduleWidgetDataSource.kt"
        ).readText()

        assertTrue(application.contains("scheduleRepository.currentCourses"))
        assertTrue(dataSource.contains("scheduleRepository.currentCourses.first()"))
        assertFalse(dataSource.contains("scheduleRepository.courses.first()"))
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

    @Test
    fun eventDrivenRefreshUsesUniqueOneTimeWorkAndSchedulesTheNextBoundary() {
        val gradle = appFile("build.gradle.kts").readText()
        val worker = appFile(
            "src/main/java/com/glut/schedule/widget/ScheduleWidgetRefreshWorker.kt"
        )
        val updater = appFile(
            "src/main/java/com/glut/schedule/widget/ScheduleWidgetUpdater.kt"
        ).readText()

        assertTrue(gradle.contains("androidx.work:work-runtime-ktx:2.7.1"))
        assertTrue(worker.isFile)
        val workerSource = worker.readText()
        assertTrue(workerSource.contains("enqueueUniqueWork"))
        assertTrue(workerSource.contains("ExistingWorkPolicy.REPLACE"))
        assertTrue(workerSource.contains("setInitialDelay"))
        assertTrue(workerSource.contains("ScheduleWidgetRefreshPlanner.nextRefreshAt"))
        assertTrue(workerSource.contains("runAttemptCount + 1 < MAX_RETRY_ATTEMPTS"))
        assertTrue(workerSource.contains("if (policy == ExistingWorkPolicy.REPLACE)"))
        assertTrue(updater.contains("ScheduleWidgetRefreshScheduler.scheduleNext"))
    }

    @Test
    fun everyWidgetRequestsAnImmediateRefreshWhenFirstEnabled() {
        val receivers = appFile(
            "src/main/java/com/glut/schedule/widget/ScheduleWidgetReceivers.kt"
        ).readText()

        assertEquals(3, "override fun onEnabled".toRegex().findAll(receivers).count())
        assertEquals(3, "ScheduleWidgetRefreshScheduler.requestImmediate".toRegex().findAll(receivers).count())
    }

    @Test
    fun widgetHeaderOffersARealManualRefreshAction() {
        val widgets = appFile(
            "src/main/java/com/glut/schedule/widget/ScheduleWidgets.kt"
        ).readText()

        assertTrue(widgets.contains("class RefreshScheduleWidgetsAction : ActionCallback"))
        assertTrue(widgets.contains("actionRunCallback<RefreshScheduleWidgetsAction>()"))
        assertTrue(widgets.contains("ScheduleWidgetUpdater.updateAll(context)"))
        assertTrue(widgets.contains("\"刷新\""))
    }

    @Test
    fun compactWidgetUsesItsOwnTwoRowHeaderAndOnlyTwoCourses() {
        val widgets = appFile(
            "src/main/java/com/glut/schedule/widget/ScheduleWidgets.kt"
        ).readText()
        val compact = widgets
            .substringAfter("private fun CompactTodayContent")
            .substringBefore("private fun TodayTomorrowContent")
        val wideWidgets = widgets.substringAfter("private fun TodayTomorrowContent")

        assertTrue(compact.contains("CompactWidgetHeader(snapshot)"))
        assertTrue(compact.contains("CourseList(snapshot.todayCourses, limit = 2)"))
        assertFalse(compact.lineSequence().any { it.trimStart().startsWith("WidgetHeader(snapshot") })
        assertTrue(wideWidgets.contains("WidgetHeader(snapshot, \"近期课程\")"))
        assertTrue(wideWidgets.contains("WidgetHeader(snapshot, \"日视图\")"))
    }

    @Test
    fun compactScalablePreviewMatchesTheRealTwoRowHeader() {
        val preview = appFile(
            "src/main/res/layout/widget_preview_compact_today.xml"
        ).readText()

        assertTrue(preview.contains("android:text=\"刷新\""))
        assertTrue(preview.contains("android:text=\"3月16日 · 第 2 周 · 周一\""))
        assertEquals(2, "widget_preview_(pink|blue)_bar".toRegex().findAll(preview).count())
    }

    private fun appFile(relativePath: String): File {
        val moduleFile = File(relativePath)
        return if (moduleFile.exists()) moduleFile else File("app/$relativePath")
    }
}
