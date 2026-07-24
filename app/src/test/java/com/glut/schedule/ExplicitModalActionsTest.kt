package com.glut.schedule

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplicitModalActionsTest {
    @Test
    fun updateAndNoticeDialogsRequireExplicitCardActions() {
        val source = mainActivitySource()

        assertTrue(source.contains("private val EXPLICIT_ACTION_DIALOG_PROPERTIES = DialogProperties("))
        assertTrue(source.contains("dismissOnBackPress = false"))
        assertTrue(source.contains("dismissOnClickOutside = false"))
        assertEquals(
            5,
            Regex("properties = EXPLICIT_ACTION_DIALOG_PROPERTIES").findAll(source).count()
        )
        assertEquals(5, Regex("""onDismissRequest = \{ \}""").findAll(source).count())
    }

    @Test
    fun explicitButtonsKeepTheirCloseAndNavigationCallbacks() {
        val source = mainActivitySource()

        assertTrue(source.contains("TextButton(onClick = onOpenNotices)"))
        assertTrue(source.contains("TextButton(onClick = onDismiss)"))
        assertTrue(source.contains("TextButton(onClick = { startDownload(state.info) })"))
        assertTrue(source.contains("Text(\"取消下载\")"))
        assertTrue(source.contains("appUpdater.installApk(state.apkFile)"))
    }

    @Test
    fun cancelDownloadStopsTheTrackedJobWithoutShowingFailure() {
        val source = mainActivitySource()

        assertTrue(source.contains("var downloadJob by remember { mutableStateOf<Job?>(null) }"))
        assertTrue(source.contains("downloadJob?.cancel()"))
        assertTrue(source.contains("catch (error: CancellationException)"))
        assertTrue(source.contains("throw error"))
    }

    private fun mainActivitySource(): String {
        val module = File("src/main/java/com/glut/schedule/MainActivity.kt")
        return (if (module.exists()) module else
            File("app/src/main/java/com/glut/schedule/MainActivity.kt")).readText()
    }
}
