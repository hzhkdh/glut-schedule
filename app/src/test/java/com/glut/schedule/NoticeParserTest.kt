package com.glut.schedule

import com.glut.schedule.data.model.hasUnreadNotices
import com.glut.schedule.service.NoticeChecker
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeParserTest {
    @Test
    fun parseNoticesSortsByPublishedDateDescending() {
        val notices = NoticeChecker.parseNotices(
            """
            {
              "schemaVersion": 1,
              "updatedAt": "2026-07-06T12:00:00+08:00",
              "notices": [
                {
                  "id": "2026-07-04-maintenance",
                  "title": "教务系统维护提醒",
                  "content": "维护期间导入可能不可用。",
                  "level": "info",
                  "publishedAt": "2026-07-04",
                  "expiresAt": "",
                  "url": ""
                },
                {
                  "id": "2026-07-05-update",
                  "title": "v0.14.11 更新说明",
                  "content": "修复考试安排显示问题。",
                  "level": "update",
                  "publishedAt": "2026-07-05",
                  "expiresAt": "",
                  "url": ""
                }
              ]
            }
            """.trimIndent(),
            today = LocalDate.of(2026, 7, 6)
        )

        assertEquals(listOf("2026-07-05-update", "2026-07-04-maintenance"), notices.map { it.id })
    }

    @Test
    fun parseNoticesFiltersExpiredAndInvalidItems() {
        val notices = NoticeChecker.parseNotices(
            """
            {
              "notices": [
                {
                  "id": "expired",
                  "title": "已过期",
                  "content": "不应展示",
                  "publishedAt": "2026-06-01",
                  "expiresAt": "2026-06-30"
                },
                {
                  "id": "",
                  "title": "无效通知",
                  "content": "缺少 id",
                  "publishedAt": "2026-07-03"
                },
                {
                  "id": "active",
                  "title": "有效通知",
                  "content": "应展示",
                  "publishedAt": "2026-07-02",
                  "expiresAt": "2026-07-30"
                }
              ]
            }
            """.trimIndent(),
            today = LocalDate.of(2026, 7, 6)
        )

        assertEquals(listOf("active"), notices.map { it.id })
    }

    @Test
    fun invalidJsonReturnsEmptyList() {
        assertEquals(emptyList<String>(), NoticeChecker.parseNotices("{bad json").map { it.id })
    }

    @Test
    fun hasUnreadNoticesComparesCurrentNoticeIdsWithReadIds() {
        assertTrue(hasUnreadNotices(currentNoticeIds = setOf("a", "b"), readNoticeIds = setOf("a")))
        assertFalse(hasUnreadNotices(currentNoticeIds = setOf("a", "b"), readNoticeIds = setOf("a", "b", "old")))
        assertFalse(hasUnreadNotices(currentNoticeIds = emptySet(), readNoticeIds = emptySet()))
    }
}
