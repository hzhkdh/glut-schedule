package com.glut.schedule.service.refresh

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 手动与定时刷新共用网络锁，避免同时续期令教务会话互相失效。 */
internal val academicRefreshMutex = Mutex()

/** 账号切换的清库与后台写库互斥，防止清理后旧请求重新填入缓存。 */
internal val academicCacheWriteMutex = Mutex()

internal suspend fun commitScheduledRefreshIfCurrent(
    isCurrent: suspend () -> Boolean,
    commit: suspend () -> Unit
): Boolean = academicCacheWriteMutex.withLock {
    if (!isCurrent()) return@withLock false
    commit()
    true
}

/** 后台续期沿用登录时的认证来源；微信失败不能擅自调用保存的密码。 */
internal suspend fun renewScheduledSession(
    method: com.glut.schedule.service.academic.AcademicSessionAuthMethod,
    resumeWechat: suspend () -> com.glut.schedule.service.academic.AcademicLoginResult,
    resumePassword: suspend () -> com.glut.schedule.service.academic.AcademicLoginResult
): com.glut.schedule.service.academic.AcademicLoginResult =
    if (method == com.glut.schedule.service.academic.AcademicSessionAuthMethod.WECHAT_SSO) resumeWechat() else resumePassword()
