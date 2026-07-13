package com.glut.schedule.service.fitness

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class FitnessStore(context: Context) {
    private val securePrefs: SharedPreferences
    private val cachePrefs: SharedPreferences =
        context.getSharedPreferences("fitness_cache", Context.MODE_PRIVATE)

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        securePrefs = EncryptedSharedPreferences.create(
            context,
            "fitness_secure_data",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        migrateCacheIfNeeded()
    }

    fun saveCredentials(username: String, password: String) {
        securePrefs.edit()
            .putString(KEY_USERNAME, username.trim())
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun getUsername(): String = securePrefs.getString(KEY_USERNAME, "").orEmpty()

    fun getPassword(): String = securePrefs.getString(KEY_PASSWORD, "").orEmpty()

    fun saveSession(cookie: String) {
        securePrefs.edit().putString(KEY_COOKIE, cookie).apply()
    }

    fun getSession(): String = securePrefs.getString(KEY_COOKIE, "").orEmpty()

    fun clearSession() {
        securePrefs.edit().remove(KEY_COOKIE).commit()
    }

    fun saveSnapshot(currentHtml: String, historyHtml: String) {
        cachePrefs.edit()
            .putString(KEY_CURRENT_HTML, currentHtml)
            .putString(KEY_HISTORY_HTML, historyHtml)
            .apply()
    }

    fun getCurrentHtml(): String = cachePrefs.getString(KEY_CURRENT_HTML, "").orEmpty()

    fun getHistoryHtml(): String = cachePrefs.getString(KEY_HISTORY_HTML, "").orEmpty()

    fun saveHistoryDetail(year: String, term: String, html: String) {
        cachePrefs.edit().putString(historyDetailKey(year, term), html).apply()
    }

    fun getHistoryDetail(year: String, term: String): String =
        cachePrefs.getString(historyDetailKey(year, term), "").orEmpty()

    fun saveStandard(html: String) {
        cachePrefs.edit().putString(KEY_STANDARD_HTML, html).apply()
    }

    fun getStandardHtml(): String = cachePrefs.getString(KEY_STANDARD_HTML, "").orEmpty()

    fun clearAll() {
        securePrefs.edit().clear().commit()
        cachePrefs.edit().clear()
            .putInt(KEY_HISTORY_CACHE_VERSION, HISTORY_CACHE_VERSION)
            .putInt(KEY_STANDARD_CACHE_VERSION, STANDARD_CACHE_VERSION)
            .commit()
    }

    private fun migrateCacheIfNeeded() {
        val editor = cachePrefs.edit()
        if (cachePrefs.getInt(KEY_HISTORY_CACHE_VERSION, 0) != HISTORY_CACHE_VERSION) {
            cachePrefs.all.keys.filter { it.startsWith(KEY_HISTORY_DETAIL_PREFIX) }
                .forEach(editor::remove)
            editor.putInt(KEY_HISTORY_CACHE_VERSION, HISTORY_CACHE_VERSION)
        }
        if (cachePrefs.getInt(KEY_STANDARD_CACHE_VERSION, 0) != STANDARD_CACHE_VERSION) {
            editor.remove(KEY_STANDARD_HTML)
            editor.putInt(KEY_STANDARD_CACHE_VERSION, STANDARD_CACHE_VERSION)
        }
        editor.apply()
    }

    private fun historyDetailKey(year: String, term: String): String =
        "$KEY_HISTORY_DETAIL_PREFIX${year.trim()}_${term.trim()}"

    companion object {
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_COOKIE = "cookie"
        private const val KEY_CURRENT_HTML = "current_html"
        private const val KEY_HISTORY_HTML = "history_html"
        private const val KEY_HISTORY_DETAIL_PREFIX = "history_detail_"
        private const val KEY_STANDARD_HTML = "standard_html"
        private const val KEY_HISTORY_CACHE_VERSION = "history_cache_version"
        private const val KEY_STANDARD_CACHE_VERSION = "standard_cache_version"
        private const val HISTORY_CACHE_VERSION = 2
        private const val STANDARD_CACHE_VERSION = 2
    }
}
