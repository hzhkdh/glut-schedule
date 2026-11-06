package com.glut.schedule.ui.requirements

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 从详情内部起滑也必须推动外层列表，不能困在 WebView 内。 */
class HomeworkRequirementsViewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun shortRequirementsDoNotReserveBlankSpace() {
        compose.setContent {
            HomeworkRequirementsView("<p>匿名短要求</p>", Modifier.fillMaxWidth().testTag("requirements"))
        }
        compose.waitUntil(15_000) {
            compose.onNodeWithTag("requirements").fetchSemanticsNode().size.height > 4
        }
        val height = compose.onNodeWithTag("requirements").fetchSemanticsNode().size.height
        assertTrue("短要求残留固定高度空白", height <= with(compose.density) { 120.dp.roundToPx() })
    }

    @Test fun wideningRequirementsReducesContentHeight() {
        val width = mutableStateOf(160.dp)
        compose.setContent {
            Column(Modifier.height(400.dp).verticalScroll(ScrollState(0))) {
                HomeworkRequirementsView("<p>匿名长文本要求，用于验证重新换行后的正文高度。</p>".repeat(8),
                    Modifier.width(width.value).testTag("requirements"))
            }
        }
        compose.waitUntil(15_000) { compose.onNodeWithTag("requirements").fetchSemanticsNode().size.height > 200 }
        val previous = compose.onNodeWithTag("requirements").fetchSemanticsNode().size.height
        compose.runOnIdle { width.value = 300.dp }
        compose.waitUntil(15_000) { compose.onNodeWithTag("requirements").fetchSemanticsNode().size.height < previous }
    }

    @Test fun draggingRequirementsScrollsOuterList() {
        val scroll = ScrollState(0)
        compose.setContent {
            Column(Modifier.height(400.dp).verticalScroll(scroll)) {
                HomeworkRequirementsView(
                    "<p>匿名要求</p>".repeat(50),
                    Modifier.fillMaxWidth().testTag("requirements")
                )
                Text("列表末尾")
            }
        }
        // WebView 排版异步完成，等待内容把外层撑开。
        compose.waitUntil(15_000) { scroll.maxValue > 500 }
        compose.onNodeWithTag("requirements").performTouchInput { swipeUp() }
        compose.runOnIdle { assertTrue("详情吞掉了纵向手势", scroll.value > 0) }
    }
}
