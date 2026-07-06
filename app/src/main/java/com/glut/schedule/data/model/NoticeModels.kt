package com.glut.schedule.data.model

import java.time.LocalDate

data class NoticeAttachment(
    val name: String,
    val url: String,
    val type: String
)

data class NoticeInfo(
    val id: String,
    val title: String,
    val content: String,
    val level: String,
    val publishedAt: LocalDate,
    val expiresAt: LocalDate?,
    val url: String,
    val attachments: List<NoticeAttachment> = emptyList()
)

fun hasUnreadNotices(
    currentNoticeIds: Set<String>,
    readNoticeIds: Set<String>
): Boolean {
    return currentNoticeIds.any { it !in readNoticeIds }
}
