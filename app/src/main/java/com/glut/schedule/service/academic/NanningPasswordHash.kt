package com.glut.schedule.service.academic

import java.security.MessageDigest

/**
 * Implements Nanning campus (U-MOOC) login password hashing.
 *
 * Matches the JavaScript function from md5.js:
 * ```
 * function submit_hex_md5(s, salt) {
 *     var encryptPwd1 = rstr2hex(rstr_md5(str2rstr_utf8(s)));
 *     var encryptPwd2 = rstr2hex(rstr_md5(str2rstr_utf8(encryptPwd1)));
 *     return encryptPwd2;
 * }
 * ```
 * Which is: MD5(MD5(UTF8(password))) — lowercase hex.
 */
object NanningPasswordHash {

    fun hash(password: String): String {
        val first = md5Hex(password.toByteArray(Charsets.UTF_8))
        return md5Hex(first.toByteArray(Charsets.UTF_8))
    }

    private fun md5Hex(input: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(input)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
