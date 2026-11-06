package com.glut.schedule.service.academic

import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.academicSessionDataStore by preferencesDataStore(name = "academic_session")

/** 当前教务 Cookie 的建立方式，用于在失效时选对续期链路。 */
enum class AcademicSessionAuthMethod {
    PASSWORD,
    WECHAT_SSO
}

/** 同一个 DataStore 版本的身份与会话，避免姓名和学号分开订阅时短暂错配。 */
data class AcademicProfileSession(val name: String = "", val studentNo: String = "", val base: String = "", val cookie: String = "", val snapshot: PersonalCenterSnapshot? = null)

class AcademicSessionStore(
    private val context: Context
) : RenewalSessionWriter {
    // 独立加密存储不进入课表数据库、分享导出或日志；密钥不可用时禁止明文降级。
    private val profilePrefs by lazy {
        runCatching {
            EncryptedSharedPreferences.create(context, "glut_secure_personal_snapshot",
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        }.getOrNull()
    }

    private fun readSnapshot(base: String, studentNo: String): PersonalCenterSnapshot? = runCatching {
        val json = JSONObject(profilePrefs?.getString("snapshot", null) ?: return@runCatching null)
        val data = json.getJSONObject("values")
        PersonalCenterSnapshot(json.getString("base"), json.getString("studentNo"),
            PersonalCenterParser.fields.mapNotNull { field -> data.optString(field.key).takeIf { it.isNotBlank() }?.let { field.key to it } }.toMap(),
            json.getLong("updatedAt")).takeIf { it.matches(base, studentNo) }
    }.getOrNull()

    /** 与会话更新串行校验，拒绝跨账号、跨来源以及旧 Cookie 的迟到结果。 */
    suspend fun saveProfileSnapshotIfCurrent(expectedCookie: String, snapshot: PersonalCenterSnapshot) {
        context.academicSessionDataStore.edit { preferences ->
            val base = AcademicUrlPolicy.normalizeCampusBaseUrl(preferences[campusUrlKey].orEmpty())
            if (preferences[cookieKey].orEmpty() == expectedCookie && snapshot.matches(base, preferences[authenticatedStudentNumberKey].orEmpty())) {
                val json = JSONObject().put("base", snapshot.base).put("studentNo", snapshot.studentNo)
                    .put("updatedAt", snapshot.updatedAt).put("values", JSONObject(snapshot.values))
                profilePrefs?.edit()?.putString("snapshot", json.toString())?.commit()
                // 触发同一身份的订阅更新，不改变快照的真实采集时间。
                preferences[profileRevisionKey] = java.util.UUID.randomUUID().toString()
            }
        }
    }

    private val profileRevisionKey = stringPreferencesKey("profile_snapshot_revision")
    private val cookieKey = stringPreferencesKey("academic_cookie")
    private val lastHtmlPreviewKey = stringPreferencesKey("last_timetable_html_preview")
    private val examApiUrlKey = stringPreferencesKey("exam_api_url")
    private val campusUrlKey = stringPreferencesKey("campus_base_url")
    private val timetableUrlKey = stringPreferencesKey("timetable_url")
    private val authenticatedStudentNumberKey = stringPreferencesKey("authenticated_student_number")
    private val authenticatedStudentNameKey = stringPreferencesKey("authenticated_student_name")
    private val authMethodKey = stringPreferencesKey("academic_session_auth_method")

    val profileSession: Flow<AcademicProfileSession> = context.academicSessionDataStore.data.map { preferences ->
        AcademicProfileSession(preferences[authenticatedStudentNameKey].orEmpty(), preferences[authenticatedStudentNumberKey].orEmpty(),
            AcademicUrlPolicy.normalizeCampusBaseUrl(preferences[campusUrlKey].orEmpty()), preferences[cookieKey].orEmpty(),
            readSnapshot(AcademicUrlPolicy.normalizeCampusBaseUrl(preferences[campusUrlKey].orEmpty()), preferences[authenticatedStudentNumberKey].orEmpty()))
    }

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
        AcademicUrlPolicy.normalizeCampusBaseUrl(preferences[campusUrlKey].orEmpty())
    }

    val authenticatedStudentNumber: Flow<String> = context.academicSessionDataStore.data.map { preferences ->
        preferences[authenticatedStudentNumberKey].orEmpty()
    }

    val authenticatedStudentName: Flow<String> = context.academicSessionDataStore.data.map { preferences ->
        preferences[authenticatedStudentNameKey].orEmpty()
    }

    val sessionAuthMethod: Flow<AcademicSessionAuthMethod> = context.academicSessionDataStore.data.map { preferences ->
        when (preferences[authMethodKey]) {
            AcademicSessionAuthMethod.WECHAT_SSO.name -> AcademicSessionAuthMethod.WECHAT_SSO
            else -> AcademicSessionAuthMethod.PASSWORD
        }
    }

    override suspend fun saveCookie(cookie: String) {
        context.academicSessionDataStore.edit { preferences ->
            preferences[cookieKey] = cookie
        }
    }

    suspend fun saveSessionAuthMethod(method: AcademicSessionAuthMethod) {
        context.academicSessionDataStore.edit { preferences ->
            preferences[authMethodKey] = method.name
        }
    }

    /** 资料请求可能轮换 Cookie，但旧账号响应不能覆盖刚导入的新会话。 */
    suspend fun saveProfileCookieIfCurrent(expectedCookie: String, studentNo: String, base: String, cookie: String) {
        if (cookie.isBlank()) return
        context.academicSessionDataStore.edit { preferences ->
            if(preferences[cookieKey].orEmpty() == expectedCookie && preferences[authenticatedStudentNumberKey].orEmpty() == studentNo &&
                AcademicUrlPolicy.normalizeCampusBaseUrl(preferences[campusUrlKey].orEmpty()) == base) preferences[cookieKey] = cookie
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

    /** 续期成功后把门户认到的学号补上；直接复用既有的写入实现。 */
    override suspend fun saveStudentNumber(studentNumber: String) {
        saveAuthenticatedStudentNumber(studentNumber)
    }

    override suspend fun saveCampusBaseUrl(url: String) {
        context.academicSessionDataStore.edit { preferences ->
            preferences[campusUrlKey] = AcademicUrlPolicy.normalizeCampusBaseUrl(url)
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
        context.academicSessionDataStore.edit {
            profilePrefs?.edit()?.clear()?.commit()
            it.clear()
        }
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
