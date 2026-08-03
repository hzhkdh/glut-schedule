package com.glut.schedule

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定曾被旧分支整文件覆盖的 MainActivity 接线，避免相同合并方式再次静默回退 UI。
 */
class MainActivityMergeRegressionTest {
    private val source by lazy {
        val module = File("src/main/java/com/glut/schedule/MainActivity.kt")
        (if (module.exists()) module else
            File("app/src/main/java/com/glut/schedule/MainActivity.kt")).readText()
    }
    private val gallerySource by lazy {
        val module = File("src/main/java/com/glut/schedule/ui/pages/RemoteBackgroundGalleryScreen.kt")
        (if (module.exists()) module else
            File("app/src/main/java/com/glut/schedule/ui/pages/RemoteBackgroundGalleryScreen.kt")).readText()
    }

    @Test
    fun drawerUsesSeventyFivePercentOfScreenWidth() {
        assertTrue(source.contains("Modifier.fillMaxWidth(0.75f)"))
        assertFalse(source.contains("Modifier.fillMaxWidth(0.60f)"))
    }

    @Test
    fun settingsRootScrollsInsteadOfCompressingTheLastCard() {
        val settingsPage = source
            .substringAfter("private fun SettingsPage(")
            .substringBefore("@Composable\nprivate fun CourseColorsPage")

        assertTrue(settingsPage.contains(".verticalScroll(rememberScrollState())"))
        assertTrue(settingsPage.contains("Text(\"重置应用\""))
    }

    @Test
    fun backgroundGalleryNavigationKeepsBothBuiltInBackgroundsReachable() {
        assertTrue(source.contains("BUILT_IN_BACKGROUNDS(\"背景图库\")"))
        assertTrue(source.contains("SettingsSubPage.BUILT_IN_BACKGROUNDS -> remoteBackgroundGalleryViewModel?.let"))
        assertTrue(source.contains("Text(\"背景图库\""))
        assertTrue(gallerySource.contains("BuiltInScheduleBackground.STARRY"))
        assertTrue(gallerySource.contains("BuiltInScheduleBackground.FLOWER"))
    }

    @Test
    fun recroppingRemoteBackgroundKeepsItsRemoteIdentity() {
        assertTrue(source.contains("remoteId = uiState.remoteBackgroundId.takeIf(String::isNotBlank)"))
        assertTrue(source.contains("remoteSha256 = uiState.remoteBackgroundSha256.takeIf(String::isNotBlank)"))
        assertTrue(source.contains("remoteDisplayName = uiState.remoteBackgroundDisplayName.takeIf(String::isNotBlank)"))
    }

    @Test
    fun aboutShareButtonUsesTheRealApkShareCallback() {
        assertTrue(source.contains("onShare = { shareApk(shareContext) }"))
        assertTrue(source.contains("private fun shareApk(context: Context)"))
        assertTrue(source.contains("Intent.createChooser(shareIntent, \"分享到\")"))
    }
}
