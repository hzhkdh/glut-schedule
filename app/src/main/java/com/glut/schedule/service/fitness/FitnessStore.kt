package com.glut.schedule.service.fitness

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

interface FitnessStorage {
    fun saveCredentials(username: String, password: String)
    fun getUsername(): String
    fun getPassword(): String
    fun saveSession(cookie: String)
    fun getSession(): String
    fun clearSession()
    fun saveSnapshot(currentHtml: String, historyHtml: String)
    fun getCurrentHtml(): String
    fun getHistoryHtml(): String
    fun saveHistoryDetail(year: String, term: String, html: String)
    fun getHistoryDetail(year: String, term: String): String
    fun saveStandard(html: String)
    fun getStandardHtml(): String
    fun clearAccountCache()
    fun clearAll()
}

class FitnessStore(context: Context) : FitnessStorage {
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

    override fun saveCredentials(username: String, password: String) {
        securePrefs.edit()
            .putString(KEY_USERNAME, username.trim())
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    override fun getUsername(): String = securePrefs.getString(KEY_USERNAME, "").orEmpty()

    override fun getPassword(): String = securePrefs.getString(KEY_PASSWORD, "").orEmpty()

    override fun saveSession(cookie: String) {
        securePrefs.edit().putString(KEY_COOKIE, cookie).apply()
    }

    override fun getSession(): String = securePrefs.getString(KEY_COOKIE, "").orEmpty()

    override fun clearSession() {
        securePrefs.edit().remove(KEY_COOKIE).commit()
    }

    override fun saveSnapshot(currentHtml: String, historyHtml: String) {
        cachePrefs.edit()
            .putString(KEY_CURRENT_HTML, currentHtml)
            .putString(KEY_HISTORY_HTML, historyHtml)
            .apply()
    }

    override fun getCurrentHtml(): String = cachePrefs.getString(KEY_CURRENT_HTML, "").orEmpty()

    override fun getHistoryHtml(): String = cachePrefs.getString(KEY_HISTORY_HTML, "").orEmpty()

    override fun saveHistoryDetail(year: String, term: String, html: String) {
        cachePrefs.edit().putString(historyDetailKey(year, term), html).apply()
    }

    override fun getHistoryDetail(year: String, term: String): String =
        cachePrefs.getString(historyDetailKey(year, term), "").orEmpty()

    override fun saveStandard(html: String) {
        cachePrefs.edit().putString(KEY_STANDARD_HTML, html).apply()
    }

    override fun getStandardHtml(): String = cachePrefs.getString(KEY_STANDARD_HTML, "").orEmpty()

    override fun clearAccountCache() {
        securePrefs.edit().remove(KEY_COOKIE).commit()
        val editor = cachePrefs.edit()
            .remove(KEY_CURRENT_HTML)
            .remove(KEY_HISTORY_HTML)
        cachePrefs.all.keys.filter { it.startsWith(KEY_HISTORY_DETAIL_PREFIX) }
            .forEach(editor::remove)
        editor.commit()
    }

    override fun clearAll() {
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
