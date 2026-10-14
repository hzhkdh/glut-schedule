package com.glut.schedule.ui.pages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glut.schedule.data.model.AcademicSemester
import com.glut.schedule.data.model.AutoCopyOrigin
import com.glut.schedule.data.model.AutoDayCopyRule
import com.glut.schedule.data.model.ManualDayCopyRule
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.countManualCopySourceBlocks
import com.glut.schedule.data.model.hiddenCardRuleHits
import com.glut.schedule.data.model.hiddenRuleLabel
import com.glut.schedule.data.model.validateManualDayCopy
import com.glut.schedule.data.repository.ScheduleRepository
import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.data.settings.ScheduleSettingsStore
import com.glut.schedule.data.settings.capabilitiesFor
import com.glut.schedule.service.holiday.AutoDayCopyCoordinator
import com.glut.schedule.service.holiday.MakeupTableClient
import com.glut.schedule.service.holiday.TimorHolidayCalendarParser
import com.glut.schedule.service.holiday.parseMakeupTableJson
import com.glut.schedule.service.holiday.refreshMakeupTable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 「调休调课」编辑页状态。
 *
 * 这里用**当前学期**而不是「正在查看的学期」：调休规则只对当前学期生效，
 * 用户在看历史学期时进来编辑的仍然是当前学期的规则（与小程序同语义）。
 */
data class HolidayAdjustmentsUiState(
    val isReady: Boolean = false,
    val semesterId: String = "",
    val semesterLabel: String = "",
    val semesterStartDate: LocalDate? = null,
    val semesterEndDate: LocalDate? = null,
    val courses: List<ScheduleCourse> = emptyList(),
    val rules: List<ManualDayCopyRule> = emptyList(),
    /** 法定放假日：目标日落在其中时需要二次确认。 */
    val holidayDates: Set<LocalDate> = emptySet(),
    /** 首页是否显示周末列：目标日是周末而首页未开启周末时，追加的课程在首页看不到。 */
    val showWeekend: Boolean = false,
    /** 长按卡片手动删掉的卡片。与调休规则同属「我手动改动了课表」的叠加层。 */
    val hiddenItems: List<HiddenCardItem> = emptyList(),
    /** 「自动调课」开关。关闭时下面这些自动相关的字段全部为空，页面回到纯手动形态。 */
    val autoDayCopyEnabled: Boolean = false,
    val autoRules: List<AutoDayCopyRule> = emptyList(),
    /** 这次自动调课为什么补了/没补某一天的说明，逐行展示。 */
    val autoNotices: List<String> = emptyList(),
    /** 被用户删掉、因此不再自动出现的规则条数（用于「恢复已忽略」按钮）。 */
    val suppressedAutoCount: Int = 0,
    /** 对照表是否已下载并解析成功。为 false 时页面会说明「以下为推算结果，可能不准」。 */
    val remoteTableReady: Boolean = false,
    /** 对照表版本号。**当前不在界面上渲染**——rev 是维护者的版本控制概念，用户读不懂也做不了决定。 */
    val remoteRevision: Int? = null,
    /** 对照表更新日期，用作「可能已过期」的判据。 */
    val remoteUpdatedAt: String = ""
) {
    /** 学期起止日期缺失时无法确定可选范围，整页退化为空状态。 */
    val canEdit: Boolean
        get() = isReady && semesterStartDate != null && semesterEndDate != null
}

/** 规则来源，决定列表里的徽标文案与删除行为。 */
enum class ManualDayCopyRowOrigin(val label: String) {
    MANUAL("手动"),
    AUTO_REMOTE("自动·教务"),
    AUTO_DERIVED("自动·推导");

    val isAuto: Boolean get() = this != MANUAL
}

/** 规则列表行：复制节数一并算好，避免渲染时重复计算。 */
data class ManualDayCopyRow(
    val rule: ManualDayCopyRule,
    val courseCount: Int,
    val origin: ManualDayCopyRowOrigin = ManualDayCopyRowOrigin.MANUAL
)

/** 「自动补的」组标题。 */
internal const val AUTO_ROW_GROUP_TITLE = "自动补的"

/** 「我加的」组标题。 */
internal const val MANUAL_ROW_GROUP_TITLE = "我加的"

/** 同一来源的一组规则行：共用一个组标题与一条身份色。 */
data class ManualDayCopyGroup(
    val title: String,
    val isAuto: Boolean,
    val rows: List<ManualDayCopyRow>
)

/**
 * 把规则行按来源分成「自动补的 / 我加的」两组，自动在前、手动在后（与列表原本的顺序一致）。
 *
 * 空组不出现——只有手动规则时不该冒出一个空的「自动补的 0」。分组之后来源由组标题声明，
 * 逐行的来源徽标就可以撤掉；那个徽标在长日期文本旁边会被挤成一个字一行。
 */
