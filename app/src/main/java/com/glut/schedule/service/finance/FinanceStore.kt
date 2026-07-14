package com.glut.schedule.service.finance

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.glut.schedule.data.model.CachedFinancePayload
import com.glut.schedule.data.model.FinanceCredentials
import com.glut.schedule.data.model.FinanceModule

interface FinanceStorage {
    fun credentials(): FinanceCredentials
    fun saveCredentials(value: FinanceCredentials)
    fun sessionCookie(): String
    fun saveSessionCookie(value: String)
    fun clearSession()
    fun module(module: FinanceModule): CachedFinancePayload?
    fun saveModule(module: FinanceModule, value: CachedFinancePayload)
    fun clearAll()
}

class FinanceStore(context: Context) : FinanceStorage {
    private val securePrefs: SharedPreferences
    private val cachePrefs = context.getSharedPreferences("finance_cache", Context.MODE_PRIVATE)

    init {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        securePrefs = EncryptedSharedPreferences.create(
            context,
            "finance_secure_data",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        if (cachePrefs.getInt(KEY_SCHEMA, 0) != CACHE_SCHEMA) {
            cachePrefs.edit().clear().putInt(KEY_SCHEMA, CACHE_SCHEMA).apply()
        }
    }

    override fun credentials() = FinanceCredentials(
        securePrefs.getString(KEY_USERNAME, "").orEmpty(),
        securePrefs.getString(KEY_PASSWORD, "").orEmpty()
    )

    override fun saveCredentials(value: FinanceCredentials) {
        securePrefs.edit().putString(KEY_USERNAME, value.username.trim()).putString(KEY_PASSWORD, value.password).apply()
    }

    override fun sessionCookie(): String = securePrefs.getString(KEY_COOKIE, "").orEmpty()
    override fun saveSessionCookie(value: String) { securePrefs.edit().putString(KEY_COOKIE, value).apply() }
    override fun clearSession() { securePrefs.edit().remove(KEY_COOKIE).commit() }

    override fun module(module: FinanceModule): CachedFinancePayload? {
        val raw = cachePrefs.getString(dataKey(module), null) ?: return null
        return runCatching {
            CachedFinancePayload(FinanceCacheCodec.decode(raw), cachePrefs.getLong(timeKey(module), 0L))
        }.getOrNull()
    }

    override fun saveModule(module: FinanceModule, value: CachedFinancePayload) {
        cachePrefs.edit()
            .putString(dataKey(module), FinanceCacheCodec.encode(value.payload))
            .putLong(timeKey(module), value.savedAt)
            .apply()
    }

    override fun clearAll() {
        securePrefs.edit().clear().commit()
        cachePrefs.edit().clear().putInt(KEY_SCHEMA, CACHE_SCHEMA).commit()
    }

    private fun dataKey(module: FinanceModule) = "module_${module.key}_data"
    private fun timeKey(module: FinanceModule) = "module_${module.key}_time"

    companion object {
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_COOKIE = "cookie"
        private const val KEY_SCHEMA = "schema_version"
        private const val CACHE_SCHEMA = 1
    }
}
