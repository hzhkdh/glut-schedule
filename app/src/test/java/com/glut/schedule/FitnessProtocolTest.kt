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
        assertTrue(FitnessResponses.isSessionExpired("<b>页面已过期或者遇到错误!</b>"))
        assertTrue(FitnessResponses.isSessionExpired("<b>请重新登录!</b>"))
        assertFalse(FitnessResponses.isSessionExpired("<table><td>项目名称</td><td>测试成绩</td></table>"))
    }

    @Test
    fun redirectsOnlyUpgradeTheFitnessSitesSameHostHttpErrorPage() {
        val base = "https://tzcs.glut.edu.cn/".toHttpUrl()

        assertEquals(
            "https://tzcs.glut.edu.cn/student/index.jsp".toHttpUrl(),
            FitnessResponses.safeRedirect("https://tzcs.glut.edu.cn/student/index.jsp".toHttpUrl(), base)
        )
        assertEquals(
            "https://tzcs.glut.edu.cn/SportWeb/common/error.jsp?from=score".toHttpUrl(),
            FitnessResponses.safeRedirect(
                "http://tzcs.glut.edu.cn:80/SportWeb/common/error.jsp?from=score".toHttpUrl(),
                base
            )
        )
        assertEquals(null, FitnessResponses.safeRedirect("http://tzcs.glut.edu.cn:8080/error.jsp".toHttpUrl(), base))
        assertEquals(null, FitnessResponses.safeRedirect("https://tzcs.glut.edu.cn:8443/student/index.jsp".toHttpUrl(), base))
        assertEquals(null, FitnessResponses.safeRedirect("https://example.com/".toHttpUrl(), base))
    }
}
