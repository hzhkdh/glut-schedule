package com.glut.schedule

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class ScheduleWidgetPreviewContractTest {
    @Test
    fun mainActivityUsesSingleTaskLaunchModeToAvoidWidgetStacking() {
        val manifest = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(appFile("src/main/AndroidManifest.xml"))
        val activities = manifest.getElementsByTagName("activity")
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val mainActivity = (0 until activities.length)
            .map { activities.item(it) }
            .first { it.attributes.getNamedItemNS(androidNamespace, "name")?.nodeValue == ".MainActivity" }

        // 小组件与桌面图标反复打开应用时必须复用同一实例，避免后台残留多套 Compose/ViewModel。
        assertEquals(
            "singleTask",
            mainActivity.attributes.getNamedItemNS(androidNamespace, "launchMode")?.nodeValue
        )
    }

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

        assertTrue(gradle.contains("androidx.work:work-runtime-ktx:2.10.0"))
        assertTrue(worker.isFile)
        val workerSource = worker.readText()
        assertTrue(workerSource.contains("enqueueUniqueWork"))
        assertTrue(workerSource.contains("ExistingWorkPolicy.REPLACE"))
        assertTrue(workerSource.contains("setInitialDelay"))
        assertTrue(workerSource.contains("ScheduleWidgetRefreshPlanner.nextRefreshAt"))
        assertTrue(workerSource.contains("runAttemptCount + 1 < MAX_RETRY_ATTEMPTS"))
        // 「没有小组件就停链」这个判断只在**显式重排**（REPLACE，即 updateAll 之后）时成立。
        // worker 自我续期（APPEND_OR_REPLACE）时不能这么判：那一轮刚渲染过，小组件显然存在，
        // 而 getGlanceIds 在进程刚重启时可能瞬时返回空——一判就永久断链，当天再没有事件刷新。
        assertTrue(workerSource.contains("policy == ExistingWorkPolicy.REPLACE && !hasInstalledWidgets(appContext)"))
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
    fun everyCourseRegionUsesAStableScrollableCollection() {
        val widgets = appFile(
            "src/main/java/com/glut/schedule/widget/ScheduleWidgets.kt"
        ).readText()
        val models = appFile(
            "src/main/java/com/glut/schedule/widget/ScheduleWidgetModels.kt"
        ).readText()
        val compact = widgets
            .substringAfter("private fun CompactTodayContent")
            .substringBefore("private fun TodayTomorrowContent")
        val recent = widgets
            .substringAfter("private fun TodayTomorrowContent")
            .substringBefore("private fun ColorTimelineContent")
        val timeline = widgets
            .substringAfter("private fun ColorTimelineContent")
            .substringBefore("private fun CompactWidgetHeader")

        assertTrue(compact.contains("CompactWidgetHeader(snapshot)"))
        assertTrue(compact.contains("ScrollableCourseList("))
        assertTrue(compact.contains("snapshot.todayCourses"))
        assertEquals(2, "ScrollableDayColumn".toRegex().findAll(recent).count())
        assertTrue(timeline.contains("ScrollableTimeline("))
        assertTrue(timeline.contains("snapshot.todayCourses"))
        assertEquals(2, "LazyColumn\\(".toRegex().findAll(widgets).count())
        assertTrue(widgets.contains("itemId = { course -> course.stableId }"))
        assertTrue(models.contains("val stableId: Long"))
        assertFalse(widgets.contains("take(2)"))
        assertFalse(widgets.contains("limit = 2"))
        assertFalse(compact.lineSequence().any { it.trimStart().startsWith("WidgetHeader(snapshot") })
        assertTrue(widgets.contains("WidgetHeader(snapshot, \"近期课程\")"))
        assertTrue(widgets.contains("WidgetHeader(snapshot, \"日视图\")"))
    }

    @Test
    fun noCourseWidgetsDoNotOfferANextCourseReminder() {
        val widgets = appFile(
            "src/main/java/com/glut/schedule/widget/ScheduleWidgets.kt"
        ).readText()
        val models = appFile(
            "src/main/java/com/glut/schedule/widget/ScheduleWidgetModels.kt"
        ).readText()

        assertFalse(widgets.contains("nextCourse"))
        assertFalse(models.contains("nextCourse"))
        assertTrue(widgets.contains("private fun NoCourseContent()"))
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
