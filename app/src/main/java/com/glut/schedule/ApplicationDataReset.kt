package com.glut.schedule

/**
 * 先等待应用级后台下载完全退出，再清理账号数据。
 * 该顺序是硬约束：否则旧任务可能在数据库清空后重新写回上一账号的课表。
 */
internal suspend fun resetApplicationDataSafely(
    cancelActiveDownloads: suspend () -> Unit,
    clearData: suspend () -> Unit,
    /** 清除 App 内 WebView 的统一身份认证会话，避免“重置后仍免密登录”。 */
    clearWebLoginSession: suspend () -> Unit = {}
) {
    cancelActiveDownloads()
    clearData()
    clearWebLoginSession()
}
