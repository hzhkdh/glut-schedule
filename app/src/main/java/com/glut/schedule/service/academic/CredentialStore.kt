package com.glut.schedule.service.academic

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class CredentialStore(private val context: Context) {

    // 密钥库不可用时采用失败关闭：禁用持久化，而不是把教务密码降级为明文保存。
    private val prefs: SharedPreferences? = runCatching {
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
    }.onFailure {
        android.util.Log.w("CredentialStore", "EncryptedSharedPreferences unavailable; credential persistence disabled")
    }.getOrNull()

    fun saveCredentials(username: String, password: String) {
        prefs?.edit()
            ?.putString(KEY_USERNAME, username)
            ?.putString(KEY_PASSWORD, password)
            ?.apply()
    }

    fun getUsername(): String = prefs?.getString(KEY_USERNAME, null).orEmpty()

    fun getPassword(): String = prefs?.getString(KEY_PASSWORD, null).orEmpty()

    fun hasCredentials(): Boolean = getUsername().isNotBlank() && getPassword().isNotBlank()

    fun canPersistCredentials(): Boolean = prefs != null

    fun clearCredentials() {
        prefs?.edit()
            ?.remove(KEY_USERNAME)
            ?.remove(KEY_PASSWORD)
            ?.apply()
        // 清理旧版本可能创建的明文回退文件，避免升级后继续遗留密码。
        context.getSharedPreferences("glut_credentials_fallback", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    companion object {
        private const val KEY_USERNAME = "enc_username"
        private const val KEY_PASSWORD = "enc_password"
    }
}
