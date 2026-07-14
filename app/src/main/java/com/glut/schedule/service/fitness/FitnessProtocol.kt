package com.glut.schedule.service.fitness

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import okhttp3.HttpUrl

object FitnessCookies {
    fun merge(current: String, setCookies: List<String>): String {
        val values = linkedMapOf<String, String>()
        current.split(';').map(String::trim).filter { it.contains('=') }.forEach { pair ->
            val (name, value) = pair.split('=', limit = 2)
            if (name.isNotBlank()) values[name] = value
        }
        setCookies.forEach { header ->
            val pair = header.substringBefore(';').trim()
            if (pair.contains('=')) {
                val (name, value) = pair.split('=', limit = 2)
                if (name.isNotBlank()) values[name] = value
            }
        }
        return values.entries.joinToString("; ") { (name, value) -> "$name=$value" }
    }
}

object FitnessLoginPage {
    private val SECRET_KEY = Regex("""(?:var\s+)?secretKey\s*=\s*['\"]([^'\"]+)['\"]""")

    fun secretKey(html: String): String = SECRET_KEY.find(html)?.groupValues?.get(1)
        ?: throw IllegalArgumentException("学校体测系统登录页面暂时无法识别")
}

object FitnessLoginCrypto {
    fun encrypt(value: String, base64Key: String): String {
        val key = runCatching { Base64.getDecoder().decode(base64Key) }
            .getOrElse { throw IllegalArgumentException("学校体测系统登录密钥无效") }
        require(key.size in setOf(16, 24, 32)) { "学校体测系统登录密钥无效" }
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return Base64.getEncoder().encodeToString(cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)))
    }

    fun formValue(value: String, base64Key: String): String =
        URLEncoder.encode(encrypt(value, base64Key), StandardCharsets.UTF_8.name())
}

object FitnessResponses {
    private val PASSWORD_INPUT = Regex("""name\s*=\s*['\"]?passwd['\"]?""", RegexOption.IGNORE_CASE)

    fun isSessionExpired(html: String): Boolean =
        html.contains("/servlet/UpdateDate?method=validateCode", ignoreCase = true) ||
            html.contains("secretKey", ignoreCase = true) ||
            PASSWORD_INPUT.containsMatchIn(html)

    fun isSafeRedirect(candidate: HttpUrl, base: HttpUrl): Boolean =
        candidate.scheme == base.scheme && candidate.host == base.host && candidate.port == base.port
}
