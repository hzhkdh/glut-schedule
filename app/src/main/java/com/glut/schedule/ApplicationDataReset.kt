package com.glut.schedule

/**
 * 先等待应用级后台下载完全退出，再清理账号数据。
 * 该顺序是硬约束：否则旧任务可能在数据库清空后重新写回上一账号的课表。
 */
internal suspend fun resetApplicationDataSafely(
    cancelActiveDownloads: suspend () -> Unit,
    clearData: suspend () -> Unit
) {
    cancelActiveDownloads()
    clearData()
}
