package com.glut.schedule.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.glut.schedule.data.model.DEFAULT_SEMESTER_END_DATE
import com.glut.schedule.data.model.DEFAULT_SEMESTER_START_MONDAY
import com.glut.schedule.data.model.ClassPeriod
import com.glut.schedule.data.model.CourseColorMapper
import com.glut.schedule.data.model.DEFAULT_BACKGROUND_DIM_AMOUNT
import com.glut.schedule.data.model.AutoDayCopyRule
import com.glut.schedule.data.model.HiddenCourseRule
import com.glut.schedule.data.model.ManualDayCopyRule
import com.glut.schedule.data.model.NormalizedCropRect
import com.glut.schedule.data.model.ScheduleBackgroundPreferences
import com.glut.schedule.data.model.decodeAutoCopyNotices
import com.glut.schedule.data.model.decodeAutoDayCopyRules
import com.glut.schedule.data.model.decodeAutoDayCopySuppressions
import com.glut.schedule.data.model.decodeHiddenCourseRules
import com.glut.schedule.data.model.decodeManualDayCopyRules
import com.glut.schedule.data.model.encodeAutoCopyNotices
import com.glut.schedule.data.model.encodeAutoDayCopyRules
import com.glut.schedule.data.model.encodeAutoDayCopySuppressions
import com.glut.schedule.data.model.encodeHiddenCourseRules
import com.glut.schedule.data.model.encodeManualDayCopyRules
import com.glut.schedule.data.model.snapBackgroundDimAmount
import com.glut.schedule.data.model.AcademicSemester
import com.glut.schedule.data.model.SemesterSeason
import com.glut.schedule.data.model.clampAcademicWeek
import com.glut.schedule.data.model.normalizeSemesterStartMonday
import com.glut.schedule.data.model.validateClassPeriods
import com.glut.schedule.service.greeting.GreetingTemplateCache
import com.glut.schedule.service.greeting.GreetingTemplateCacheSnapshot
import com.glut.schedule.service.holiday.encodeHolidayYearCache
import com.glut.schedule.service.holiday.holidayYearCache
import com.glut.schedule.service.holiday.isUsableHolidayYearPayload
import com.glut.schedule.service.holiday.isUsableMakeupTablePayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * 数据来源（校区）。
 *
 * `GRADUATE` 表示研究生门户（gsems.glut.edu.cn）。它与桂林/南宁不是「同一个教务的另一个校区」，
 * 而是一套完全独立的协议、学期编号与作息；因此凡是按校区分支的地方都必须显式处理它，
 * 不能再沿用「非南宁即桂林」的隐式判断。
 */
enum class CampusType { GUILIN, NANNING, GRADUATE }

/** 情侣/基友课表的展示范围；未知旧值必须安全回退到“一起”。 */
enum class PartnerScheduleViewMode(val storageValue: String) {
    PARTNER("partner"),
    COMBINED("combined");

    companion object {
        fun fromStorageValue(value: String?): PartnerScheduleViewMode =
            entries.firstOrNull { it.storageValue == value } ?: COMBINED
    }
}

enum class GuilinSubCampus(val storageValue: String) {
    YANSHAN(GUILIN_SUB_CAMPUS_DEFAULT),
    PINGFENG(GUILIN_SUB_CAMPUS_PINGFENG);

    companion object {
        fun fromStorageValue(value: String?): GuilinSubCampus =
            entries.firstOrNull { it.storageValue == value } ?: YANSHAN
    }
}

/** 上课时间的持久化作用域；桂林两个教学区必须互不覆盖。 */
enum class ClassPeriodProfile {
    GUILIN_YANSHAN,
    GUILIN_PINGFENG,
    NANNING,
    /** 研究生：12 节，默认没有任何时间，与本科/南宁作息互不影响。 */
    GRADUATE
}

fun classPeriodProfile(campus: CampusType, subCampus: String): ClassPeriodProfile = when (campus) {
    CampusType.NANNING -> ClassPeriodProfile.NANNING
    CampusType.GRADUATE -> ClassPeriodProfile.GRADUATE
    CampusType.GUILIN -> if (subCampus == GUILIN_SUB_CAMPUS_PINGFENG) {
        ClassPeriodProfile.GUILIN_PINGFENG
    } else {
        ClassPeriodProfile.GUILIN_YANSHAN
    }
}

/** 桂林子校区标识 */
const val GUILIN_SUB_CAMPUS_DEFAULT = "yanshan"
const val GUILIN_SUB_CAMPUS_PINGFENG = "pingfeng"

private val Context.scheduleSettings by preferencesDataStore(name = "schedule_settings")

