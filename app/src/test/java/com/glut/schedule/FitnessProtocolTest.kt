package com.glut.schedule

import com.glut.schedule.service.fitness.FitnessCookies
import com.glut.schedule.service.fitness.FitnessLoginCrypto
import com.glut.schedule.service.fitness.FitnessLoginPage
import com.glut.schedule.service.fitness.FitnessResponses
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FitnessProtocolTest {
    @Test
    fun cookiesMergeByNameAndDropResponseAttributes() {
        val merged = FitnessCookies.merge(
            "JSESSIONID=old; route=a",
            listOf("JSESSIONID=new; Path=/; HttpOnly", "token=t1; Secure")
        )

        assertEquals("JSESSIONID=new; route=a; token=t1", merged)
    }

    @Test
    fun loginPageExtractsSingleOrDoubleQuotedSecretKey() {
        assertEquals("YWJjZA==", FitnessLoginPage.secretKey("var secretKey = 'YWJjZA==';"))
        assertEquals("ZWZnaA==", FitnessLoginPage.secretKey("secretKey=\"ZWZnaA==\""))
        assertThrows(IllegalArgumentException::class.java) {
            FitnessLoginPage.secretKey("<html>学校维护中</html>")
        }
    }

    @Test
    fun officialLoginEncryptionMatchesAesEcbPkcs7() {
        val encrypted = FitnessLoginCrypto.encrypt(
            "2024001",
            "MDEyMzQ1Njc4OWFiY2RlZg=="
        )

        assertEquals("Xi9v7VbllM2+7X0P52dVgA==", encrypted)
        assertEquals(
            URLEncoder.encode(encrypted, StandardCharsets.UTF_8.name()),
            FitnessLoginCrypto.formValue("2024001", "MDEyMzQ1Njc4OWFiY2RlZg==")
        )
    }

    @Test
    fun sessionDetectionRequiresFitnessLoginPageEvidence() {
        assertTrue(FitnessResponses.isSessionExpired("<input name=\"passwd\"><img src=\"/servlet/UpdateDate?method=validateCode\">"))
        assertTrue(FitnessResponses.isSessionExpired("<script>var secretKey='YWJjZA=='</script>"))
        assertFalse(FitnessResponses.isSessionExpired("<table><td>项目名称</td><td>测试成绩</td></table>"))
    }

    @Test
    fun redirectsCannotChangeHostSchemeOrPort() {
        val base = "https://tzcs.glut.edu.cn/".toHttpUrl()

        assertTrue(FitnessResponses.isSafeRedirect("https://tzcs.glut.edu.cn/student/index.jsp".toHttpUrl(), base))
        assertFalse(FitnessResponses.isSafeRedirect("http://tzcs.glut.edu.cn/student/index.jsp".toHttpUrl(), base))
        assertFalse(FitnessResponses.isSafeRedirect("https://tzcs.glut.edu.cn:8443/student/index.jsp".toHttpUrl(), base))
        assertFalse(FitnessResponses.isSafeRedirect("https://example.com/".toHttpUrl(), base))
    }
}
