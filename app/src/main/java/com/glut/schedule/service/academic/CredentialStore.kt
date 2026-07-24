package com.glut.schedule.service.academic

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class CredentialStore(context: Context) {

    // 加密存储优先；密钥库不可用（部分设备/系统故障）时回退明文 SharedPreferences，
    // 避免应用启动崩溃。
    private val prefs: SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "glut_secure_credentials",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrElse {
        android.util.Log.w("CredentialStore", "EncryptedSharedPreferences unavailable, falling back to plain storage", it)
        context.getSharedPreferences("glut_credentials_fallback", Context.MODE_PRIVATE)
    }

    fun saveCredentials(username: String, password: String) {
        prefs.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun getUsername(): String = prefs.getString(KEY_USERNAME, null).orEmpty()

    fun getPassword(): String = prefs.getString(KEY_PASSWORD, null).orEmpty()

    fun hasCredentials(): Boolean = getUsername().isNotBlank() && getPassword().isNotBlank()

    fun clearCredentials() {
        prefs.edit().remove(KEY_USERNAME).remove(KEY_PASSWORD).commit()
    }

    companion object {
        private const val KEY_USERNAME = "enc_username"
        private const val KEY_PASSWORD = "enc_password"
    }
}
