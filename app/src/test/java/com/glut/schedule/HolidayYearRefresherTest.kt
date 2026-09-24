package com.glut.schedule

import com.glut.schedule.service.holiday.TimorHolidayClient
import com.glut.schedule.service.holiday.refreshMissingHolidayYears
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 端到端跑通「补齐缺失年份」这条链路：真实 HTTP 响应 → 可用性判定 → 写缓存。
 *
 * 用 MockWebServer 而不是只测纯函数，是因为本次的真实故障形态
 * （timor 对未公布年份返回 HTTP 200 + `{"code":0,"holiday":{}}`）只有在
 * 「响应解析 + 判定 + 写缓存」串起来时才会暴露。
 */
class HolidayYearRefresherTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // 指向本地服务器：单元测试绝不访问线上 timor 接口。
        baseUrl = server.url("/api/holiday/year/").toString()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private lateinit var baseUrl: String

    private val valid2026 =
        """{"code":0,"holiday":{"10-01":{"holiday":true,"name":"国庆节"}}}"""

    /** 未公布年份的真实返回：格式合法、code 为 0、假期表为空。 */
    private val notPublished = """{"code":0,"holiday":{}}"""

    @Test
    fun unpublishedYearIsNotCachedSoTheNextRefreshRetriesIt() = runTest {
        server.enqueue(MockResponse().setBody(notPublished))
        val saved = mutableMapOf<Int, String>()

        val loaded = refresh(cached = emptyMap(), save = { year, json -> saved[year] = json })

        assertEquals(1, server.requestCount)
        assertTrue("未公布的年份不应被写进缓存", saved.isEmpty())
        assertTrue("没有真正拿到数据就不算成功", loaded.isEmpty())
    }

    @Test
    fun usableYearIsCachedAndNotRequestedAgain() = runTest {
        server.enqueue(MockResponse().setBody(valid2026))
        val saved = mutableMapOf<Int, String>()

        val loaded = refresh(cached = emptyMap(), save = { year, json -> saved[year] = json })

        assertEquals(listOf(2026), loaded)
        assertEquals(valid2026, saved[2026])

        // 第二次刷新：已有可用数据，不该再打接口。
        val again = refresh(cached = saved, save = { year, json -> saved[year] = json })

        assertEquals("已有可用数据时不得重复请求", 1, server.requestCount)
        assertTrue(again.isEmpty())
    }

    @Test
    fun blankCacheEntryCountsAsMissingAndIsRetried() = runTest {
        // 读闸门已经把它剔除了，所以调用方拿到的是「缺失」；即便有人绕过闸门塞了空白进来，
        // 判定也不能把空白当成已缓存。
        server.enqueue(MockResponse().setBody(valid2026))

        val loaded = refresh(cached = mapOf(2026 to "   "), save = { _, _ -> })

        assertEquals(1, server.requestCount)
        assertEquals(listOf(2026), loaded)
    }

    @Test
    fun httpFailureLeavesCacheUntouched() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val saved = mutableMapOf<Int, String>()

        val loaded = refresh(cached = emptyMap(), save = { year, json -> saved[year] = json })

        assertTrue(saved.isEmpty())
        assertTrue(loaded.isEmpty())
    }

    @Test
    fun malformedResponseIsNeverCached() = runTest {
        server.enqueue(MockResponse().setBody("<html>502 Bad Gateway</html>"))
        val saved = mutableMapOf<Int, String>()

        refresh(cached = emptyMap(), save = { year, json -> saved[year] = json })

        assertTrue(saved.isEmpty())
    }

    @Test
    fun saveFailureNeverPropagatesToTheCaller() = runTest {
        // 契约：这是旁路刷新，挂在导入 / 刷新课表这些主流程上——本地存储出问题也不能
        // 把主流程拖成「导入失败」。
        server.enqueue(MockResponse().setBody(valid2026))

        val loaded = refresh(
            cached = emptyMap(),
            save = { _, _ -> throw IllegalStateException("存储空间不足") }
        )

        assertTrue(loaded.isEmpty())
    }

    private suspend fun refresh(
        cached: Map<Int, String>,
        save: suspend (Int, String) -> Unit
    ): List<Int> = refreshMissingHolidayYears(
        client = TimorHolidayClient(baseUrl = baseUrl),
        years = 2026..2026,
        cachedYears = cached,
        saveYear = save
    )
}
