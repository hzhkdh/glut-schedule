package com.glut.schedule

import com.glut.schedule.service.academic.AcademicExamService
import com.glut.schedule.service.academic.AcademicExamSessionExpiredException
import com.glut.schedule.service.academic.ApiProbeService
import com.glut.schedule.service.parser.GlutExamParser
import com.glut.schedule.service.network.awaitResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

/** 本地合成响应覆盖考试探测的短路、Cookie 轮换和慢响应取消。 */
class ExamRefreshNetworkTest {
    private val exam = """{"data":[{"courseName":"测试课程","examDate":"2026-12-01","examTime":"09:00-11:00","examRoom":"测试教室","seatNumber":"1"}]}"""

    @Test fun cachedExamEndpointStopsFurtherProbes() = runBlocking {
        MockWebServer().use { server ->
            repeat(8) { server.enqueue(MockResponse().setBody(exam)) }
            val service = ApiProbeService(sessionUrlValidator = { true })
            val result = service.probeExamEndpoints("JSESSIONID=initial", server.url("/exam/cached").toString(), server.url("").toString().trimEnd('/'))
            assertEquals(1, result.size)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun fallbackExamProbeUsesRotatedCookie() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().addHeader("Set-Cookie", "JSESSIONID=rotated; Path=/").setBody("unrecognized"))
            repeat(7) { server.enqueue(MockResponse().setBody(exam)) }
            ApiProbeService(sessionUrlValidator = { true }).probeExamEndpoints(
                "JSESSIONID=initial", server.url("/exam/cached").toString(), server.url("").toString().trimEnd('/'))
            server.takeRequest()
            assertEquals("JSESSIONID=rotated", server.takeRequest().getHeader("Cookie"))
        }
    }

    @Test fun recognizableEmptyExamPageIsSuccessful() {
        val body = "<table><tr><th>课程名称</th><th>考试时间</th><th>考试地点</th></tr><tr><td colspan='3'>暂无考试安排</td></tr></table>"
        val selected = AcademicExamService(GlutExamParser()).selectExamDataFromProbeResults(listOf(
            ApiProbeService.ProbeResult("http://jw.glut.edu.cn/academic/exam", "GET", 200, "text/html", body, body.length)))
        assertNotNull(selected)
        assertTrue(selected!!.exams.isEmpty())
    }

    @Test fun malformedExamRowsDoNotClearExistingExams() {
        val body = "<table><tr><th>课程名称</th><th>考试时间</th><th>考试地点</th></tr><tr><td>测试课程</td><td>时间未知</td><td></td></tr></table>"
        assertNull(AcademicExamService(GlutExamParser()).selectExamDataFromProbeResults(listOf(
            ApiProbeService.ProbeResult("http://jw.glut.edu.cn/academic/exam", "GET", 200, "text/html", body, body.length))))
    }

    @Test fun cancellingDuringBodyReadClosesCallPromptly() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("slow body").setBodyDelay(3, TimeUnit.SECONDS))
            val bodyStarted = CountDownLatch(1)
            val bodyFinished = CountDownLatch(1)
            val client = OkHttpClient()
            val call = client.newCall(Request.Builder().url(server.url("/academic/exam")).build())
            val request = async(Dispatchers.Default) {
                call.awaitResponse {
                    // 响应已交付且进入读取回调；body 延迟三秒，取消应中断这次读取。
                    // responseBodyStart 事件在读取到首字节后才触发，不能用来等待慢响应开始读取。
                    bodyStarted.countDown()
                    try { it.body!!.string() } finally { bodyFinished.countDown() }
                }
            }
            try {
                assertTrue(bodyStarted.await(2, TimeUnit.SECONDS))
                request.cancel()
                withTimeout(800) { request.join() }
                assertTrue(request.isCancelled)
                assertTrue(call.isCanceled())
                // 只结束协程仍可能泄漏阻塞网络读取，另证实回调也在延迟体到达前退出。
                assertTrue(bodyFinished.await(800, TimeUnit.MILLISECONDS))
            } finally {
                request.cancel()
                call.cancel()
            }
        }
    }

    @Test fun probeCancellationIsNotSwallowedAsNull() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("slow body").setBodyDelay(3, TimeUnit.SECONDS))
            val request = async(Dispatchers.Default) {
                ApiProbeService(sessionUrlValidator = { true }).probeUrl("", server.url("/academic/exam").toString())
            }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            request.cancel()
            withTimeout(800) { request.join() }
            assertTrue(request.isCancelled)
        }
    }

    @Test fun serverFailureDoesNotPretendSessionExpired() = runBlocking {
        MockWebServer().use { server ->
            repeat(8) { server.enqueue(MockResponse().setResponseCode(503).setBody("service unavailable")) }
            val service = AcademicExamService(GlutExamParser(), ApiProbeService(sessionUrlValidator = { true }))
            val result = service.fetchExamData("JSESSIONID=initial", baseUrl = server.url("").toString().trimEnd('/'))
            assertTrue(result.isFailure)
            assertFalse(result.exceptionOrNull() is AcademicExamSessionExpiredException)
        }
    }

    @Test fun confirmedLoginPageTriggersExpiryAndRetainsRotatedCookie() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().addHeader("Set-Cookie", "JSESSIONID=rotated; Path=/").setBody("<html>统一身份认证</html>"))
            val service = AcademicExamService(GlutExamParser(), ApiProbeService(sessionUrlValidator = { true }))
            val result = service.fetchExamData("JSESSIONID=initial", baseUrl = server.url("").toString().trimEnd('/'))
            assertTrue(result.exceptionOrNull() is AcademicExamSessionExpiredException)
            assertEquals("JSESSIONID=rotated", service.lastUpdatedCookie)
            assertEquals(1, server.requestCount)
        }
    }
}