class ScheduleSettingsStore(
    private val context: Context
) : GreetingTemplateCache {
    private val currentWeekKey = intPreferencesKey("current_week")
    private val showWeekendKey = booleanPreferencesKey("show_weekend")
    private val showNoonKey = booleanPreferencesKey("show_noon")
    /** 课表网格线；默认关闭，新增键对老用户即为关闭，不需要额外迁移。 */
    private val showGridLinesKey = booleanPreferencesKey("show_grid_lines")
    private val partnerShowWeekendKey = booleanPreferencesKey("partner_show_weekend")
    private val partnerShowNoonKey = booleanPreferencesKey("partner_show_noon")
    private val partnerViewModeKey = stringPreferencesKey("partner_schedule_view_mode")
    private val semesterStartMondayKey = stringPreferencesKey("semester_start_monday")
    private val semesterEndDateKey = stringPreferencesKey("semester_end_date")
    private val customBackgroundUriKey = stringPreferencesKey("custom_background_uri")
    private val customBackgroundCropLeftKey = floatPreferencesKey("custom_background_crop_left")
    private val customBackgroundCropTopKey = floatPreferencesKey("custom_background_crop_top")
    private val customBackgroundCropRightKey = floatPreferencesKey("custom_background_crop_right")
    private val customBackgroundCropBottomKey = floatPreferencesKey("custom_background_crop_bottom")
    private val backgroundDimAmountKey = floatPreferencesKey("background_dim_amount")
    private val remoteBackgroundIdKey = stringPreferencesKey("remote_background_id")
    private val remoteBackgroundSha256Key = stringPreferencesKey("remote_background_sha256")
    private val remoteBackgroundDisplayNameKey = stringPreferencesKey("remote_background_display_name")
    private val courseColorOverridesKey = stringSetPreferencesKey("course_color_overrides")
    private val guilinClassPeriodsKey = stringSetPreferencesKey("guilin_class_periods")
    private val guilinYanshanClassPeriodsKey = stringSetPreferencesKey("guilin_yanshan_class_periods")
    private val guilinPingfengClassPeriodsKey = stringSetPreferencesKey("guilin_pingfeng_class_periods")
    private val nanningClassPeriodsKey = stringSetPreferencesKey("nanning_class_periods")
    /** 研究生 12 节作息：默认整份为空时间，只有用户主动填写过才会出现这个键。 */
    private val graduateClassPeriodsKey = stringSetPreferencesKey("graduate_class_periods")
    private val campusTypeKey = stringPreferencesKey("campus_type")
    /** 上一次成功导入使用的来源；导入页据此恢复下拉选择，首启回退到桂林本部。 */
    private val lastImportSourceKey = stringPreferencesKey("last_import_source")
    private val updateAvailableVersionKey = stringPreferencesKey("update_available_version")
    private val dismissedUpdatePopupVersionKey = stringPreferencesKey("dismissed_update_popup_version")
    private val cachedNoticesJsonKey = stringPreferencesKey("cached_notices_json")
    private val readNoticeIdsKey = stringSetPreferencesKey("read_notice_ids")
    private val dismissedNoticePopupIdsKey = stringSetPreferencesKey("dismissed_notice_popup_ids")
    private val holidaysCacheKey = stringPreferencesKey("holidays_cache")
    private val holidaysCacheDateKey = stringPreferencesKey("holidays_cache_date")
    private val holidaysCacheYearsKey = stringPreferencesKey("holidays_cache_years")
    private val manualDayCopiesKey = stringSetPreferencesKey("manual_day_copies")
    /**
     * 自动调课开关。**默认关闭**：关闭时「调休调课」的行为与没有这个功能时完全一致，
     * 只有用户手动添加的规则生效。
     */
    private val autoDayCopyEnabledKey = booleanPreferencesKey("auto_day_copy_enabled")
    private val autoDayCopiesKey = stringSetPreferencesKey("auto_day_copies")
    private val autoDayCopySuppressionsKey = stringSetPreferencesKey("auto_day_copy_suppressions")
    private val autoDayCopyNoticesKey = stringPreferencesKey("auto_day_copy_notices")
    private val holidayMakeupTableKey = stringPreferencesKey("holiday_makeup_table_json")
    private val hiddenCourseCardsKey = stringSetPreferencesKey("hidden_course_cards")
    private val currentSemesterIdKey = stringPreferencesKey("current_semester_id")
    private val confirmedEnrollmentYearKey = intPreferencesKey("confirmed_enrollment_year")
    private val confirmedEnrollmentSeasonKey = stringPreferencesKey("confirmed_enrollment_season")
    private val guilinSubCampusKey = stringPreferencesKey("guilin_sub_campus")
    private val greetingEnabledKey = booleanPreferencesKey("drawer_greeting_enabled")
    private val greetingTemplatesJsonKey = stringPreferencesKey("greeting_templates_json")
    private val greetingTemplatesSuccessAtKey = longPreferencesKey("greeting_templates_success_at")
    private val greetingTemplatesAttemptAtKey = longPreferencesKey("greeting_templates_attempt_at")

    val currentWeekNumber: Flow<Int> = context.scheduleSettings.data.map { preferences ->
        clampAcademicWeek(preferences[currentWeekKey] ?: 9)
    }.distinctUntilChanged()

    val showWeekend: Flow<Boolean> = context.scheduleSettings.data.map { preferences ->
        preferences[showWeekendKey] ?: false
    }.distinctUntilChanged()

    /** 情侣/基友课表的显示偏好独立保存，不改变首页布局。 */
    val partnerShowWeekend: Flow<Boolean> = context.scheduleSettings.data.map { preferences ->
        preferences[partnerShowWeekendKey] ?: false
    }.distinctUntilChanged()

    val partnerViewMode: Flow<PartnerScheduleViewMode> = context.scheduleSettings.data.map { preferences ->
        PartnerScheduleViewMode.fromStorageValue(preferences[partnerViewModeKey])
    }.distinctUntilChanged()

    val semesterStartMonday: Flow<LocalDate> = context.scheduleSettings.data.map { preferences ->
        preferences[semesterStartMondayKey]
            ?.let { value -> runCatching { LocalDate.parse(value) }.getOrNull() }
            ?.let(::normalizeSemesterStartMonday)
            ?: DEFAULT_SEMESTER_START_MONDAY
    }.distinctUntilChanged()

    val semesterEndDate: Flow<LocalDate> = context.scheduleSettings.data.map { preferences ->
        preferences[semesterEndDateKey]
            ?.let { value -> runCatching { LocalDate.parse(value) }.getOrNull() }
            ?: DEFAULT_SEMESTER_END_DATE
    }.distinctUntilChanged()

    val customBackgroundUri: Flow<String> = context.scheduleSettings.data.map { preferences ->
        preferences[customBackgroundUriKey].orEmpty()
    }.distinctUntilChanged()

    /** URI、裁剪参数和蒙黑度来自同一份 Preferences 快照，避免界面短暂组合出不一致状态。 */
    val backgroundPreferences: Flow<ScheduleBackgroundPreferences> = context.scheduleSettings.data.map { preferences ->
        val crop = listOf(
            preferences[customBackgroundCropLeftKey],
            preferences[customBackgroundCropTopKey],
            preferences[customBackgroundCropRightKey],
            preferences[customBackgroundCropBottomKey]
        ).takeIf { values -> values.all { it != null } }
            ?.let { values ->
                NormalizedCropRect(values[0]!!, values[1]!!, values[2]!!, values[3]!!).sanitized()
            }
        ScheduleBackgroundPreferences(
            uri = preferences[customBackgroundUriKey].orEmpty(),
            crop = crop,
            dimAmount = snapBackgroundDimAmount(
                preferences[backgroundDimAmountKey] ?: DEFAULT_BACKGROUND_DIM_AMOUNT
            ),
            remoteId = preferences[remoteBackgroundIdKey].orEmpty(),
            remoteSha256 = preferences[remoteBackgroundSha256Key].orEmpty(),
            remoteDisplayName = preferences[remoteBackgroundDisplayNameKey].orEmpty()
        )
    }.distinctUntilChanged()

    val courseColorOverrides: Flow<Map<String, String>> = context.scheduleSettings.data.map { preferences ->
        decodeCourseColorOverrides(preferences[courseColorOverridesKey].orEmpty())
    }.distinctUntilChanged()

    val classPeriodOverrides: Flow<Map<CampusType, List<ClassPeriod>>> =
        context.scheduleSettings.data.map { preferences ->
            buildMap {
                decodeClassPeriods(
                    CampusType.GUILIN,
                    preferences[guilinClassPeriodsKey].orEmpty()
                )?.let { put(CampusType.GUILIN, it) }
                decodeClassPeriods(
                    CampusType.NANNING,
                    preferences[nanningClassPeriodsKey].orEmpty()
                )?.let { put(CampusType.NANNING, it) }
            }
        }.distinctUntilChanged()

    val classPeriodProfileOverrides: Flow<Map<ClassPeriodProfile, List<ClassPeriod>>> =
        context.scheduleSettings.data.map { preferences ->
            decodeClassPeriodOverrides(
                selectedSubCampus = GuilinSubCampus.fromStorageValue(preferences[guilinSubCampusKey]),
                legacyGuilinEntries = preferences[guilinClassPeriodsKey].orEmpty(),
                yanshanEntries = preferences[guilinYanshanClassPeriodsKey].orEmpty(),
                pingfengEntries = preferences[guilinPingfengClassPeriodsKey].orEmpty(),
                nanningEntries = preferences[nanningClassPeriodsKey].orEmpty(),
                graduateEntries = preferences[graduateClassPeriodsKey].orEmpty()
            )
        }.distinctUntilChanged()

    /**
     * 上一次成功导入的来源。
     *
     * 只在**导入成功之后**写入（见 `ImportSource` 的注释）：把「用户在下拉框里点过什么」当成
     * 已生效的来源，会让一次失败的尝试把首页数据源也改掉。
     */
    val lastImportSource: Flow<ImportSource> = context.scheduleSettings.data.map { preferences ->
        // 缺失时用既有的 campus_type 兜底。
        //
        // 老用户升级前只有 campus_type、没有 last_import_source；若这里直接回退 GUILIN，
        // 南宁用户的导入页下拉框会默认成「桂林本部」，点登录就会拿南宁账号去打桂林教务，
        // 失败信息还会误导成「账号密码错误」。历史上「按 10 位学号自动切南宁」的兜底
        // 已随来源下拉框一并移除，因此这条迁移必须在读取侧补上。
        preferences[lastImportSourceKey]
            ?.let(ImportSource::fromStorageValue)
            ?: ImportSource.of(
                runCatching { CampusType.valueOf(preferences[campusTypeKey] ?: CampusType.GUILIN.name) }
                    .getOrDefault(CampusType.GUILIN)
            )
    }.distinctUntilChanged()

    val campusType: Flow<CampusType> = context.scheduleSettings.data.map { preferences ->
        val name = preferences[campusTypeKey] ?: CampusType.GUILIN.name
        runCatching { CampusType.valueOf(name) }.getOrDefault(CampusType.GUILIN)
    }

    val currentSemesterId: Flow<String> = context.scheduleSettings.data.map { preferences ->
        preferences[currentSemesterIdKey] ?: AcademicSemester.LEGACY_CURRENT_ID
    }.distinctUntilChanged()

    val confirmedEnrollmentStart: Flow<Pair<Int, SemesterSeason>?> = context.scheduleSettings.data.map { preferences ->
        val year = preferences[confirmedEnrollmentYearKey] ?: return@map null
        val season = preferences[confirmedEnrollmentSeasonKey]
            ?.let { runCatching { SemesterSeason.valueOf(it) }.getOrNull() }
            ?: SemesterSeason.AUTUMN
        year to season
    }.distinctUntilChanged()

    /** 桂林子校区偏好：yanshan（默认）或 pingfeng */
    val guilinSubCampus: Flow<String> = context.scheduleSettings.data.map { preferences ->
        preferences[guilinSubCampusKey] ?: GUILIN_SUB_CAMPUS_DEFAULT
    }.distinctUntilChanged()

    val greetingEnabled: Flow<Boolean> = context.scheduleSettings.data.map { preferences ->
        greetingEnabledFromStored(preferences[greetingEnabledKey])
    }.distinctUntilChanged()

    suspend fun setGuilinSubCampus(subCampus: String) {
        context.scheduleSettings.edit { preferences ->
            migrateLegacyGuilinClassPeriods(preferences)
            if (subCampus == GUILIN_SUB_CAMPUS_DEFAULT) {
                preferences.remove(guilinSubCampusKey)
            } else {
                preferences[guilinSubCampusKey] = subCampus
            }
        }
    }

    suspend fun setCampusType(type: CampusType) {
        context.scheduleSettings.edit { preferences ->
            preferences[campusTypeKey] = type.name
        }
    }

    /**
     * 记录「这一次导入确实成功了」的来源。
     *
     * 必须由成功路径调用：下拉框的选择本身只存在于内存里，失败不回写，
     * 否则一次点错就会把上次真正可用的来源覆盖掉。
     */
    suspend fun setLastImportSource(source: ImportSource) {
        context.scheduleSettings.edit { preferences ->
            preferences[lastImportSourceKey] = source.storageValue
        }
    }

    suspend fun setCurrentSemesterId(semesterId: String) {
        context.scheduleSettings.edit { preferences ->
            preferences[currentSemesterIdKey] = semesterId
        }
    }

    suspend fun setConfirmedEnrollmentStart(year: Int, season: SemesterSeason) {
        context.scheduleSettings.edit { preferences ->
            preferences[confirmedEnrollmentYearKey] = year
            preferences[confirmedEnrollmentSeasonKey] = season.name
        }
    }

    suspend fun setCurrentWeekNumber(week: Int) {
        context.scheduleSettings.edit { preferences ->
            preferences[currentWeekKey] = clampAcademicWeek(week)
        }
    }

    suspend fun setShowWeekend(showWeekend: Boolean) {
        context.scheduleSettings.edit { preferences ->
            preferences[showWeekendKey] = showWeekend
        }
    }

    suspend fun setPartnerShowWeekend(showWeekend: Boolean) {
        context.scheduleSettings.edit { preferences ->
            preferences[partnerShowWeekendKey] = showWeekend
        }
    }

    suspend fun setPartnerViewMode(mode: PartnerScheduleViewMode) {
        context.scheduleSettings.edit { preferences ->
            preferences[partnerViewModeKey] = mode.storageValue
        }
    }

    val showNoon: Flow<Boolean> = context.scheduleSettings.data.map { preferences ->
        preferences[showNoonKey] ?: false
    }.distinctUntilChanged()

    val partnerShowNoon: Flow<Boolean> = context.scheduleSettings.data.map { preferences ->
        preferences[partnerShowNoonKey] ?: false
    }.distinctUntilChanged()

    suspend fun setShowNoon(showNoon: Boolean) {
        context.scheduleSettings.edit { preferences ->
            preferences[showNoonKey] = showNoon
        }
    }

    val showGridLines: Flow<Boolean> = context.scheduleSettings.data.map { preferences ->
        preferences[showGridLinesKey] ?: false
    }.distinctUntilChanged()

    suspend fun setShowGridLines(showGridLines: Boolean) {
        context.scheduleSettings.edit { preferences ->
            preferences[showGridLinesKey] = showGridLines
        }
    }

    suspend fun setPartnerShowNoon(showNoon: Boolean) {
        context.scheduleSettings.edit { preferences ->
            preferences[partnerShowNoonKey] = showNoon
        }
    }

    suspend fun setSemesterStartMonday(date: LocalDate) {
        context.scheduleSettings.edit { preferences ->
            preferences[semesterStartMondayKey] = normalizeSemesterStartMonday(date).toString()
        }
    }

    suspend fun setSemesterEndDate(date: LocalDate) {
        context.scheduleSettings.edit { preferences ->
            preferences[semesterEndDateKey] = date.toString()
        }
    }

    suspend fun setCustomBackgroundUri(uri: String) {
        setCustomBackground(uri, crop = null)
    }

    /** 自定义图片 URI 与裁剪区域必须一次写入，避免冷启动读到半套配置。 */
    suspend fun setCustomBackground(uri: String, crop: NormalizedCropRect?) {
        context.scheduleSettings.edit { preferences ->
            writeBackground(preferences, uri, crop)
            preferences.remove(remoteBackgroundIdKey)
            preferences.remove(remoteBackgroundSha256Key)
            preferences.remove(remoteBackgroundDisplayNameKey)
        }
    }

    /** 远程资源身份与 URI、裁剪区域一次写入，删除管理才能可靠判断“使用中”。 */
    suspend fun setRemoteBackground(
        uri: String,
        crop: NormalizedCropRect?,
        id: String,
        sha256: String,
        displayName: String
    ) {
        context.scheduleSettings.edit { preferences ->
            writeBackground(preferences, uri, crop)
            preferences[remoteBackgroundIdKey] = id
            preferences[remoteBackgroundSha256Key] = sha256
            preferences[remoteBackgroundDisplayNameKey] = displayName
        }
    }

    private fun writeBackground(
        preferences: MutablePreferences,
        uri: String,
        crop: NormalizedCropRect?
    ) {
        val trimmed = uri.trim()
        if (trimmed.isBlank()) preferences.remove(customBackgroundUriKey)
        else preferences[customBackgroundUriKey] = trimmed
        val safeCrop = crop?.sanitized()
        if (safeCrop == null) {
            preferences.remove(customBackgroundCropLeftKey)
            preferences.remove(customBackgroundCropTopKey)
            preferences.remove(customBackgroundCropRightKey)
            preferences.remove(customBackgroundCropBottomKey)
        } else {
            preferences[customBackgroundCropLeftKey] = safeCrop.left
            preferences[customBackgroundCropTopKey] = safeCrop.top
            preferences[customBackgroundCropRightKey] = safeCrop.right
            preferences[customBackgroundCropBottomKey] = safeCrop.bottom
        }
    }

    suspend fun setBackgroundDimAmount(value: Float) {
        context.scheduleSettings.edit { preferences ->
            preferences[backgroundDimAmountKey] = snapBackgroundDimAmount(value)
        }
    }

    // ---- Update notifications ----

    /** Latest version available for download (empty if no update or check hasn't run) */
    val updateAvailableVersion: Flow<String> = context.scheduleSettings.data.map { preferences ->
        preferences[updateAvailableVersionKey].orEmpty()
    }

    suspend fun setUpdateAvailable(version: String) {
        context.scheduleSettings.edit { preferences ->
            preferences[updateAvailableVersionKey] = version
        }
    }

    suspend fun setCourseColorOverride(courseKey: String, colorHex: String): Boolean {
        val normalizedKey = courseKey.trim()
        val normalizedColor = CourseColorMapper.normalizeHexColor(colorHex) ?: return false
        if (normalizedKey.isBlank()) return false
        context.scheduleSettings.edit { preferences ->
            val overrides = decodeCourseColorOverrides(preferences[courseColorOverridesKey].orEmpty()).toMutableMap()
            overrides[normalizedKey] = normalizedColor
            preferences[courseColorOverridesKey] = encodeCourseColorOverrides(overrides)
        }
        return true
    }

    suspend fun removeCourseColorOverride(courseKey: String) {
        context.scheduleSettings.edit { preferences ->
            val overrides = decodeCourseColorOverrides(preferences[courseColorOverridesKey].orEmpty()).toMutableMap()
            if (overrides.remove(courseKey) == null) return@edit
            if (overrides.isEmpty()) preferences.remove(courseColorOverridesKey)
            else preferences[courseColorOverridesKey] = encodeCourseColorOverrides(overrides)
        }
    }

    suspend fun clearCourseColorOverrides() {
        context.scheduleSettings.edit { preferences -> preferences.remove(courseColorOverridesKey) }
    }

    suspend fun setClassPeriods(campus: CampusType, periods: List<ClassPeriod>): Boolean {
        if (!validateClassPeriods(campus, periods)) return false
        context.scheduleSettings.edit { preferences ->
            preferences[classPeriodsKey(campus)] = encodeClassPeriods(periods)
        }
        return true
    }

    suspend fun setGreetingEnabled(enabled: Boolean) {
        context.scheduleSettings.edit { preferences ->
            preferences[greetingEnabledKey] = enabled
        }
    }

    suspend fun setClassPeriods(profile: ClassPeriodProfile, periods: List<ClassPeriod>): Boolean {
        if (!com.glut.schedule.data.model.validateClassPeriods(profile, periods)) return false
        context.scheduleSettings.edit { preferences ->
            migrateLegacyGuilinClassPeriods(preferences)
            preferences[classPeriodsKey(profile)] = encodeClassPeriods(periods)
        }
        return true
    }

    suspend fun resetClassPeriods(campus: CampusType) {
        context.scheduleSettings.edit { preferences ->
            preferences.remove(classPeriodsKey(campus))
        }
    }

    suspend fun resetClassPeriods(profile: ClassPeriodProfile) {
        context.scheduleSettings.edit { preferences ->
            migrateLegacyGuilinClassPeriods(preferences)
            preferences.remove(classPeriodsKey(profile))
        }
    }

    /** Latest update version that has already been shown as an automatic startup popup. */
    val dismissedUpdatePopupVersion: Flow<String> = context.scheduleSettings.data.map { preferences ->
        preferences[dismissedUpdatePopupVersionKey].orEmpty()
    }

    suspend fun markUpdatePopupDismissed(version: String) {
        if (version.isBlank()) return
        context.scheduleSettings.edit { preferences ->
            preferences[dismissedUpdatePopupVersionKey] = version
        }
    }

    // ---- App notices ----

    val cachedNoticesJson: Flow<String> = context.scheduleSettings.data.map { preferences ->
        preferences[cachedNoticesJsonKey].orEmpty()
    }

    val readNoticeIds: Flow<Set<String>> = context.scheduleSettings.data.map { preferences ->
        preferences[readNoticeIdsKey].orEmpty()
    }

    val dismissedNoticePopupIds: Flow<Set<String>> = context.scheduleSettings.data.map { preferences ->
        preferences[dismissedNoticePopupIdsKey].orEmpty()
    }

    suspend fun setCachedNoticesJson(json: String) {
        context.scheduleSettings.edit { preferences ->
            if (json.isBlank()) {
                preferences.remove(cachedNoticesJsonKey)
            } else {
                preferences[cachedNoticesJsonKey] = json
            }
        }
    }

    suspend fun markNoticesRead(ids: Set<String>) {
        if (ids.isEmpty()) return
        context.scheduleSettings.edit { preferences ->
            preferences[readNoticeIdsKey] = preferences[readNoticeIdsKey].orEmpty() + ids
        }
    }

    suspend fun markNoticePopupsDismissed(ids: Set<String>) {
        if (ids.isEmpty()) return
        context.scheduleSettings.edit { preferences ->
            preferences[dismissedNoticePopupIdsKey] = preferences[dismissedNoticePopupIdsKey].orEmpty() + ids
        }
    }

    // ---- Holidays cache ----

    /**
     * 按数据所属年份缓存的 timor.tech 节假日 JSON（`年 -> 原始返回`）。
     *
     * 必须按年份分开存：秋季学期会跨到次年 1 月，只留一份「最后拉取的那年」会让元旦
     * 之类的假期拿不到数据。缓存键用**数据所属年份**而不是拉取当天，跨年提前缓存
     * 下一年的数据也能被正确识别。
     */
    val holidayCacheByYear: Flow<Map<Int, String>> = context.scheduleSettings.data.map { preferences ->
        holidayCacheYearsFrom(preferences)
    }

    /**
     * 写入年度缓存。**写闸门**：只有真能渲染出假期的载荷才落盘。
     *
     * timor 对未公布年份返回的是合法但内容为空的 `{"code":0,"holiday":{}}`；放行它会让
     * 「未公布」变成「已缓存」，元旦之类的固定节日再也不会有角标。读数方另有读闸门，
     * 这里再拦一道是为了不把坏数据写进磁盘。
     */
    suspend fun setHolidayYearCache(year: Int, json: String) {
        if (year <= 0 || !isUsableHolidayYearPayload(json, year)) return
        context.scheduleSettings.edit { preferences ->
            // 合并而不是覆盖：写入前必须先经过同一个读取函数，否则「旧键兜底」的条目
            // 会在第一次写入新键后凭空消失——旧数据被静默丢弃，角标与学期概览都会短暂回退。
            preferences[holidaysCacheYearsKey] =
                encodeHolidayYearCache(holidayCacheYearsFrom(preferences) + (year to json))
        }
    }

    /** 兼容旧读取方：仍按「当前年份的 JSON + 该年份的第一天」返回。 */
    val holidaysCache: Flow<Pair<String, String>> = holidayCacheByYear.map { cache ->
        val year = LocalDate.now().year
        val json = cache[year].orEmpty()
        // 缓存日期必须落在数据所属年份内，跨年时旧缓存应被当作未知而不是沿用。
        if (json.isBlank()) "" to "" else json to "$year-01-01"
    }

    suspend fun setHolidaysCache(json: String) = setHolidayYearCache(LocalDate.now().year, json)

    // ---- 手动调休调课规则 ----

    /** 调休调课规则按学期隔离，避免当前学期的设置污染历史学期快照。 */
    val manualDayCopies: Flow<Map<String, List<ManualDayCopyRule>>> =
        context.scheduleSettings.data.map { preferences ->
            decodeManualDayCopyRules(preferences[manualDayCopiesKey].orEmpty())
        }.distinctUntilChanged()

    suspend fun setManualDayCopies(semesterId: String, rules: List<ManualDayCopyRule>) {
        if (semesterId.isBlank()) return
        context.scheduleSettings.edit { preferences ->
            val current = decodeManualDayCopyRules(preferences[manualDayCopiesKey].orEmpty()).toMutableMap()
            if (rules.isEmpty()) current.remove(semesterId) else current[semesterId] = rules
            preferences[manualDayCopiesKey] = encodeManualDayCopyRules(current)
        }
    }

    // ---- 手动隐藏的课程卡片 ----

    /**
     * 按学期隔离的用户隐藏记录。
     *
     * 与 [manualDayCopies] 同样是「读时叠加」的覆盖层：刷新课表是整表替换，隐藏记录只有
     * 存在课程数据之外才不会被冲掉。
     */
    val hiddenCourseRules: Flow<Map<String, List<HiddenCourseRule>>> =
        context.scheduleSettings.data.map { preferences ->
            decodeHiddenCourseRules(preferences[hiddenCourseCardsKey].orEmpty())
        }.distinctUntilChanged()

    suspend fun setHiddenCourseRules(semesterId: String, rules: List<HiddenCourseRule>) {
        if (semesterId.isBlank()) return
        context.scheduleSettings.edit { preferences ->
            val current = decodeHiddenCourseRules(preferences[hiddenCourseCardsKey].orEmpty()).toMutableMap()
            if (rules.isEmpty()) current.remove(semesterId) else current[semesterId] = rules
            preferences[hiddenCourseCardsKey] = encodeHiddenCourseRules(current)
        }
    }

    suspend fun clearHiddenCourseRules(semesterId: String) = setHiddenCourseRules(semesterId, emptyList())

    // ---- 自动调课（「调休调课」页的开关打开时生效） ----

    /**
     * 自动调课开关。**默认打开**（详见 [autoDayCopyEnabledFromStored]）；用户拨过关的保持关闭。
     */
    val autoDayCopyEnabled: Flow<Boolean> = context.scheduleSettings.data.map { preferences ->
        autoDayCopyEnabledFromStored(preferences[autoDayCopyEnabledKey])
    }.distinctUntilChanged()

    suspend fun setAutoDayCopyEnabled(enabled: Boolean) {
        context.scheduleSettings.edit { preferences ->
            preferences[autoDayCopyEnabledKey] = enabled
        }
    }

    /**
     * 自动调课规则。**开关关闭时恒为空表**——这是渲染层的最后一道闸门。
     *
     * 关闭开关时调用方会显式清空存储，但那是一次异步写入，期间读取方有可能拿到旧值；
     * 在这里再闸一次，任何消费方（首页、桌面小组件、编辑页）都不可能把已关闭的规则画出来。
     */
    val autoDayCopies: Flow<Map<String, List<AutoDayCopyRule>>> =
        combine(autoDayCopyEnabled, context.scheduleSettings.data) { enabled, preferences ->
            if (!enabled) emptyMap() else decodeAutoDayCopyRules(preferences[autoDayCopiesKey].orEmpty())
        }.distinctUntilChanged()

    suspend fun setAutoDayCopies(semesterId: String, rules: List<AutoDayCopyRule>) {
        if (semesterId.isBlank()) return
        context.scheduleSettings.edit { preferences ->
            val current = decodeAutoDayCopyRules(preferences[autoDayCopiesKey].orEmpty()).toMutableMap()
            if (rules.isEmpty()) current.remove(semesterId) else current[semesterId] = rules
            preferences[autoDayCopiesKey] = encodeAutoDayCopyRules(current)
        }
    }

    suspend fun clearAutoDayCopies(semesterId: String) = setAutoDayCopies(semesterId, emptyList())

    /**
     * 被用户删掉的自动规则（每学期一份）。
     *
     * 这是「默认生效、不点确认」模式下用户唯一的纠错手段，所以**不随开关关闭而清除**——
     * 那是用户的意图记录，不是自动生成的数据。要恢复得由用户显式点「恢复已忽略的自动调课」。
     */
    val autoDayCopySuppressions: Flow<Map<String, Set<String>>> =
        context.scheduleSettings.data.map { preferences ->
            decodeAutoDayCopySuppressions(preferences[autoDayCopySuppressionsKey].orEmpty())
        }.distinctUntilChanged()

    suspend fun addAutoDayCopySuppression(semesterId: String, ruleId: String) {
        if (semesterId.isBlank() || ruleId.isBlank()) return
        context.scheduleSettings.edit { preferences ->
            val current = decodeAutoDayCopySuppressions(
                preferences[autoDayCopySuppressionsKey].orEmpty()
            ).toMutableMap()
            current[semesterId] = current[semesterId].orEmpty() + ruleId
            preferences[autoDayCopySuppressionsKey] = encodeAutoDayCopySuppressions(current)
        }
    }

    suspend fun clearAutoDayCopySuppressions(semesterId: String) {
        if (semesterId.isBlank()) return
        context.scheduleSettings.edit { preferences ->
            val current = decodeAutoDayCopySuppressions(
                preferences[autoDayCopySuppressionsKey].orEmpty()
            ).toMutableMap()
            current.remove(semesterId)
            preferences[autoDayCopySuppressionsKey] = encodeAutoDayCopySuppressions(current)
        }
    }

    /** 编辑页上「这次为什么没自动补」的说明行。 */
    val autoDayCopyNotices: Flow<Map<String, List<String>>> =
        context.scheduleSettings.data.map { preferences ->
            decodeAutoCopyNotices(preferences[autoDayCopyNoticesKey].orEmpty())
        }.distinctUntilChanged()

    suspend fun setAutoCopyNotices(semesterId: String, notices: List<String>) {
        if (semesterId.isBlank()) return
        context.scheduleSettings.edit { preferences ->
            val current = decodeAutoCopyNotices(preferences[autoDayCopyNoticesKey].orEmpty()).toMutableMap()
            if (notices.isEmpty()) current.remove(semesterId) else current[semesterId] = notices
            preferences[autoDayCopyNoticesKey] = encodeAutoCopyNotices(current)
        }
    }

    /** 远端对照表原文（按学期分组的 JSON 由 `MakeupTableModels` 解析）。取数在 `MakeupTableClient`。 */
    val holidayMakeupTableJson: Flow<String> = context.scheduleSettings.data.map { preferences ->
        preferences[holidayMakeupTableKey].orEmpty()
    }.distinctUntilChanged()

    /**
     * 写入对照表原文。**写闸门**：只有真能解析成表的载荷才落盘。
     *
     * 比通知缓存严格是必要的：静态托管在路径不存在时常常返回 200 + 一个 HTML 兜底页，
     * 而「拿到就存」会让那个 HTML 被解析成「表被清空了」，把远端规则全部抹掉且无法自动恢复。
     */
    suspend fun setHolidayMakeupTableJson(json: String) {
        if (!isUsableMakeupTablePayload(json)) return
        context.scheduleSettings.edit { preferences ->
            preferences[holidayMakeupTableKey] = json
        }
    }

    suspend fun clearAll() {
        context.scheduleSettings.edit { it.clear() }
    }

    /**
     * 读取年度节假日缓存（含升级前的单一旧键兼容）。
     *
     * 读取方与写入方共用同一个函数，保证两边看到的是同一份数据——
     * 否则「写新键」会把旧键里已有的那一年挤掉。
     */
    private fun holidayCacheYearsFrom(preferences: Preferences): Map<Int, String> =
        holidayYearCache(
            storedYears = preferences[holidaysCacheYearsKey],
            legacyJson = preferences[holidaysCacheKey],
            legacyDate = preferences[holidaysCacheDateKey],
            fallbackYear = LocalDate.now().year
        )

    private fun decodeCourseColorOverrides(entries: Set<String>): Map<String, String> {
        return entries.mapNotNull { entry ->
            val separator = entry.indexOf('\u0000')
            if (separator <= 0) return@mapNotNull null
            val key = entry.substring(0, separator)
            val color = CourseColorMapper.normalizeHexColor(entry.substring(separator + 1)) ?: return@mapNotNull null
            key to color
        }.toMap()
    }

    private fun encodeCourseColorOverrides(overrides: Map<String, String>): Set<String> {
        return overrides.mapNotNull { (key, color) ->
            CourseColorMapper.normalizeHexColor(color)?.let { "$key\u0000$it" }
        }.toSet()
    }

    private fun classPeriodsKey(campus: CampusType) = when (campus) {
        CampusType.GUILIN -> guilinClassPeriodsKey
        CampusType.NANNING -> nanningClassPeriodsKey
        CampusType.GRADUATE -> graduateClassPeriodsKey
    }

    override suspend fun read(): GreetingTemplateCacheSnapshot {
        val preferences = context.scheduleSettings.data.first()
        return GreetingTemplateCacheSnapshot(
            rawJson = preferences[greetingTemplatesJsonKey].orEmpty(),
            lastSuccessEpochMillis = preferences[greetingTemplatesSuccessAtKey] ?: 0L,
            lastAttemptEpochMillis = preferences[greetingTemplatesAttemptAtKey] ?: 0L
        )
    }

    override suspend fun recordAttempt(nowEpochMillis: Long) {
        context.scheduleSettings.edit { preferences ->
            preferences[greetingTemplatesAttemptAtKey] = nowEpochMillis
        }
    }

    override suspend fun saveSuccess(rawJson: String, nowEpochMillis: Long) {
        context.scheduleSettings.edit { preferences ->
            preferences[greetingTemplatesJsonKey] = rawJson
            preferences[greetingTemplatesSuccessAtKey] = nowEpochMillis
            preferences[greetingTemplatesAttemptAtKey] = nowEpochMillis
        }
    }

    private fun classPeriodsKey(profile: ClassPeriodProfile) = when (profile) {
        ClassPeriodProfile.GUILIN_YANSHAN -> guilinYanshanClassPeriodsKey
        ClassPeriodProfile.GUILIN_PINGFENG -> guilinPingfengClassPeriodsKey
        ClassPeriodProfile.NANNING -> nanningClassPeriodsKey
        ClassPeriodProfile.GRADUATE -> graduateClassPeriodsKey
    }

    /**
     * 在更改 pill 之前，把旧桂林配置固定到旧选择对应的档案，避免配置随选择漂移。
     */
    private fun migrateLegacyGuilinClassPeriods(preferences: MutablePreferences) {
        val legacy = preferences[guilinClassPeriodsKey] ?: return
        val targetKey = when (
            GuilinSubCampus.fromStorageValue(preferences[guilinSubCampusKey])
        ) {
            GuilinSubCampus.YANSHAN -> guilinYanshanClassPeriodsKey
            GuilinSubCampus.PINGFENG -> guilinPingfengClassPeriodsKey
        }
        if (preferences[targetKey].isNullOrEmpty()) {
            preferences[targetKey] = legacy
        }
        preferences.remove(guilinClassPeriodsKey)
    }
}

