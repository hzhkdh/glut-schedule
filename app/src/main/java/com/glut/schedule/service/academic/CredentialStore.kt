package com.glut.schedule.service.academic

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class CredentialStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "glut_secure_credentials",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveCredentials(username: String, password: String) {
        prefs.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun getUsername(): String = prefs.getString(KEY_USERNAME, null).orEmpty()

    fun getPassword(): String = prefs.getString(KEY_PASSWORD, null).orEmpty()

    fun hasCredentials(): Boolean = getUsername().isNotBlank() && getPassword().isNotBlank()

    companion object {
        private const val KEY_USERNAME = "enc_username"
        private const val KEY_PASSWORD = "enc_password"
    }
}
