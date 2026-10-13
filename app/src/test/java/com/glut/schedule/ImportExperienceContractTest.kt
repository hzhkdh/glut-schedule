package com.glut.schedule

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入体验的页面与重置接线契约。
 *
 * 这三项依赖 Android WebView 与 Activity 组合，JVM 单测不启动真实界面；
 * 因此锁定不可退化的源码接线，同时把纯业务行为留给各自的单元测试。
 */
class ImportExperienceContractTest {

    @Test
    fun importResultAlwaysShowsTheSameFourCategories() {
        val screen = page("DirectLoginScreen.kt")
        val cards = screen.substringAfter("val result = uiState.importResult")
            .substringBefore("// 调课提示")

        listOf("课程", "成绩", "培养计划", "考试").forEach { label ->
            assertTrue("导入结果必须始终展示 $label", cards.contains("\"$label\""))
        }
        // 研究生没有考试门户，显示横杠而不是把“不支持”伪装成 0 条数据。
        assertTrue(cards.contains("count = if (uiState.isGraduate) null else result.examCount"))
    }

    @Test
    fun wechatLoginPrefersNativeQrCardAndKeepsWebFallback() {
        val screen = page("WechatLoginScreen.kt")

        assertTrue("二维码解析成功后必须切换到原生卡片", screen.contains("NativeWechatQrCard"))
        assertTrue("二维码读取失败时必须保留原网页兜底", screen.contains("showWebFallback"))
        assertTrue("自动保存成功需要给出无需截图的明确反馈", screen.contains("无需截图"))
        assertTrue("用户必须能主动重新保存二维码", screen.contains("重新保存二维码"))
    }

    @Test
    fun applicationResetClearsTheAppWebLoginSession() {
        val main = source("MainActivity.kt")
        val reset = main.substringAfter("resetApplicationDataSafely(")
            .substringBefore("showResetConfirm = false")

        assertTrue("重置应用必须接入 WebView 会话清理", reset.contains("clearWebLoginSession"))
        assertTrue("重置说明必须明确包含免密登录会话", main.contains("免密登录会话"))

        val webSession = source("service/academic/WebLoginSessionStore.kt")
        assertTrue(webSession.contains("removeAllCookies"))
        assertTrue(webSession.contains("WebStorage.getInstance().deleteAllData()"))
    }

    private fun page(name: String): String = source("ui/pages/$name")

    private fun source(relative: String): String {
        val module = File("src/main/java/com/glut/schedule/$relative")
        val path = if (module.exists()) module else File("app/src/main/java/com/glut/schedule/$relative")
        // 尚未创建的实现应令契约断言失败，而不是让测试因找不到文件而报错。
        return path.takeIf(File::exists)?.readText().orEmpty()
    }
}