internal fun greetingEnabledFromStored(value: Boolean?): Boolean = value ?: true

/**
 * 自动调课开关的默认值：**打开**。
 *
 * 新增键对老用户即为打开，不需要任何一次性迁移：学校调休本来就是要补课的，不该要求用户先去
 * 某个二级页面把开关拨开才能看到正确的课表。**显式关过的人（存了 `false`）保持关闭**——拨过
 * 开关的选择不会被这次默认值改动覆盖。
 *
 * 抽成纯函数是为了让测试能直接钉住上面这两条。
 */
internal fun autoDayCopyEnabledFromStored(value: Boolean?): Boolean = value ?: true

internal fun encodeClassPeriods(periods: List<ClassPeriod>): Set<String> {
    return periods.map { period ->
        "${period.section}\u0000${period.startsAt}\u0000${period.endsAt}"
    }.toSet()
}

internal fun decodeClassPeriods(
    campus: CampusType,
    entries: Set<String>
): List<ClassPeriod>? {
    if (entries.isEmpty()) return null
    val periods = entries.mapNotNull { entry ->
        val parts = entry.split('\u0000')
        if (parts.size != 3) return@mapNotNull null
        ClassPeriod(
            section = parts[0].toIntOrNull() ?: return@mapNotNull null,
            startsAt = parts[1],
            endsAt = parts[2]
        )
    }
    if (periods.size != entries.size) return null
    val sorted = periods.sortedBy { it.section }
    return sorted.takeIf { validateClassPeriods(campus, it) }
}

