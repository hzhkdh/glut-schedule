package com.glut.schedule

import com.glut.schedule.service.holiday.MakeupTableClient
import com.glut.schedule.service.holiday.isUsableMakeupTablePayload
import com.glut.schedule.service.holiday.parseMakeupTableJson
import com.glut.schedule.service.holiday.refreshMakeupTable
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 调休补课对照表：解析闸门 + 「取数 → 判定 → 落盘」整条链路。
 *
 * 用 MockWebServer 而不是只测纯函数，是因为真实故障形态只有在串起来时才暴露：
 * 静态托管在路径不存在时返回的是 **HTTP 200 + 一个 HTML 兜底页**，如果照抄通知缓存
 * 「只要 isSuccessful 就存原文」的写法，那个 HTML 会被解析成「维护者清空了表」
 * 并永久落盘，把远端规则全部抹掉且无法自动恢复。
 */
class MakeupTableRefresherTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val validTable = """
        {
          "schema": 1,
          "revision": 7,
          "updatedAt": "2026-10-08",
          "semesters": {
            "guilin:2026:autumn": {
              "label": "2026·秋",
              "makeups": [
                { "source": "2026-10-06", "target": "2026-09-20", "reason": "国庆节前补班" },
                { "source": "2026-10-07", "target": "2026-10-10", "reason": "国庆节后补班" }
              ]
            }
          }
        }
    """.trimIndent()

    private fun client() = MakeupTableClient(url = server.url("/holiday-makeups.json").toString())

    @Test
    fun validTableIsSavedVerbatim() = runTest {
        server.enqueue(MockResponse().setBody(validTable))
        var saved: String? = null

        val updated = refreshMakeupTable(client(), saveJson = { saved = it })

        assertTrue(updated)
        assertEquals(validTable, saved)
    }

    @Test
    fun htmlFallbackPageIsNotCached() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<!doctype html><html><body>Not Found</body></html>")
        )
        var saved: String? = null

        val updated = refreshMakeupTable(client(), saveJson = { saved = it })

        assertFalse("HTML 兜底页绝不能被当成「表被清空了」落盘", updated)
        assertNull(saved)
    }

    @Test
    fun unknownSchemaIsNotCached() = runTest {
        server.enqueue(MockResponse().setBody("""{"schema":99,"semesters":{}}"""))
        var saved: String? = null

        val updated = refreshMakeupTable(client(), saveJson = { saved = it })

        assertFalse(updated)
        assertNull(saved)
    }

    @Test
    fun httpFailureKeepsTheOldCacheAndDoesNotThrow() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        var saved: String? = null

        val updated = refreshMakeupTable(client(), saveJson = { saved = it })

        assertFalse(updated)
        assertNull("失败时保留上一次同步成功的表", saved)
    }

    @Test
    fun unreachableHostDoesNotThrow() = runTest {
        val unreachable = MakeupTableClient(url = "http://127.0.0.1:1/holiday-makeups.json")

        // 契约：永不抛出——它挂在导入 / 刷新课表这些主流程上。
        val updated = refreshMakeupTable(unreachable, saveJson = { })

        assertFalse(updated)
    }

    // ---- 解析闸门 ----

    @Test
    fun parserRejectsNonTablePayloads() {
        assertNull(parseMakeupTableJson(""))
        assertNull(parseMakeupTableJson("<!doctype html><html></html>"))
        assertNull(parseMakeupTableJson("""{"schema":1}"""))
        assertNull(parseMakeupTableJson("""{"schema":2,"semesters":{}}"""))
        assertFalse(isUsableMakeupTablePayload("<html>"))
    }

    @Test
    fun emptySemestersIsAcceptedBecauseItIsAnExplicitMaintainerEdit() {
        // 与 isUsableHolidayYearPayload 的口径故意不同：那里的空载荷代表「年份未公布」，
        // 这里的空表是维护者确认过「本学期没有调休」，必须传播。
        val table = parseMakeupTableJson("""{"schema":1,"revision":3,"semesters":{}}""")

        assertNotNull(table)
        assertTrue(table!!.entriesFor("guilin:2026:autumn").isEmpty())
    }

    @Test
    fun revisionIsOptionalSoAMissingFieldDoesNotVoidTheWholeTable() {
        val table = parseMakeupTableJson("""{"schema":1,"semesters":{}}""")

        assertNotNull(table)
        assertEquals(0, table!!.revision)
    }

    @Test
    fun badEntriesAreDroppedOneByOneInsteadOfVoidingTheTable() {
        val table = parseMakeupTableJson(
            """
            {
              "schema": 1,
              "revision": 1,
              "semesters": {
                "guilin:2026:autumn": {
                  "makeups": [
                    { "source": "2026-10-06", "target": "2026-09-20" },
                    { "source": "not-a-date", "target": "2026-09-20" },
                    { "source": "2026-10-06", "target": "2026-10-06" },
                    { "target": "2026-10-10" }
                  ]
                }
              }
            }
            """.trimIndent()
        )

        val entries = table!!.entriesFor("guilin:2026:autumn")
        assertEquals(1, entries.size)
        assertEquals(LocalDate.of(2026, 10, 6), entries.single().source)
        assertEquals(LocalDate.of(2026, 9, 20), entries.single().target)
    }

    @Test
    fun entriesAreKeyedBySemesterSoOneCampusCannotEatAnothersMapping() {
        val table = parseMakeupTableJson(
            """
            {
              "schema": 1,
              "semesters": {
                "guilin:2026:autumn": { "makeups": [{ "source": "2026-10-07", "target": "2026-10-10" }] },
                "nanning:2026:autumn": { "makeups": [] }
              }
            }
            """.trimIndent()
        )

        assertEquals(1, table!!.entriesFor("guilin:2026:autumn").size)
        assertTrue("南宁没录就是没录，不能回落到桂林的映射", table.entriesFor("nanning:2026:autumn").isEmpty())
        assertTrue(table.entriesFor("graduate:2026:autumn").isEmpty())
    }

    /**
     * `entriesFor` 为空有两种含义，必须靠 [MakeupTable.covers] 分开：
     * 「表里没这个学期」要由推导兜底；「维护者写了空数组」表示本学期确实没有调休，
     * 推导得整学期退场——否则推导会补出学校通知里根本没有的日子。
     */
    @Test
    fun coversDistinguishesAnEmptyArrayFromAMissingSemesterKey() {
        val table = parseMakeupTableJson(
            """
            {
              "schema": 1,
              "semesters": {
                "guilin:2026:autumn": { "makeups": [{ "source": "2026-10-07", "target": "2026-10-10" }] },
                "nanning:2026:autumn": { "makeups": [] }
              }
            }
            """.trimIndent()
        )!!

        assertTrue(table.covers("guilin:2026:autumn"))
        assertTrue("空数组也是「收录」——维护者显式确认本学期没有调休", table.covers("nanning:2026:autumn"))
        assertFalse("键不存在才是没覆盖，那时才轮到推导", table.covers("graduate:2026:autumn"))
    }
}
