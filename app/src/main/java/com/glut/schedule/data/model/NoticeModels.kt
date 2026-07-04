package com.glut.schedule.data.model

import java.time.LocalDate

data class NoticeInfo(
    val id: String,
    val title: String,
    val content: String,
    val level: String,
    val publishedAt: LocalDate,
    val expiresAt: LocalDate?,
    val url: String
)

fun hasUnreadNotices(
    currentNoticeIds: Set<String>,
    readNoticeIds: Set<String>
): Boolean {
    return currentNoticeIds.any { it !in readNoticeIds }
}
