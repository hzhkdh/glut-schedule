package com.glut.schedule.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.glut.schedule.data.model.DEFAULT_SEMESTER_END_DATE
import com.glut.schedule.data.model.DEFAULT_SEMESTER_START_MONDAY
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
    private val semesterStartMondayKey = stringPreferencesKey("semester_start_monday")
    private val semesterEndDateKey = stringPreferencesKey("semester_end_date")
    private val customBackgroundUriKey = stringPreferencesKey("custom_background_uri")
    private val campusTypeKey = stringPreferencesKey("campus_type")

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
}