internal fun groupDayCopyRows(rows: List<ManualDayCopyRow>): List<ManualDayCopyGroup> =
    listOf(
        ManualDayCopyGroup(AUTO_ROW_GROUP_TITLE, isAuto = true, rows = rows.filter { it.origin.isAuto }),
        ManualDayCopyGroup(MANUAL_ROW_GROUP_TITLE, isAuto = false, rows = rows.filterNot { it.origin.isAuto })
    ).filter { it.rows.isNotEmpty() }

/** 自动调课相关的原始 Flow 归约。分开归约是因为主 combine 已经占满 5 个流。 */
private data class AutoCopyState(
    val enabled: Boolean,
    val rulesBySemester: Map<String, List<AutoDayCopyRule>>,
    val noticesBySemester: Map<String, List<String>>,
    val suppressionsBySemester: Map<String, Set<String>>,
    val remoteTableReady: Boolean,
    val remoteRevision: Int?,
    val remoteUpdatedAt: String
)

class HolidayAdjustmentsViewModel(
    private val repository: ScheduleRepository,
    private val settingsStore: ScheduleSettingsStore,
    private val makeupTableClient: MakeupTableClient,
    private val autoDayCopyCoordinator: AutoDayCopyCoordinator
) : ViewModel() {

    private val _message = MutableStateFlow("")

    /** 用户主动操作的结果提示（下载对照表 / 恢复已忽略）。与学期概览同一套做法。 */
    val message: StateFlow<String> = _message

    val uiState: StateFlow<HolidayAdjustmentsUiState>

    init {
        val semesterBase = combine(
            repository.currentSemester,
            repository.currentCourses,
            settingsStore.semesterStartMonday,
            settingsStore.semesterEndDate,
            settingsStore.hiddenCourseRules
        ) { semester, courses, fallbackStart, fallbackEnd, hiddenRulesBySemester ->
            SemesterBase(
                semester = semester,
                courses = courses,
                // 手动隐藏的卡片与调休规则同属「我手动改动了课表」的叠加层，所以放在这一页。
                hiddenItems = hiddenRulesBySemester[semester?.id.orEmpty()].orEmpty().map { rule ->
                    HiddenCardItem(
                        id = rule.id,
                        // 节次文案必须按**该学期来源**的口径渲染：南宁与研究生都没有中午偏移，
                        // 漏传会退化成默认 true，把内部 5/6 节显示成「午1-午2」。
                        // 取学期自身的 campus 而不是再加一条流——这里的 combine 已是 5 个流的上限。
                        label = hiddenRuleLabel(
                            rule,
                            courses,
                            capabilitiesFor(semester?.campus ?: CampusType.GUILIN).hasNoonSections
                        ),
                        existsInSchedule = hiddenCardRuleHits(courses, rule)
                    )
                },
                // 以 settings 为准：首页网格的周次锚点就是它（ScheduleViewModel 用
                // settingsStore.semesterStartMonday）。两边锚点不同时，编辑页预览的
                // 「将复制 N 节课程」会和首页实际追加的课次对不上。
                // 两者是同一次导入写入的，正常情况下完全一致。
                startDate = fallbackStart ?: semester?.semesterStartDate,
                endDate = fallbackEnd ?: semester?.semesterEndDate
            )
        }

        val holidayDates = settingsStore.holidayCacheByYear.map { cache ->
            cache.entries.flatMapTo(mutableSetOf()) { (year, json) ->
                TimorHolidayCalendarParser.parse(json, year)?.holidayDates.orEmpty()
            }
        }

        // 自动调课相关的 5 个 Flow 先归约成一路：主 combine 只剩一个空位（4 + 1 = 5）。
        val autoCopyState = combine(
            settingsStore.autoDayCopyEnabled,
            settingsStore.autoDayCopies,
            settingsStore.autoDayCopyNotices,
            settingsStore.autoDayCopySuppressions,
            settingsStore.holidayMakeupTableJson
        ) { enabled, rulesBySemester, noticesBySemester, suppressionsBySemester, tableJson ->
            val table = parseMakeupTableJson(tableJson)
            AutoCopyState(
                enabled = enabled,
                rulesBySemester = rulesBySemester,
                noticesBySemester = noticesBySemester,
                suppressionsBySemester = suppressionsBySemester,
                remoteTableReady = table != null,
                remoteRevision = table?.revision,
                remoteUpdatedAt = table?.updatedAt.orEmpty()
            )
        }

        uiState = combine(
            semesterBase,
            settingsStore.manualDayCopies,
            settingsStore.showWeekend,
            holidayDates,
            autoCopyState
        ) { base, rulesBySemester, showWeekend, holidays, auto ->
            val semesterId = base.semester?.id.orEmpty()
            HolidayAdjustmentsUiState(
                // 没有课程就没有可复制的对象；与小程序一致，此时整页进入空状态。
                isReady = base.courses.isNotEmpty(),
                semesterId = semesterId,
                semesterLabel = base.semester?.displayName.orEmpty(),
                semesterStartDate = base.startDate,
                semesterEndDate = base.endDate,
                courses = base.courses,
                rules = rulesBySemester[semesterId].orEmpty(),
                hiddenItems = base.hiddenItems,
                holidayDates = holidays,
                showWeekend = showWeekend,
                autoDayCopyEnabled = auto.enabled,
                // 开关关闭时 store 的 Flow 已经恒为空表，这里不需要再判一次。
                autoRules = auto.rulesBySemester[semesterId].orEmpty(),
                autoNotices = auto.noticesBySemester[semesterId].orEmpty(),
                suppressedAutoCount = auto.suppressionsBySemester[semesterId].orEmpty().size,
                remoteTableReady = auto.remoteTableReady,
                remoteRevision = auto.remoteRevision,
                remoteUpdatedAt = auto.remoteUpdatedAt
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = HolidayAdjustmentsUiState()
        )
    }

    /**
     * 界面展示用的规则行（含每行复制节数）。
     *
     * 自动行排在前面并带来源徽标；同一 id 同时存在于两边时**只显示手动那条**——用户显式
     * 加过的规则优先，删掉自动那条也不该把用户自己加的删掉。
     */
    fun rowsFor(state: HolidayAdjustmentsUiState): List<ManualDayCopyRow> {
        val manualIds = state.rules.mapTo(mutableSetOf()) { it.id }
        val autoRows = state.autoRules
            .filterNot { it.id in manualIds }
            .map { rule ->
                ManualDayCopyRow(
                    rule = rule.asManualRule(),
                    courseCount = countManualCopySourceBlocks(
                        state.courses,
                        rule.sourceDate,
                        state.semesterStartDate
                    ),
                    origin = when (rule.origin) {
                        AutoCopyOrigin.REMOTE -> ManualDayCopyRowOrigin.AUTO_REMOTE
                        AutoCopyOrigin.DERIVED -> ManualDayCopyRowOrigin.AUTO_DERIVED
                    }
                )
            }
        val manualRows = state.rules.map { rule ->
            ManualDayCopyRow(
                rule = rule,
                courseCount = countManualCopySourceBlocks(
                    state.courses,
                    rule.sourceDate,
                    state.semesterStartDate
                ),
                origin = ManualDayCopyRowOrigin.MANUAL
            )
        }
        return autoRows + manualRows
    }

    fun sourceCourseCount(state: HolidayAdjustmentsUiState, sourceDate: LocalDate?): Int =
        countManualCopySourceBlocks(state.courses, sourceDate, state.semesterStartDate)

    fun isHoliday(state: HolidayAdjustmentsUiState, date: LocalDate?): Boolean =
        date != null && date in state.holidayDates

    fun clearMessage() {
        _message.value = ""
    }

    /**
     * 添加规则。返回失败原因，成功返回 null。
     *
     * 拒绝保存的四种情况与小程序一致：日期非法或相同、原日期无课、规则已存在。
     * 目标日是法定假日 **不** 拒绝保存，只由界面先做一次二次确认。
     */
    fun addRule(
        state: HolidayAdjustmentsUiState,
        sourceDate: LocalDate?,
        targetDate: LocalDate?
    ): String? {
        if (!state.canEdit) return "当前学期课表尚未就绪"
        validateManualDayCopy(sourceDate, targetDate)?.let { return it }
        if (sourceDate == null || targetDate == null) return "请选择有效日期"
        if (sourceCourseCount(state, sourceDate) == 0) return "原日期没有可复制课程"
        val rule = ManualDayCopyRule(sourceDate = sourceDate, targetDate = targetDate)
        if (state.rules.any { it.id == rule.id }) return "该调课已存在"
        viewModelScope.launch {
            // 写入前重新读取最新状态，避免连续快速添加时基于旧列表互相覆盖。
            val latest = uiState.value
            if (latest.rules.any { it.id == rule.id }) return@launch
            settingsStore.setManualDayCopies(latest.semesterId, latest.rules + rule)
        }
        return null
    }

    fun deleteRule(rule: ManualDayCopyRule) {
        viewModelScope.launch {
            val latest = uiState.value
            settingsStore.setManualDayCopies(
                latest.semesterId,
                latest.rules.filterNot { it.id == rule.id }
            )
        }
    }

    /**
     * 打开/关闭「自动调课」。
     *
     * 打开：立刻按本地缓存重算一遍（**不联网**），用户不需要再去别处刷新。
     * 关闭：清掉本学期的自动规则与提示，手动规则一条不动；**抑制记录保留**——那是用户的
     * 意图，关掉再打开时被删过的规则依然不该复活。
     */
    fun setAutoDayCopyEnabled(enabled: Boolean) {
        val semesterId = uiState.value.semesterId
        viewModelScope.launch {
            settingsStore.setAutoDayCopyEnabled(enabled)
            if (semesterId.isBlank()) return@launch
            if (enabled) {
                autoDayCopyCoordinator.recompose(semesterId)
            } else {
                settingsStore.clearAutoDayCopies(semesterId)
                settingsStore.setAutoCopyNotices(semesterId, emptyList())
            }
        }
    }

    /** 「重新计算」：纯本地重跑一遍（节假日缓存或对照表可能已经更新）。不联网。 */
    fun recalculateAutoCopies() {
        val semesterId = uiState.value.semesterId
        if (semesterId.isBlank()) return
        viewModelScope.launch { autoDayCopyCoordinator.recompose(semesterId) }
    }

    /**
     * 下载调休对照表。**这是本页唯一会联网的动作**，且只由用户点按钮触发——
     * 进入页面、拨开关、点重新计算都不联网（与「进入页面只读缓存」的既有约定一致）。
     */
    fun downloadMakeupTable() {
        val semesterId = uiState.value.semesterId
        viewModelScope.launch {
            val updated = refreshMakeupTable(makeupTableClient, settingsStore::setHolidayMakeupTableJson)
            _message.value = if (updated) "对照表已更新" else "对照表下载失败，可稍后重试"
            if (updated && semesterId.isNotBlank()) {
                // 表更新后必须重算：同一补班日上远端要压过推导。
                autoDayCopyCoordinator.recompose(semesterId)
            }
        }
    }

    /**
     * 删除一条自动规则。**先写抑制、再删规则**——顺序反了的话，中途被打断就会出现
     * 「规则删了但没记抑制，下次重算又回来」。
     */
    fun deleteAutoRule(ruleId: String) {
        val semesterId = uiState.value.semesterId
        if (semesterId.isBlank()) return
        viewModelScope.launch {
            settingsStore.addAutoDayCopySuppression(semesterId, ruleId)
            val latest = settingsStore.autoDayCopies.first()[semesterId].orEmpty()
            settingsStore.setAutoDayCopies(semesterId, latest.filterNot { it.id == ruleId })
        }
    }

    /** 恢复全部被忽略的自动规则（用户唯一需要主动点的恢复入口）。 */
    fun restoreSuppressedAutoRules() {
        val semesterId = uiState.value.semesterId
        if (semesterId.isBlank()) return
        viewModelScope.launch {
            settingsStore.clearAutoDayCopySuppressions(semesterId)
            autoDayCopyCoordinator.recompose(semesterId)
        }
    }

    /** 恢复一条被手动隐藏的卡片。写入前重新读一次最新列表，避免与首页的并发写入互相覆盖。 */
    fun restoreHiddenCard(ruleId: String) {
        val semesterId = uiState.value.semesterId
        if (semesterId.isBlank()) return
        viewModelScope.launch {
            val latest = settingsStore.hiddenCourseRules.first()[semesterId].orEmpty()
            settingsStore.setHiddenCourseRules(semesterId, latest.filterNot { it.id == ruleId })
        }
    }

    /** 恢复本学期全部被手动隐藏的卡片。 */
    fun restoreAllHiddenCards() {
        val semesterId = uiState.value.semesterId
        if (semesterId.isBlank()) return
        viewModelScope.launch { settingsStore.clearHiddenCourseRules(semesterId) }
    }

    private data class SemesterBase(
        val semester: AcademicSemester?,
        val courses: List<ScheduleCourse>,
        val startDate: LocalDate?,
        val endDate: LocalDate?,
        val hiddenItems: List<HiddenCardItem> = emptyList()
    )
}

class HolidayAdjustmentsViewModelFactory(
    private val repository: ScheduleRepository,
    private val settingsStore: ScheduleSettingsStore,
    private val makeupTableClient: MakeupTableClient,
    private val autoDayCopyCoordinator: AutoDayCopyCoordinator
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return HolidayAdjustmentsViewModel(
            repository,
            settingsStore,
            makeupTableClient,
            autoDayCopyCoordinator
        ) as T
    }
}
