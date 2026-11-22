package com.glut.schedule.ui.pages

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.glut.schedule.service.campus.SchoolHistoryEvent
import com.glut.schedule.service.campus.SchoolHistoryPeriod
import com.glut.schedule.service.campus.SchoolOverviewDocument
import com.glut.schedule.service.campus.SchoolOverviewTopic
import com.glut.schedule.service.campus.SchoolSong
import com.glut.schedule.service.campus.SchoolSongMottoSection
import com.glut.schedule.service.campus.SchoolSongNote
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SchoolOverviewScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun historyHasNoYearJumpAndStacksOnlyWhenTheDateCrowdsTheText() {
        val width = mutableStateOf(360.dp)
        compose.setContent {
            Box(Modifier.width(width.value)) {
                SchoolOverviewTopicContent(SchoolOverviewTopic.HISTORY, history(), SchoolOverviewLoadState.READY, {}, { null })
            }
        }
        compose.onAllNodesWithText("1956").assertCountEquals(0)
        val date = compose.onNodeWithText("1956年6月25日")
        val event = compose.onNodeWithText("学校创办")
        assertTrue(date.fetchSemanticsNode().boundsInRoot.right < event.fetchSemanticsNode().boundsInRoot.left)

        compose.runOnIdle { width.value = 238.dp }
        assertTrue(date.fetchSemanticsNode().boundsInRoot.bottom <= event.fetchSemanticsNode().boundsInRoot.top)
    }

    @Test fun diagramCanRetryAndCloseBackToTheTimeline() {
        val attempts = AtomicInteger()
        val png = ByteArrayOutputStream().use { stream ->
            Bitmap.createBitmap(48, 36, Bitmap.Config.ARGB_8888).useBitmap { it.compress(Bitmap.CompressFormat.PNG, 100, stream) }
            stream.toByteArray()
        }
        compose.setContent {
            SchoolOverviewTopicContent(
                SchoolOverviewTopic.HISTORY,
                history(diagram = "https://example.invalid/diagram"),
                SchoolOverviewLoadState.READY,
                {},
                { if (attempts.incrementAndGet() == 1) null else png }
            )
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("沿革图暂时无法加载").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("重试").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("查看沿革图").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("查看沿革图").performClick()
        compose.onNodeWithContentDescription("关闭沿革图").assertIsDisplayed().performClick()
        compose.onNodeWithText("学校创办").assertIsDisplayed()
    }

    @Test fun songTextTabsCanBeReadBeforeLoadingVideo() {
        val document = SchoolOverviewDocument(
            fetchedAt = 1L,
            sources = emptyList(),
            song = SchoolSong(
                title = "校歌",
                videoUrl = "https://example.invalid/song.mp4",
                lyrics = listOf("第一段歌词"),
                notes = listOf(SchoolSongNote("校园", "注释正文")),
                meaning = listOf("大意正文"),
                mottoSections = listOf(SchoolSongMottoSection("校训标题", listOf("释义正文")))
            )
        )
        compose.setContent {
            SchoolOverviewTopicContent(SchoolOverviewTopic.SONG, document, SchoolOverviewLoadState.READY, {}, { null })
        }
        compose.onNodeWithText("第一段歌词").assertIsDisplayed()
        compose.onNodeWithText("注释").performClick().assertIsSelected()
        compose.onNodeWithText("注释正文").assertIsDisplayed()
        compose.onNodeWithText("歌词大意").performClick().assertIsSelected()
        compose.onNodeWithText("大意正文").assertIsDisplayed()
        compose.onNodeWithText("校训释义").performClick().assertIsSelected()
        compose.onNodeWithText("释义正文").assertIsDisplayed()
        compose.onNodeWithContentDescription("播放校歌视频").assertIsDisplayed()
    }

    private fun history(diagram: String? = null) = SchoolOverviewDocument(
        fetchedAt = 1L,
        sources = emptyList(),
        periods = listOf(SchoolHistoryPeriod("founding", "创办", "1956–2024", listOf(SchoolHistoryEvent("1956年6月25日", "学校创办")))),
        diagramUrl = diagram
    )

    private inline fun Bitmap.useBitmap(block: (Bitmap) -> Unit) {
        try { block(this) } finally { recycle() }
    }
}
