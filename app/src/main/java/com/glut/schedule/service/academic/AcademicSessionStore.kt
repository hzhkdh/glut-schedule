package com.glut.schedule.service.academic

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.academicSessionDataStore by preferencesDataStore(name = "academic_session")

class AcademicSessionStore(
    private val context: Context
) {
    private val cookieKey = stringPreferencesKey("academic_cookie")
    private val lastHtmlPreviewKey = stringPreferencesKey("last_timetable_html_preview")

    val academicCookie: Flow<String> = context.academicSessionDataStore.data.map { preferences ->
        preferences[cookieKey].orEmpty()
    }

    val lastHtmlPreview: Flow<String> = context.academicSessionDataStore.data.map { preferences ->
        preferences[lastHtmlPreviewKey].orEmpty()
    }

    suspend fun saveCookie(cookie: String) {
        context.academicSessionDataStore.edit { preferences ->
            preferences[cookieKey] = cookie
        }
    }

    suspend fun saveHtmlPreview(html: String) {
        context.academicSessionDataStore.edit { preferences ->
            preferences[lastHtmlPreviewKey] = html.take(1200)
        }
    }
}
