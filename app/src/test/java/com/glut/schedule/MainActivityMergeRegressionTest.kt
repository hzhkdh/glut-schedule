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
    fun builtInBackgroundNavigationRemainsReachable() {
        assertTrue(source.contains("BUILT_IN_BACKGROUNDS(\"内置背景\")"))
        assertTrue(source.contains("SettingsSubPage.BUILT_IN_BACKGROUNDS -> BuiltInBackgroundsPage("))
        assertTrue(source.contains("Text(\"内置背景\""))
        assertTrue(source.contains("BuiltInScheduleBackground.STARRY"))
        assertTrue(source.contains("BuiltInScheduleBackground.FLOWER"))
    }

    @Test
    fun aboutShareButtonUsesTheRealApkShareCallback() {
        assertTrue(source.contains("onShare = { shareApk(shareContext) }"))
        assertTrue(source.contains("private fun shareApk(context: Context)"))
        assertTrue(source.contains("Intent.createChooser(shareIntent, \"分享到\")"))
    }
}
