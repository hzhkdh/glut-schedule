package com.glut.schedule.service.holiday

import android.util.Log
import com.glut.schedule.data.model.AUTO_DERIVATION_ENABLED
import com.glut.schedule.data.model.adjustmentTargetDates
import com.glut.schedule.data.model.composeAutoDayCopies
import com.glut.schedule.data.repository.ScheduleRepository
import com.glut.schedule.data.settings.ScheduleSettingsStore
import kotlinx.coroutines.flow.first

/**
 * 自动调课规则的重算。**全仓唯一一份实现**，所有调用点共用。
 *
 * 单独抽出来的理由与 `HolidayYearRefresher` 同：这条链路的口径（学期范围取哪个锚点、
 * 让位教务怎么算、抑制怎么过滤）一旦在几处各写一遍，很快就会「首页一套、编辑页另一套」。
 *
 * 契约：**永不抛出**，且**不联网**——它读的全是本地缓存（节假日缓存、对照表缓存、
 * Room 里的教务调课记录）。远端取数由 [refreshMakeupTable] 在用户主动刷新时单独负责。
 */
class AutoDayCopyCoordinator(
    private val settingsStore: ScheduleSettingsStore,
    private val scheduleRepository: ScheduleRepository
) {
    /**
     * 重算指定学期的自动调课规则并落库。
     *
     * 开关关闭 / 学期 id 为空 / 学期不存在或非当前学期时**直接返回**：历史学期是只读快照，
     * 当前学期的规则不得渗进去。开关关闭时的清理由调用方（编辑页拨动开关）显式执行，
     * 这里不写存储——渲染层的闸门（`ScheduleSettingsStore.autoDayCopies` 在关闭时恒为空）
     * 已经保证残留数据不会被画出来，多写两次反而是额外的抖动。
     */
    suspend fun recompose(semesterId: String) {
        runCatching { recomposeOrThrow(semesterId) }
            .onFailure { error ->
                // 契约是永不抛出：它挂在导入 / 刷新课表这些主流程上，自动调课算不出来
                // 绝不能把主流程也拖失败（用户会看到「导入失败」，而真实原因只是这里）。
                Log.w(TAG, "自动调课重算失败：${error.javaClass.simpleName}")
            }
    }

    private suspend fun recomposeOrThrow(semesterId: String) {
        if (semesterId.isBlank()) return
        if (!settingsStore.autoDayCopyEnabled.first()) return

        val semester = scheduleRepository.semesters.first().firstOrNull { it.id == semesterId }
        if (semester == null || !semester.isCurrent) return

        // 学期起止取 settings 而不是学期实体：首页网格的周次锚点就是它，两边锚点不同
        // 会让「补课副本落在哪一周」和用户看到的周次对不上。
        val semesterStart = settingsStore.semesterStartMonday.first()
        val semesterEnd = settingsStore.semesterEndDate.first()

        val holidayCache = settingsStore.holidayCacheByYear.first()
        val calendars = holidayCache.mapNotNull { (year, json) ->
            TimorHolidayCalendarParser.parse(json, year)
        }
        // 跨年份合并后再推导：元旦这类跨年假期，放假日在今年的载荷里、节前补班日
        // 可能落在上一年的载荷里，只读单年会把它当成两个无关的假期。
        val holidays = calendars.flatMap { it.holidays }
        val adjustedWorkdays = calendars.flatMap { it.adjustedWorkdays }.sortedBy { it.date }

        val table = parseMakeupTableJson(settingsStore.holidayMakeupTableJson.first())
        val remoteEntries = table?.entriesFor(semesterId).orEmpty()
        // 对照表**收录了本学期**（学期键存在——哪怕 makeups 是空数组，那也是维护者显式确认
        // 「本学期没有调休」）时推导整学期退场：学校通知说什么就是什么，不再由推导补出别的日子。
        // 只有「表里压根没有这个学期」才算没覆盖，那时才轮到推导兜底。
        val tableCoversSemester = table?.covers(semesterId) == true

        val adjustments = scheduleRepository.adjustmentsForSemester(semesterId)
        val yieldedDates = adjustmentTargetDates(adjustments, semesterStart)

        val suppressedIds = settingsStore.autoDayCopySuppressions.first()[semesterId].orEmpty()
        val manualRuleIds = settingsStore.manualDayCopies.first()[semesterId]
            .orEmpty()
            .mapTo(mutableSetOf()) { it.id }

        val composed = composeAutoDayCopies(
            semesterStartDate = semesterStart,
            semesterEndDate = semesterEnd,
            remoteEntries = remoteEntries,
            adjustedWorkdays = adjustedWorkdays,
            holidays = holidays,
            // 读闸门已把「未公布 / 被污染」的年份从缓存里剔掉，所以缓存非空就等于数据可用。
            holidayCacheAvailable = holidayCache.isNotEmpty(),
            yieldedDates = yieldedDates,
            suppressedIds = suppressedIds,
            manualRuleIds = manualRuleIds,
            derivationEnabled = AUTO_DERIVATION_ENABLED && !tableCoversSemester
        )

        val notices = composed.notices.toMutableList()
        when {
            tableCoversSemester && table != null ->
                notices += "对照表已收录本学期（rev ${table.revision}）：以学校通知为准，推导已停用。"
            table != null ->
                notices += "对照表已同步（rev ${table.revision}）但未覆盖本学期，当前来自补班日规律推导。"
        }

        settingsStore.setAutoDayCopies(semesterId, composed.rules)
        settingsStore.setAutoCopyNotices(semesterId, notices)
    }

    private companion object {
        const val TAG = "AutoDayCopy"
    }
}
