package com.glut.schedule.service.refresh

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.glut.schedule.AppContainer
import com.glut.schedule.ScheduleApplication
import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.service.academic.*
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.LocalDate

/** 后台资格必须同时匹配界面来源与实际会话，不能仅相信设置页是否可点。 */
internal fun supportsScheduledRefresh(campus: CampusType, session: AcademicProfileSession): Boolean =
    campus == CampusType.GUILIN && session.studentNo.isNotBlank() &&
        session.base.trimEnd('/') == AcademicLoginResult.DEFAULT_GUILIN_URL

class ScheduledRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val container = (applicationContext as ScheduleApplication).appContainer
        val store = ScheduledRefreshStore(applicationContext)
        var expectedOwner: String? = null
        var retry = false
        var completed = false
        try {
            withTimeout(8 * 60_000L) {
                academicRefreshMutex.withLock {
                    val initial = container.academicSessionStore.profileSession.first()
                    val config = store.config.first()
                    if (!config.enabled || !supportsScheduledRefresh(container.settingsStore.campusType.first(), initial)) {
                        return@withLock
                    }
                    expectedOwner = initial.studentNo + "|" + initial.base
                    store.recordAttempt(System.currentTimeMillis(), expectedOwner)
                    val status = synchronize(container, initial) {
                        val current = container.academicSessionStore.profileSession.first()
                        current.studentNo == initial.studentNo && current.base == initial.base &&
                            current.cookie == initial.cookie && store.config.first().enabled &&
                            container.settingsStore.campusType.first() == CampusType.GUILIN
                    }
                    if (container.academicSessionStore.profileSession.first().let {
                            it.studentNo == initial.studentNo && it.base == initial.base
                        }) {
                        store.recordResult(System.currentTimeMillis(), status == "课表与考试均已更新", status, expectedOwner)
                    }
                }
            }
            completed = true
            Result.success()
        } catch (_: TimeoutCancellationException) {
            completed = true
            store.recordResult(System.currentTimeMillis(), false, "同步超时，保留已有数据", expectedOwner)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 错误信息不携带远端原文、请求 URL、Cookie 或账号信息。
            completed = true
            retry = runAttemptCount < 2
            store.recordResult(System.currentTimeMillis(), false,
                if (retry) "网络或学校服务暂不可用，稍后重试" else "本次同步未完成，保留原有数据", expectedOwner)
            if (retry) Result.retry() else Result.success()
        } finally {
            // 暂时失败使用 WorkManager 退避；正常结束按本地时刻计算下一次，不补跑所有错过时刻。
            if (completed && !retry) {
                val session = container.academicSessionStore.profileSession.first()
                reconcileScheduledRefresh(applicationContext, store.config.first(),
                    supportsScheduledRefresh(container.settingsStore.campusType.first(), session))
            }
        }
    }

    private suspend fun synchronize(
        container: AppContainer,
        initial: AcademicProfileSession,
        isCurrent: suspend () -> Boolean
    ): String {
        val repo = container.scheduleRepository
        val semester = repo.currentSemester.first() ?: return "请先导入当前学期课表"
        if (semester.campus != CampusType.GUILIN) return "当前来源不支持后台刷新"
        var cookie = initial.cookie
        suspend fun import() = container.academicSemesterImportService.importSemester(
            cookie = cookie, baseUrl = initial.base, semester = semester, studentIdFallback = initial.studentNo)
        var payload = if (cookie.isBlank()) null else import().getOrNull()
        if (payload == null) {
            if (!isCurrent()) return "账号或设置已变化，本次已取消"
            // 微信只复用统一认证会话，密码只使用本账号凭据，绝不跨认证来源回退。
            val login = renewScheduledSession(
                container.academicSessionStore.sessionAuthMethod.first(),
                resumeWechat = { container.wechatSsoSessionResumer.resume() },
                resumePassword = password@{
                    val credentials = container.credentialStore
                    if (!credentials.hasCredentials() || credentials.getUsername() != initial.studentNo) {
                        return@password AcademicLoginResult.MissingCredentials
                    }
                    val username = credentials.getUsername()
                    val password = credentials.getPassword()
                    var result = AcademicLoginHttpClient().login(username, password)
                    if (result is AcademicLoginResult.NetworkError || result == AcademicLoginResult.CaptchaOrInteractiveLoginRequired) {
                        if (!isCurrent()) return@password AcademicLoginResult.CaptchaOrInteractiveLoginRequired
                        result = AcademicOALoginClient().login(username, password)
                    }
                    result
                }
            )
            if (login == AcademicLoginResult.MissingCredentials) return "缺少本账号的已保存密码，请重新登录并记住密码"
            if (login is AcademicLoginResult.NetworkError) error("暂时不可用")
            if (login !is AcademicLoginResult.Success) return "登录状态需要更新，请打开 App 重新登录"
            cookie = login.cookie
            if (!isCurrent()) return "账号或设置已变化，本次已取消"
            payload = import().getOrThrow()
        }
        val data = payload
        // 导入过程可能轮换登录态，后续请求必须使用最终会话。
        cookie = data.updatedCookie.ifBlank { cookie }
        val directCalendar = runCatching {
            ApiProbeService.extractAcademicCalendar(container.apiProbeService.probeScheduleEndpoints(cookie = cookie, baseUrl = initial.base))
        }.getOrNull()
        currentCoroutineContext().ensureActive()
        val calendar = AcademicSemesterCalendarResolver.resolve(semester, LocalDate.now(), directCalendar,
            data.semesterStartMonday, data.portalMaxWeek)
        if (!commitScheduledRefreshIfCurrent(isCurrent) {
                repo.replaceSemesterSchedule(semester, data.courses, data.adjustments,
                    repo.currentClassPeriods.first(), calendar.startMonday, calendar.endDate, data.portalMaxWeek)
                container.settingsStore.setSemesterStartMonday(calendar.startMonday)
                container.settingsStore.setSemesterEndDate(calendar.endDate)
                container.autoDayCopyCoordinator.recompose(semester.id)
            }) return "账号或设置已变化，本次已取消"
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return "账号或设置已变化，本次已取消"
        val exams = container.academicExamService.fetchExamData(cookie,
            container.academicSessionStore.examApiUrl.first(), initial.base)
        currentCoroutineContext().ensureActive()
        if (exams.isFailure) {
            container.academicSessionStore.saveProfileCookieIfCurrent(initial.cookie, initial.studentNo, initial.base, cookie)
            return "课表已更新，考试同步未完成"
        }
        if (!commitScheduledRefreshIfCurrent(isCurrent) { repo.replaceExams(exams.getOrThrow()) }) {
            return "账号或设置已变化，本次已取消"
        }
        // 全程请求沿用本地 Cookie；仅在原会话仍匹配时才交还已续期的 Cookie。
        container.academicSessionStore.saveProfileCookieIfCurrent(initial.cookie, initial.studentNo, initial.base, cookie)
        return "课表与考试均已更新"
    }
}