internal fun decodeClassPeriodOverrides(
    selectedSubCampus: GuilinSubCampus,
    legacyGuilinEntries: Set<String>,
    yanshanEntries: Set<String>,
    pingfengEntries: Set<String>,
    nanningEntries: Set<String>,
    graduateEntries: Set<String> = emptySet()
): Map<ClassPeriodProfile, List<ClassPeriod>> = buildMap {
    decodeClassPeriods(CampusType.GUILIN, yanshanEntries)
        ?.let { put(ClassPeriodProfile.GUILIN_YANSHAN, it) }
    decodeClassPeriods(CampusType.GUILIN, pingfengEntries)
        ?.let { put(ClassPeriodProfile.GUILIN_PINGFENG, it) }
    decodeClassPeriods(CampusType.NANNING, nanningEntries)
        ?.let { put(ClassPeriodProfile.NANNING, it) }
    // 研究生整份时间可以为空，只要结构是 1..12 且「要么全空、要么全填且不重叠」即视为有效覆盖。
    decodeClassPeriods(CampusType.GRADUATE, graduateEntries)
        ?.let { put(ClassPeriodProfile.GRADUATE, it) }

    // 旧版只有一份桂林配置；仅在目标档案没有新值时归入升级前选中的子校区。
    val legacyProfile = when (selectedSubCampus) {
        GuilinSubCampus.YANSHAN -> ClassPeriodProfile.GUILIN_YANSHAN
        GuilinSubCampus.PINGFENG -> ClassPeriodProfile.GUILIN_PINGFENG
    }
    if (legacyProfile !in this) {
        decodeClassPeriods(CampusType.GUILIN, legacyGuilinEntries)
            ?.let { put(legacyProfile, it) }
    }
}
