package com.glut.schedule

import com.glut.schedule.service.academic.ApiProbeService
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ApiProbeSessionIsolationTest {
    @Test
    fun requestWithoutCookieDoesNotReusePreviousProbeSession() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("first"))
            server.enqueue(MockResponse().setBody("second"))
            val service = ApiProbeService(sessionUrlValidator = { true })
            val url = server.url("/academic/test").toString()

            service.probeUrl("JSESSIONID=first-account", url)
            service.probeUrl("", url)

            assertEquals("JSESSIONID=first-account", server.takeRequest().getHeader("Cookie"))
            assertNull(server.takeRequest().getHeader("Cookie"))
        }
    }

    @Test
    fun probeBatchCarriesEachRotatedCookieIntoTheNextRequest() = runTest {
        MockWebServer().use { server ->
            val responseIndex = AtomicInteger(0)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val step = responseIndex.incrementAndGet()
                    return MockResponse()
                        .addHeader("Set-Cookie", "JSESSIONID=step-$step; Path=/academic")
                        .setBody("<html><body>ok</body></html>")
                }
            }
            val service = ApiProbeService(sessionUrlValidator = { true })

            val results = service.probeAllEndpoints(
                cookie = "JSESSIONID=initial",
                baseUrl = server.url("").toString().trimEnd('/')
            )

            val requests = List(results.size) { server.takeRequest() }
            assertTrue(requests.size > 1)
            assertEquals("JSESSIONID=initial", requests.first().getHeader("Cookie"))
            requests.drop(1).forEachIndexed { index, request ->
                assertEquals("JSESSIONID=step-${index + 1}", request.getHeader("Cookie"))
            }
            assertEquals("JSESSIONID=step-${results.size}", results.last().updatedCookie)
        }
    }

    @Test
    fun formProbeReportsWhenRedirectChangesPostToGet() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(301)
                    .addHeader("Location", server.url("/academic/redirected"))
            )
            server.enqueue(MockResponse().setBody("redirected"))
            val service = ApiProbeService(sessionUrlValidator = { true })

            val result = service.probeForm(
                cookie = "JSESSIONID=session",
                url = server.url("/academic/form").toString(),
                body = "whichWeek=1",
                referer = server.url("/academic/landing").toString()
            )!!

            assertEquals("POST", result.method)
            assertEquals("GET", result.finalMethod)
            assertTrue(result.redirected)
        }
    }
}
