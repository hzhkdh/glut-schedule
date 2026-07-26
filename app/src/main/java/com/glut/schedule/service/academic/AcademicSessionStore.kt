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
    private val examApiUrlKey = stringPreferencesKey("exam_api_url")
    private val campusUrlKey = stringPreferencesKey("campus_base_url")
    private val timetableUrlKey = stringPreferencesKey("timetable_url")
    private val authenticatedStudentNumberKey = stringPreferencesKey("authenticated_student_number")
    private val authenticatedStudentNameKey = stringPreferencesKey("authenticated_student_name")

    val academicCookie: Flow<String> = context.academicSessionDataStore.data.map { preferences ->
        preferences[cookieKey].orEmpty()
    }

    val lastHtmlPreview: Flow<String> = context.academicSessionDataStore.data.map { preferences ->
        preferences[lastHtmlPreviewKey].orEmpty()
    }

    val examApiUrl: Flow<String> = context.academicSessionDataStore.data.map { preferences ->
        preferences[examApiUrlKey].orEmpty()
    }

    val campusBaseUrl: Flow<String> = context.academicSessionDataStore.data.map { preferences ->
        preferences[campusUrlKey].orEmpty()
    }

    val authenticatedStudentNumber: Flow<String> = context.academicSessionDataStore.data.map { preferences ->
        preferences[authenticatedStudentNumberKey].orEmpty()
    }

    val authenticatedStudentName: Flow<String> = context.academicSessionDataStore.data.map { preferences ->
        preferences[authenticatedStudentNameKey].orEmpty()
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

    suspend fun saveExamApiUrl(url: String) {
        context.academicSessionDataStore.edit { preferences ->
            preferences[examApiUrlKey] = url
        }
    }

    suspend fun saveCampusBaseUrl(url: String) {
        context.academicSessionDataStore.edit { preferences ->
            preferences[campusUrlKey] = url
        }
    }

    suspend fun saveAuthenticatedStudentNumber(studentNumber: String) {
        saveAuthenticatedStudent(studentNumber, null)
    }

    /**
     * 学号与姓名在同一次 DataStore 事务中更新，确保切换账号时不会读到旧账号姓名。
     * 同账号偶发解析失败时保留已知姓名；新账号解析失败则清空姓名。
     */
    suspend fun saveAuthenticatedStudent(studentNumber: String, parsedStudentName: String?) {
        context.academicSessionDataStore.edit { preferences ->
            val resolved = resolveAuthenticatedStudent(
                existingStudentNumber = preferences[authenticatedStudentNumberKey].orEmpty(),
                existingStudentName = preferences[authenticatedStudentNameKey].orEmpty(),
                newStudentNumber = studentNumber,
                parsedStudentName = parsedStudentName
            )
            preferences[authenticatedStudentNumberKey] = resolved.studentNumber
            if (resolved.studentName.isBlank()) {
                preferences.remove(authenticatedStudentNameKey)
            } else {
                preferences[authenticatedStudentNameKey] = resolved.studentName
            }
        }
    }

    val timetableUrl: Flow<String> = context.academicSessionDataStore.data.map { preferences ->
        preferences[timetableUrlKey].orEmpty()
    }

    suspend fun saveTimetableUrl(url: String) {
        context.academicSessionDataStore.edit { preferences ->
            preferences[timetableUrlKey] = url
        }
    }

    suspend fun clearAll() {
        context.academicSessionDataStore.edit { it.clear() }
    }
}

data class AuthenticatedStudentIdentity(
    val studentNumber: String,
    val studentName: String
)

internal fun resolveAuthenticatedStudent(
    existingStudentNumber: String,
    existingStudentName: String,
    newStudentNumber: String,
    parsedStudentName: String?
): AuthenticatedStudentIdentity {
    val normalizedNumber = newStudentNumber.trim()
    val normalizedParsedName = parsedStudentName?.trim().orEmpty()
    val resolvedName = when {
        normalizedParsedName.isNotBlank() -> normalizedParsedName
        existingStudentNumber.trim() == normalizedNumber -> existingStudentName.trim()
        else -> ""
    }
    return AuthenticatedStudentIdentity(normalizedNumber, resolvedName)
}
