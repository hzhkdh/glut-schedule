package com.glut.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DrawerGreetingIntegrationContractTest {
    @Test
    fun disabledGreetingUsesBrandTextColumnWithoutChangingEnabledLayout() {
        assertEquals(
            DrawerHeaderLayoutMode.BRAND_TEXT_COLUMN,
            drawerHeaderLayoutMode(greetingEnabled = false)
        )
        assertEquals(
            DrawerHeaderLayoutMode.FULL_WIDTH_GREETING,
            drawerHeaderLayoutMode(greetingEnabled = true)
        )
    }

    @Test
    fun disabledGreetingAlignsLogoWithBrandTitle() {
        val mainActivity = source("app/src/main/java/com/glut/schedule/MainActivity.kt")
        val disabledHeader = mainActivity
            .substringAfter("DrawerHeaderLayoutMode.BRAND_TEXT_COLUMN -> Row(")
            .substringBefore("Image(")

        // 微信端的品牌图标与大标题同排，因此关闭问候语分支不能相对两行文字整体垂直居中。
        assertTrue(disabledHeader.contains("verticalAlignment = Alignment.Top"))
    }

    @Test
    fun appStartupAndAcademicImportFeedGreetingData() {
        val application = source("app/src/main/java/com/glut/schedule/ScheduleApplication.kt")
        val login = source("app/src/main/java/com/glut/schedule/ui/pages/DirectLoginViewModel.kt")

        assertTrue(application.contains("greetingTemplateRepository.initializeAndRefresh()"))
        assertTrue(login.contains("AcademicSemesterParser.parseStudentName(enrollmentHtml)"))
        assertTrue(login.contains("sessionStore.saveAuthenticatedStudent(studentNumber"))
    }

    @Test
    fun drawerAndSettingsExposeAccessibleGreetingBehavior() {
        val main = source("app/src/main/java/com/glut/schedule/MainActivity.kt")

        assertTrue(main.contains("Text(\"问候语\""))
        assertTrue(main.contains("snapshotFlow { drawerState.targetValue }"))
        assertTrue(main.contains("TypewriterGreetingText("))
        assertTrue(main.contains("ValueAnimator.areAnimatorsEnabled()"))
        assertTrue(main.contains("clearAndSetSemantics"))
        assertTrue(main.contains("contentDescription = fullText"))
        assertTrue(main.contains("maxLines = 2"))
        assertTrue(main.contains(".height(40.dp)"))
    }

    @Test
    fun directLoginDoesNotShowPersistentHttpWarning() {
        val loginScreen = source("app/src/main/java/com/glut/schedule/ui/pages/DirectLoginScreen.kt")

        assertTrue(!loginScreen.contains("安全提示："))
    }

    private fun source(relativePath: String): String {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "app").isDirectory }
        return File(root, relativePath).readText()
    }
}
