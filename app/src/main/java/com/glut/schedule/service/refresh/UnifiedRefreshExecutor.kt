package com.glut.schedule.service.refresh

import android.content.Context
import com.glut.schedule.AppContainer
import com.glut.schedule.BuildConfig
import com.glut.schedule.data.model.*
import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.service.academic.*
import com.glut.schedule.service.campus.CampusImageType
import com.glut.schedule.service.chaoxing.refreshHomeworkForUnified
import com.glut.schedule.service.finance.FinanceFailure
import com.glut.schedule.service.holiday.refreshMakeupTable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.time.LocalDate

class ScheduledRefreshLoginRequiredException : IOException("需要重新登录")
private class StaleRefreshSession : IOException("会话已变化")

/** 每一轮各来源保持独立身份快照；失败只影响当前项，数据依赖结果在本轮内复用。 */
internal class UnifiedRefreshExecutor(private val context: Context, private val c: AppContainer,
    private val stillEnabled: suspend () -> Boolean) {
    private val dependencyCache = RefreshDependencyCache(::executeSingle)
    private var academicInitial: AcademicProfileSession? = null
    private var academicCookie = ""
    private var renewed = false
    // 一轮内分别固定来源身份，后续子项不能切换到中途新登录的账号。
    private var financeInitial: Pair<String, String>? = null
    private var fitnessInitial: Pair<String, String>? = null

    suspend fun execute(task: RefreshTaskId): ScheduledRefreshTaskResult = dependencyCache.execute(task)

    private suspend fun executeSingle(task: RefreshTaskId): ScheduledRefreshTaskResult = try {
        if (!stillEnabled()) throw StaleRefreshSession()
        if (!refreshTaskSupported(task, c.settingsStore.campusType.first())) {
            ScheduledRefreshTaskResult(task, RefreshTaskStatus.UNSUPPORTED, "当前来源不支持后台刷新")
        } else {
            withTimeout(90_000L) {
                when {
                    task.academic -> academicRefreshMutex.withLock { academic(task) }
                    task == RefreshTaskId.CHAOXING -> if (!refreshHomeworkForUnified(context)) error("学习通部分更新失败")
                    task.group == "财务" -> finance(task)
                    task.group == "体测" -> fitness(task)
                    else -> publicData(task)
                }
            }
            ScheduledRefreshTaskResult(task, RefreshTaskStatus.SUCCESS, "已更新")
        }
    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
        ScheduledRefreshTaskResult(task, RefreshTaskStatus.FAILED, "请求超时，保留原有数据")
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: ScheduledRefreshLoginRequiredException) {
        ScheduledRefreshTaskResult(task, RefreshTaskStatus.LOGIN_REQUIRED, "请先在对应页面登录")
    } catch (_: StaleRefreshSession) {
        ScheduledRefreshTaskResult(task, RefreshTaskStatus.CANCELLED, "账号或设置已变化")
    } catch (unavailable: AcademicDataUnavailableException) {
        ScheduledRefreshTaskResult(task, RefreshTaskStatus.FAILED, unavailable.message ?: "教务数据暂不可查看")
    } catch (_: Exception) {
        // 不将远端正文、异常中的签名 URL 或会话数据写入运行记录。
        ScheduledRefreshTaskResult(task, RefreshTaskStatus.FAILED, "更新未完成，保留原有数据")
    }

    private suspend fun currentAcademic(): Boolean {
        val before = academicInitial ?: return false
        val now = c.academicSessionStore.profileSession.first()
        return stillEnabled() && now.studentNo == before.studentNo && now.base == before.base &&
            now.cookie == before.cookie && c.settingsStore.campusType.first() == CampusType.GUILIN
    }

    private suspend fun renewAcademic() {
        if (renewed || !currentAcademic()) throw ScheduledRefreshLoginRequiredException()
        renewed = true
        val initial = academicInitial!!
        val result = renewScheduledSession(c.academicSessionStore.sessionAuthMethod.first(),
            resumeWechat = { c.wechatSsoSessionResumer.resume() },
            resumePassword = password@{
                val credentials = c.credentialStore
                if (credentials.getUsername() != initial.studentNo || !credentials.hasCredentials()) return@password AcademicLoginResult.MissingCredentials
                val user = credentials.getUsername(); val password = credentials.getPassword()
                var response = AcademicLoginHttpClient().login(user, password)
                if (response is AcademicLoginResult.NetworkError || response == AcademicLoginResult.CaptchaOrInteractiveLoginRequired) {
                    if (!currentAcademic()) throw StaleRefreshSession()
                    response = AcademicOALoginClient().login(user, password)
                }
                response
            })
        if (result is AcademicLoginResult.NetworkError) throw IOException("登录网络不可用")
        if (result !is AcademicLoginResult.Success) throw ScheduledRefreshLoginRequiredException()
        if (!currentAcademic()) throw StaleRefreshSession()
        academicCookie = result.cookie
    }

    private suspend fun academic(task: RefreshTaskId) {
        if (academicInitial == null) {
            academicInitial = c.academicSessionStore.profileSession.first()
            if (!supportsScheduledRefresh(c.settingsStore.campusType.first(), academicInitial!!)) throw ScheduledRefreshLoginRequiredException()
            academicCookie = academicInitial!!.cookie
        }
        if (!currentAcademic()) throw StaleRefreshSession()
        if (academicCookie.isBlank()) renewAcademic()
        try { academicRequest(task) } catch (expired: ScheduledRefreshLoginRequiredException) {
            renewAcademic(); academicRequest(task)
        }
    }

    private suspend fun commitAcademic(write: suspend () -> Unit) {
        currentCoroutineContext().ensureActive()
        if (!commitScheduledRefreshIfCurrent(::currentAcademic, write)) throw StaleRefreshSession()
    }

    private suspend fun academicRequest(task: RefreshTaskId) {
        val initial = academicInitial!!
        when (task) {
            RefreshTaskId.SCHEDULE -> {
                val semester = c.scheduleRepository.currentSemester.first() ?: throw IOException("无当前学期")
                val result = c.academicSemesterImportService.importSemester(academicCookie, initial.base, semester, initial.studentNo)
                val data = result.getOrElse {
                    if (it.message.orEmpty().contains("登录")) throw ScheduledRefreshLoginRequiredException()
                    throw it
                }
                academicCookie = data.updatedCookie.ifBlank { academicCookie }
                val direct = runCatching { ApiProbeService.extractAcademicCalendar(c.apiProbeService.probeScheduleEndpoints(cookie = academicCookie, baseUrl = initial.base)) }.getOrNull()
                val calendar = AcademicSemesterCalendarResolver.resolve(semester, LocalDate.now(), direct, data.semesterStartMonday, data.portalMaxWeek)
                commitAcademic {
                    if (c.scheduleRepository.currentSemester.first()?.id != semester.id) throw StaleRefreshSession()
                    c.scheduleRepository.replaceSemesterSchedule(semester, data.courses, data.adjustments,
                        c.scheduleRepository.currentClassPeriods.first(), calendar.startMonday, calendar.endDate, data.portalMaxWeek)
                    c.settingsStore.setSemesterStartMonday(calendar.startMonday); c.settingsStore.setSemesterEndDate(calendar.endDate)
                    c.autoDayCopyCoordinator.recompose(semester.id)
                }
            }
            RefreshTaskId.EXAMS -> {
                val result = c.academicExamService.fetchExamData(academicCookie, c.academicSessionStore.examApiUrl.first(), initial.base)
                val data = result.getOrElse { throw ScheduledRefreshLoginRequiredException() }
                commitAcademic { c.scheduleRepository.replaceExams(data) }
            }
            RefreshTaskId.SCORES -> {
                val data = AcademicRefreshQueries.scores(academicCookie, initial.base)
                commitAcademic { c.scheduleRepository.replaceScores(data) }
            }
            RefreshTaskId.GRADE_EXAMS -> {
                val data = AcademicRefreshQueries.gradeExams(academicCookie, initial.base)
                commitAcademic { c.scheduleRepository.replaceGradeExams(data) }
            }
            RefreshTaskId.STUDY_PLAN -> {
                val data = AcademicRefreshQueries.studyPlan(academicCookie, initial.base)
                commitAcademic { c.scheduleRepository.replaceStudyPlanData(data.first, data.second) }
            }
            RefreshTaskId.SEMESTER_OVERVIEW -> {
                // 课表依赖已带回课程调课；这里刷新公共校历和学校对照表，再重算副本。
                val start = c.settingsStore.semesterStartMonday.first().year
                val end = c.settingsStore.semesterEndDate.first().year
                for (year in start..end) { val json = c.timorHolidayClient.fetchYear(year); commitAcademic { c.settingsStore.setHolidayYearCache(year, json) } }
                refreshMakeupTable(c.makeupTableClient, c.settingsStore::setHolidayMakeupTableJson)
                commitAcademic { c.scheduleRepository.currentSemester.first()?.id?.let { c.autoDayCopyCoordinator.recompose(it) } }
            }
            else -> error("数据依赖未展开")
        }
    }

    suspend fun finish() {
        val initial = academicInitial ?: return
        if (currentAcademic()) c.academicSessionStore.saveProfileCookieIfCurrent(initial.cookie, initial.studentNo, initial.base, academicCookie)
    }

    private suspend fun finance(task: RefreshTaskId) {
        val store = c.financeStore
        val cookie = store.sessionCookie(); val owner = store.credentials().username
        val initial = financeInitial ?: (owner to cookie).also { financeInitial = it }
        if (initial != (owner to cookie)) throw StaleRefreshSession()
        if (cookie.isBlank()) throw ScheduledRefreshLoginRequiredException()
        val module = FinanceModule.valueOf(task.name.removePrefix("FINANCE_"))
        val response = try { c.financeApiService.fetch(module, cookie, 1, 20) }
            catch (error: FinanceFailure.SessionExpired) { throw ScheduledRefreshLoginRequiredException() }
        currentCoroutineContext().ensureActive()
        val payload = response.payload ?: throw IOException("财务数据不可用")
        if (!stillEnabled()) throw StaleRefreshSession()
        if (!store.commitIfCurrent(owner, cookie) {
                store.saveModule(module, CachedFinancePayload(payload, System.currentTimeMillis()))
                if (response.cookie.isNotBlank()) store.saveSessionCookie(response.cookie)
            }) throw StaleRefreshSession()
        financeInitial = owner to response.cookie.ifBlank { cookie }
    }

    private suspend fun fitness(task: RefreshTaskId) {
        val store = c.fitnessStore
        val owner = store.getUsername(); var cookie = store.getSession()
        val initial = fitnessInitial ?: (owner to cookie).also { fitnessInitial = it }
        if (initial != (owner to cookie)) throw StaleRefreshSession()
        if (cookie.isBlank()) throw ScheduledRefreshLoginRequiredException()
        val response = if (task == RefreshTaskId.FITNESS_STANDARD) c.fitnessApiService.getStandard(cookie) else c.fitnessApiService.refresh(cookie)
        currentCoroutineContext().ensureActive()
        if (!response.success) {
            if (response.code.contains("SESSION") || response.code.contains("LOGIN")) throw ScheduledRefreshLoginRequiredException()
            throw IOException("体测数据不可用")
        }
        if (!stillEnabled()) throw StaleRefreshSession()
        val saved = store.commitIfCurrent(owner, cookie) {
            if (task == RefreshTaskId.FITNESS_STANDARD) store.saveStandard(response.standardHtml)
            else store.saveSnapshot(response.currentHtml, response.historyHtml)
            if (response.fitnessCookie.isNotBlank()) store.saveSession(response.fitnessCookie)
        }
        if (!saved) throw StaleRefreshSession()
        cookie = response.fitnessCookie.ifBlank { cookie }
        if (task == RefreshTaskId.FITNESS_HISTORY) {
            for (record in c.fitnessParser.parseHistory(response.historyHtml)) {
                val request = record.detailRequest ?: continue
                val detail = c.fitnessApiService.getHistoryDetail(cookie, request)
                currentCoroutineContext().ensureActive()
                if (!detail.success) throw IOException("历年体测部分失败")
                if (!stillEnabled() || !store.commitIfCurrent(owner, cookie) {
                        store.saveHistoryDetail(record.year, record.term, detail.detailHtml)
                        if (detail.fitnessCookie.isNotBlank()) store.saveSession(detail.fitnessCookie)
                    }) throw StaleRefreshSession()
                cookie = detail.fitnessCookie.ifBlank { cookie }
            }
        }
        fitnessInitial = owner to cookie
    }

    private suspend fun publicData(task: RefreshTaskId) {
        when (task) {
            RefreshTaskId.CAMPUS_INFO -> CampusImageType.entries.filter { it.isRemote }.forEach {
                if (c.campusImageService.fetch(it, true).fromCache) throw IOException("校园信息未刷新")
            }
            RefreshTaskId.NOTICES -> c.noticeChecker.check()?.let { c.settingsStore.setCachedNoticesJson(it.rawJson) } ?: throw IOException("通知不可用")
            RefreshTaskId.BACKGROUND_GALLERY -> c.remoteBackgroundRepository.loadCatalogResult(true).let {
                if (it.source.name == "CACHE_FALLBACK") throw IOException("画廊未刷新")
            }
            RefreshTaskId.UPDATE_CHECK -> c.updateChecker.check(BuildConfig.VERSION_NAME)?.let {
                c.settingsStore.setUpdateAvailable(if (it.isNewer) it.latestVersion else "")
            } ?: throw IOException("版本信息不可用")
            else -> error("未知任务")
        }
    }
}
