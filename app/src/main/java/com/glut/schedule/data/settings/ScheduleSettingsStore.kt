package com.glut.schedule.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.glut.schedule.data.model.DEFAULT_SEMESTER_END_DATE
import com.glut.schedule.data.model.DEFAULT_SEMESTER_START_MONDAY
import com.glut.schedule.data.model.CourseColorMapper
import com.glut.schedule.data.model.clampAcademicWeek
import com.glut.schedule.data.model.normalizeSemesterStartMonday
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

enum class CampusType { GUILIN, NANNING }

private val Context.scheduleSettings by preferencesDataStore(name = "schedule_settings")

class ScheduleSettingsStore(
    private val context: Context
) {
    private val currentWeekKey = intPreferencesKey("current_week")
    private val showWeekendKey = booleanPreferencesKey("show_weekend")
    private val showNoonKey = booleanPreferencesKey("show_noon")
    private val semesterStartMondayKey = stringPreferencesKey("semester_start_monday")
    private val semesterEndDateKey = stringPreferencesKey("semester_end_date")
    private val customBackgroundUriKey = stringPreferencesKey("custom_background_uri")
    private val courseColorOverridesKey = stringSetPreferencesKey("course_color_overrides")
    private val campusTypeKey = stringPreferencesKey("campus_type")
    private val updateAvailableVersionKey = stringPreferencesKey("update_available_version")
    private val dismissedUpdatePopupVersionKey = stringPreferencesKey("dismissed_update_popup_version")
    private val cachedNoticesJsonKey = stringPreferencesKey("cached_notices_json")
    private val readNoticeIdsKey = stringSetPreferencesKey("read_notice_ids")
    private val dismissedNoticePopupIdsKey = stringSetPreferencesKey("dismissed_notice_popup_ids")
    private val holidaysCacheKey = stringPreferencesKey("holidays_cache")
    private val holidaysCacheDateKey = stringPreferencesKey("holidays_cache_date")

    val currentWeekNumber: Flow<Int> = context.scheduleSettings.data.map { preferences ->
        clampAcademicWeek(preferences[currentWeekKey] ?: 9)
    }

    val showWeekend: Flow<Boolean> = context.scheduleSettings.data.map { preferences ->
        preferences[showWeekendKey] ?: false
    }

    val semesterStartMonday: Flow<LocalDate> = context.scheduleSettings.data.map { preferences ->
        preferences[semesterStartMondayKey]
            ?.let { value -> runCatching { LocalDate.parse(value) }.getOrNull() }
            ?.let(::normalizeSemesterStartMonday)
            ?: DEFAULT_SEMESTER_START_MONDAY
    }

    val semesterEndDate: Flow<LocalDate> = context.scheduleSettings.data.map { preferences ->
        preferences[semesterEndDateKey]
            ?.let { value -> runCatching { LocalDate.parse(value) }.getOrNull() }
            ?: DEFAULT_SEMESTER_END_DATE
    }

    val customBackgroundUri: Flow<String> = context.scheduleSettings.data.map { preferences ->
        preferences[customBackgroundUriKey].orEmpty()
    }

    val courseColorOverrides: Flow<Map<String, String>> = context.scheduleSettings.data.map { preferences ->
        decodeCourseColorOverrides(preferences[courseColorOverridesKey].orEmpty())
    }

    val campusType: Flow<CampusType> = context.scheduleSettings.data.map { preferences ->
        val name = preferences[campusTypeKey] ?: CampusType.GUILIN.name
        runCatching { CampusType.valueOf(name) }.getOrDefault(CampusType.GUILIN)
    }

    suspend fun setCampusType(type: CampusType) {
        context.scheduleSettings.edit { preferences ->
            preferences[campusTypeKey] = type.name
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

    val showNoon: Flow<Boolean> = context.scheduleSettings.data.map { preferences ->
        preferences[showNoonKey] ?: false
    }

    suspend fun setShowNoon(showNoon: Boolean) {
        context.scheduleSettings.edit { preferences ->
            preferences[showNoonKey] = showNoon
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
        context.scheduleSettings.edit { preferences ->
            val trimmed = uri.trim()
            if (trimmed.isBlank()) {
                preferences.remove(customBackgroundUriKey)
            } else {
                preferences[customBackgroundUriKey] = trimmed
            }
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

    /** Cached holiday JSON from timor.tech API, paired with the date it was fetched. */
    val holidaysCache: Flow<Pair<String, String>> = context.scheduleSettings.data.map { preferences ->
        val json = preferences[holidaysCacheKey].orEmpty()
        val date = preferences[holidaysCacheDateKey].orEmpty()
        json to date
    }

    suspend fun setHolidaysCache(json: String) {
        context.scheduleSettings.edit { preferences ->
            preferences[holidaysCacheKey] = json
            preferences[holidaysCacheDateKey] = LocalDate.now().toString()
        }
    }

    suspend fun clearAll() {
        context.scheduleSettings.edit { it.clear() }
    }

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
}
