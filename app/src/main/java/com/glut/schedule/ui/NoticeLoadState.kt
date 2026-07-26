package com.glut.schedule.ui

import com.glut.schedule.data.model.NoticeInfo

sealed interface NoticeLoadState {
    data object Loading : NoticeLoadState
    data class Content(val notices: List<NoticeInfo>) : NoticeLoadState
    data object Empty : NoticeLoadState
    data object Error : NoticeLoadState
}

/**
 * 缓存优先推导通知页面状态。覆盖升级遗留的有效缓存必须在刷新期间及刷新失败后继续可见。
 */
fun resolveNoticeLoadState(
    cachedNotices: List<NoticeInfo>,
    refreshedNotices: List<NoticeInfo>?,
    refreshFinished: Boolean,
    refreshFailed: Boolean
): NoticeLoadState {
    if (refreshedNotices != null) {
        return if (refreshedNotices.isEmpty()) {
            NoticeLoadState.Empty
        } else {
            NoticeLoadState.Content(refreshedNotices)
        }
    }
    if (cachedNotices.isNotEmpty()) {
        return NoticeLoadState.Content(cachedNotices)
    }
    if (!refreshFinished) {
        return NoticeLoadState.Loading
    }
    return if (refreshFailed) NoticeLoadState.Error else NoticeLoadState.Empty
}
