package com.glut.schedule

import com.glut.schedule.data.model.NoticeInfo
import com.glut.schedule.ui.NoticeLoadState
import com.glut.schedule.ui.resolveNoticeLoadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.LocalDate

class NoticeLoadStateTest {

    private val cachedNotice = notice("cached", "缓存通知")
    private val remoteNotice = notice("remote", "远程通知")

    @Test
    fun validCacheRemainsVisibleWhileRefreshIsRunning() {
        val state = resolveNoticeLoadState(
            cachedNotices = listOf(cachedNotice),
            refreshedNotices = null,
            refreshFinished = false,
            refreshFailed = false
        )

        assertEquals(listOf(cachedNotice), (state as NoticeLoadState.Content).notices)
    }

    @Test
    fun refreshFailureKeepsValidUpgradeCache() {
        val state = resolveNoticeLoadState(
            cachedNotices = listOf(cachedNotice),
            refreshedNotices = null,
            refreshFinished = true,
            refreshFailed = true
        )

        assertEquals(listOf(cachedNotice), (state as NoticeLoadState.Content).notices)
    }

    @Test
    fun noCacheAndFailedRefreshShowsErrorInsteadOfEmpty() {
        val state = resolveNoticeLoadState(
            cachedNotices = emptyList(),
            refreshedNotices = null,
            refreshFinished = true,
            refreshFailed = true
        )

        assertSame(NoticeLoadState.Error, state)
    }

    @Test
    fun successfulEmptyResponseIsTheOnlyEmptyState() {
        val state = resolveNoticeLoadState(
            cachedNotices = emptyList(),
            refreshedNotices = emptyList(),
            refreshFinished = true,
            refreshFailed = false
        )

        assertSame(NoticeLoadState.Empty, state)
    }

    @Test
    fun successfulRefreshReplacesOlderCache() {
        val state = resolveNoticeLoadState(
            cachedNotices = listOf(cachedNotice),
            refreshedNotices = listOf(remoteNotice),
            refreshFinished = true,
            refreshFailed = false
        )

        assertEquals(listOf(remoteNotice), (state as NoticeLoadState.Content).notices)
    }

    private fun notice(id: String, title: String) = NoticeInfo(
        id = id,
        title = title,
        content = "",
        level = "info",
        publishedAt = LocalDate.of(2026, 7, 26),
        expiresAt = null,
        url = ""
    )
}
