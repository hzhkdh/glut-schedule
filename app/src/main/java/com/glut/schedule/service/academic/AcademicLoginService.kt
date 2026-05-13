package com.glut.schedule.service.academic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class AcademicLoginService(
    private val sessionStore: AcademicSessionStore,
    private val credentialStore: CredentialStore
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    suspend fun silentLogin(): Boolean = withContext(Dispatchers.IO) {
        val username = credentialStore.getUsername()
        val password = credentialStore.getPassword()
        if (username.isBlank() || password.isBlank()) return@withContext false

        val encodedUser = URLEncoder.encode(username, "UTF-8")
        val encodedPass = URLEncoder.encode(password, "UTF-8")
        val loginUrl = "http://jw.glut.edu.cn/academic/j_acegi_security_check" +
            "?j_username=$encodedUser&j_password=$encodedPass&j_captcha="

        try {
            val request = Request.Builder()
                .url(loginUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val setCookie = response.headers("Set-Cookie")
                val jsessionid = setCookie.firstNotNullOfOrNull { header ->
                    Regex("""JSESSIONID=([^;]+)""").find(header)?.groupValues?.get(1)
                }
                if (jsessionid != null) {
                    sessionStore.saveCookie("JSESSIONID=$jsessionid")
                    true
                } else {
                    false
                }
            }
        } catch (_: Exception) {
            false
        }
    }
}
