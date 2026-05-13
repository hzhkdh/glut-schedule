package com.glut.schedule.service.academic

import okhttp3.OkHttpClient
import okhttp3.Request
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

    suspend fun silentLogin(): Boolean {
        val username = credentialStore.getUsername()
        val password = credentialStore.getPassword()
        if (username.isBlank() || password.isBlank()) return false

        val encodedUser = java.net.URLEncoder.encode(username, "UTF-8")
        val encodedPass = java.net.URLEncoder.encode(password, "UTF-8")
        val loginUrl = "http://jw.glut.edu.cn/academic/j_acegi_security_check" +
            "?j_username=$encodedUser&j_password=$encodedPass&j_captcha="

        return try {
            val request = Request.Builder()
                .url(loginUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                // Login success = 302 redirect that sets JSESSIONID
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
