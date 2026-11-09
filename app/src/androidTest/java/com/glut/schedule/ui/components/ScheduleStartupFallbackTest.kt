package com.glut.schedule.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.glut.schedule.ui.pages.ScheduleLoadingPlaceholder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 验证真实组件的可操作降级，取代只匹配旧版空Box源代码的断言。 */
class ScheduleStartupFallbackTest {
    @get:Rule val compose = createComposeRule()

    @Test fun delayedSettingsStillOfferWorkingMenuEntry() {
        var menuOpened = false
        compose.setContent {
            MaterialTheme { ScheduleLoadingPlaceholder { menuOpened = true } }
        }
        compose.onNodeWithText("正在恢复课表…").assertIsDisplayed()
        compose.onNodeWithText("打开菜单").performClick()
        compose.runOnIdle { assertTrue("恢复缓慢时仍能导航，不能困在空白页面", menuOpened) }
    }

    @Test fun missingCustomBitmapKeepsContentVisibleWithoutDrawingBuiltInArtwork() {
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.DarkGray).testTag("page")) {
                ScheduleBackgroundImage(customBackgroundUri = "file:///anonymous/missing.jpg")
                Text("匿名课表内容", color = Color.White)
            }
        }
        compose.onNodeWithText("匿名课表内容").assertIsDisplayed()
        val image = compose.onNodeWithTag("page").captureToImage().toPixelMap()
        assertEquals("自定义背景缺失时保留纯色底，不先切换另一幅画作", Color.DarkGray, image[image.width / 2, image.height / 2])
    }
}
