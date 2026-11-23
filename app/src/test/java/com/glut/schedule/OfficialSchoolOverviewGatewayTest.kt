package com.glut.schedule

import com.glut.schedule.service.campus.OfficialSchoolOverviewGateway
import com.glut.schedule.service.campus.SchoolOverviewTopic
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class OfficialSchoolOverviewGatewayTest {
    @Test
    fun `领导资料保留官网顺序分工和兼容旧缓存的标识`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(html(LEADERS))

            val payload = gateway(server).fetchJson(SchoolOverviewTopic.LEADERS)
            val source = payload.getJSONArray("sources").getJSONObject(0)
            val people = payload.getJSONObject("data").getJSONArray("people")
            val first = people.getJSONObject(0)
            assertEquals("/xxgk/xrld.htm", server.takeRequest().path)
            assertEquals("leaders", payload.getString("topic"))
            assertEquals(1234L, payload.getLong("fetchedAt"))
            assertEquals("现任领导", source.getString("label"))
            assertEquals("$OFFICIAL/xxgk/xrld.htm", source.getString("url"))
            assertEquals(2, people.length())
            assertEquals("示例乙", first.getString("name"))
            assertEquals("f797d54cff209a59cae145934e46e303", first.getString("id"))
            assertEquals("校长", first.getString("role"))
            assertEquals("主管教学与科研。\n\n联系示例学院。", first.getString("duties"))
            assertEquals("$OFFICIAL/__local/A/AA/AA/leader.jpg", first.getString("photoUrl"))
            assertEquals("示例甲", people.getJSONObject(1).getString("name"))
        }
    }

    @Test
    fun `校史保留同年事件和段内日期并使用沿革原图`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(html(article(HISTORY)))

            val data = gateway(server).fetchJson(SchoolOverviewTopic.HISTORY).getJSONObject("data")
            val periods = data.getJSONArray("periods")
            val first = periods.getJSONObject(0)
            val events = first.getJSONArray("events")
            assertEquals("/xxgk/lsyg.htm", server.takeRequest().path)
            assertEquals(4, periods.length())
            assertEquals("1956", first.getString("id"))
            assertEquals("学校创建与建设初期", first.getString("title"))
            assertEquals("1956—1978年", first.getString("range"))
            assertEquals("1970年6月", events.getJSONObject(0).getString("date"))
            assertEquals("开始筹备第一项工程。", events.getJSONObject(0).getString("text"))
            assertEquals("1970年10月", events.getJSONObject(1).getString("date"))
            assertEquals("成立实验组。1970年12月完成准备。", events.getJSONObject(1).getString("text"))
            assertEquals("", events.getJSONObject(2).getString("date"))
            assertEquals("$OFFICIAL/__local/A/AA/AA/full-history.jpg", data.getString("diagramUrl"))
        }
    }

    @Test
    fun `沿革图地址不受信时仍返回完整校史`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(html(article(HISTORY.replace("/__local/A/AA/AA/full-history.jpg", "https://other.invalid/__local/A/AA/AA/full-history.jpg"))))

            val data = gateway(server).fetchJson(SchoolOverviewTopic.HISTORY).getJSONObject("data")
            assertEquals(4, data.getJSONArray("periods").length())
            assertFalse(data.has("diagramUrl"))
        }
    }

    @Test
    fun `桂工精神与学校标识合并为完整的精神和校训`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/xxgk/ggjs.htm" -> html(article(SPIRIT))
                    "/xxgk/xxbs.htm" -> html(article(MARKS))
                    else -> MockResponse().setResponseCode(404)
                }
            }

            val payload = gateway(server).fetchJson(SchoolOverviewTopic.SPIRIT)
            val data = payload.getJSONObject("data")
            assertEquals(2, payload.getJSONArray("sources").length())
            assertEquals("勤学实践", data.getJSONArray("phrases").getString(0))
            assertEquals("奋发向上", data.getJSONArray("phrases").getString(3))
            assertEquals("来自长期实践。\n\n由全校师生共同传承。", data.getString("description"))
            assertEquals("勤学 求实", data.getString("motto"))
            assertEquals("一、涵义阐释\n\n努力学习，踏实实践。\n\n二、出处、典故\n\n出处说明。", data.getString("mottoDescription"))
            assertEquals("$OFFICIAL/_mediafile/glutnew/2015/09/09/spirit.png", data.getString("photoUrl"))
        }
    }

    @Test
    fun `校歌保留歌词换行和注释并适配宣传片改名或缺失`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(html(article(MARKS + PROMO)))
            server.enqueue(html(article(MARKS)))
            val gateway = gateway(server)

            val first = gateway.fetchJson(SchoolOverviewTopic.SONG).getJSONObject("data")
            val second = gateway.fetchJson(SchoolOverviewTopic.SONG).getJSONObject("data")
            assertEquals("/xxgk/xxbs.htm", server.takeRequest().path)
            assertEquals("桂林理工大学之歌", first.getString("title"))
            assertEquals("[\"歌曲第一行，\",\"歌曲第二行。\"]", first.getJSONArray("lyrics").toString())
            assertEquals("词语甲", first.getJSONArray("notes").getJSONObject(0).getString("term"))
            assertEquals("示例注释：保留冒号。", first.getJSONArray("notes").getJSONObject(0).getString("text"))
            assertEquals("[\"示例大意第一段。\",\"示例大意第二段。\"]", first.getJSONArray("meaning").toString())
            assertEquals(2, first.getJSONArray("mottoSections").length())
            assertFalse(first.has("videoUrl"))
            assertFalse(first.has("originalVideoUrl"))
            assertEquals(first.getJSONArray("meaning").toString(), second.getJSONArray("meaning").toString())
        }
    }

    @Test
    fun `官网跳转保持在单次请求内且手动刷新绕过缓存`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/redirect")))
            server.enqueue(html(LEADERS))

            assertThrows(IOException::class.java) {
                runBlocking { gateway(server).fetch(SchoolOverviewTopic.LEADERS, force = true) }
            }
            val request = server.takeRequest()
            assertEquals("/xxgk/xrld.htm", request.path)
            assertEquals("no-cache", request.getHeader("Cache-Control"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `超出上限的分块页面和非官网领导照片被拒绝`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setChunkedBody(LEADERS + " ".repeat(512 * 1024), 4096))
            server.enqueue(html(LEADERS.replace("/__local/A/AA/AA/leader.jpg", "https://other.invalid/__local/A/AA/AA/leader.jpg")))
            val gateway = gateway(server)

            repeat(2) {
                assertThrows(IOException::class.java) { runBlocking { gateway.fetchJson(SchoolOverviewTopic.LEADERS) } }
            }
            assertEquals(2, server.requestCount)
        }
    }

    // 只在最后一步将请求转发到本地服务，保留正式请求所选的协议、主机和页面路径校验。
    private fun gateway(server: MockWebServer) = OfficialSchoolOverviewGateway(
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.scheme == "https" && request.url.host == "www.glut.edu.cn")
            chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath)).build())
        }.build(), now = { 1234L }
    )

    private suspend fun OfficialSchoolOverviewGateway.fetchJson(topic: SchoolOverviewTopic) =
        JSONObject(fetch(topic, force = false))

    private fun html(content: String) = MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(content)

    private fun article(content: String) = """<img src="/images/logo.png"><div id="vsb_content">$content</div>"""

    private companion object {
        const val OFFICIAL = "https://www.glut.edu.cn"
        val LEADERS = """
            <ul class="list_box_leader">
              <li><div><img src="/__local/A/AA/AA/leader.jpg"></div>
                <h5><font>示例乙</font><span>校长</span></h5><p>主管教学与科研。</p><p>联系示例学院。</p></li>
              <li><div><img src="../_mediafile/glutnew/2015/09/09/leader.jpg"></div>
                <h5><font>&#8203;示例甲</font><span>助理</span></h5><p>负责校际交流。</p></li>
            </ul>
        """.trimIndent()
        val HISTORY = """
            <p>桂林理工大学历史沿革</p><p>学校创建与建设初期（1956—1978年）</p>
            <p>1970年6月，开始筹备<strong>第一项工程</strong>。</p>
            <p>1970年10月，成立实验组。1970年12月完成准备。</p><p>随后继续筹备。</p>
            <p>示例二（1978—1993年）</p><p>1980年，举办活动。</p>
            <p>示例三（1993—2009年）</p><p>1995年，成立组织。</p>
            <p>示例四（2009年以来）</p><p>2010年，开展合作。</p>
            <p><img src="/__local/A/AA/AA/thumb.jpg" orisrc="/__local/A/AA/AA/full-history.jpg"></p>
        """.trimIndent()
        val SPIRIT = """
            <p><img src="/_mediafile/glutnew/2015/09/09/spirit.png"></p>
            <p>勤学实践　团结互助　实事求是　奋发向上</p>
            <p>来自长期实践。</p><p>由全校师生共同传承。</p>
        """.trimIndent()
        val MARKS = """
            <p>校徽、校名及标识</p><p>校训：勤学　求实</p><p>校训“勤学 求实”释义</p>
            <p>一、涵义阐释</p><p>努力学习，踏实实践。</p><p>二、出处、典故</p><p>出处说明。</p>
            <p>桂林理工大学之歌</p><p>歌曲第一行，<br>歌曲第二行。</p>
            <p><script>showVsbVideo('sample.mp4');</script></p><p><a href="../sample.mp4">校歌视频</a>下载</p>
            <p>【<strong>注释</strong>】</p><p><b>词语甲</b>：示例注释：保留冒号。</p><p>词语乙：另一条注释。</p>
            <p>【歌词大意】</p><p>示例大意第一段。</p><p>示例大意第二段。</p>
        """.trimIndent()
        const val PROMO = "<p><strong>桂林理工大学建校周年宣传片《示例故事》</strong></p><p>宣传内容不属于校歌。</p>"
    }
}
