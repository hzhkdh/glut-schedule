package com.glut.schedule

import com.glut.schedule.service.graduate.GraduateClient
import com.glut.schedule.service.graduate.GraduateParser
import com.glut.schedule.service.graduate.GraduateProtocol
import com.glut.schedule.service.graduate.GraduateSessionExpiredException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Base64

/**
 * 研究生门户客户端的**真实 HTTP 往返**测试。
 *
 * 存在的理由：真机（vivo / release 0.25.0）点「研究生」立刻报「无法获取研究生教务验证码」。
 * 根因是 `Response` 被带出 `use { }`；`use` 返回前就 `close()`，而 OkHttp 的
 * `Response.close()` 会一并关掉 body —— 之后再读 body 抛 `IllegalStateException`。
 * 于是 `captcha()` 与 `text()`（`login()` 与所有网关请求都走它）全部失败，第一步就死。
 *
 * 之前所有测试都用假 client，**测不出 OkHttp 的关闭语义**；这里让真实 OkHttp 走完整往返，
 * 才能把「body 必须在 `use` 内部读完」这条不变量钉住。没有它，同一个 bug 会以同样方式回来。
 *
 * 全程合成数据：本机 MockWebServer、合成 cookie/学号，不含任何真实账号或门户响应。
 */
class GraduateClientHttpTest {

    private val syntheticCookie = "z-oauth-csrftoken=synthetic-csrf"

    private fun clientFor(server: MockWebServer) =
        GraduateClient(cookie = syntheticCookie, baseUrl = server.url("/").toString())

    private fun gatewayBody(data: String) = """{"success":true,"data":$data}"""

    // ---- 回归：body 必须在 use 内部读完 ----

    /**
     * `captcha()` 走 `readBytesLimited`（流式读 body）。
     * 旧实现把 `Response` 带出 `use`，这里会抛 `IllegalStateException: closed`。
     */
    @Test
    fun captchaReadsTheImageBodyBeforeTheResponseIsClosed() {
        MockWebServer().use { server ->
            val imageBytes = ByteArray(64) { index -> (index * 7 % 251).toByte() }
            server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(imageBytes)))

            val bytes = clientFor(server).captcha()

            assertArrayEquals(imageBytes, bytes)
            // 门户实际的验证码路径与参数名（`time`，不是 `t`）。
            val recorded = server.takeRequest()
            assertEquals("/VerifyCode.ashx", recorded.requestUrl!!.encodedPath)
            assertTrue(
                "验证码请求必须带 time 参数：${recorded.path}",
                recorded.requestUrl!!.queryParameter("time")!!.toLong() > 0
            )
        }
    }

    /**
     * `text()` 是 `login()` 与所有网关请求的公共读法。
     * 旧实现同样在这里抛 `closed`，表现就是「登录第一步就失败」。
     */
    @Test
    fun textReadsBodiesAcrossMultipleRequestsInOneLogin() {
        MockWebServer().use { server ->
            val publicKey = KeyPairGenerator.getInstance("RSA")
                .apply { initialize(1024) }
                .generateKeyPair().public
            val keyBase64 = Base64.getEncoder().encodeToString(publicKey.encoded)
            server.enqueue(MockResponse().setResponseCode(200).setBody("""var Pub_Key = '$keyBase64';"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ret":0}"""))

            // 不抛异常即说明两个响应体都被读到了：公钥解析成功、ret 解析为 0。
            clientFor(server).login("synthetic", "合成密码", "1234")

            assertEquals(2, server.requestCount)
            server.takeRequest()
            val loginRequest = server.takeRequest()
            assertEquals("POST", loginRequest.method)
            assertTrue(loginRequest.body.readUtf8().contains("userid=synthetic"))
        }
    }

    /** 网关响应体也必须能读到：旧实现在这里抛 `closed`，所有取数都会失败。 */
    @Test
    fun gatewayReadsTheResponseBodyAndUnwrapsData() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(gatewayBody("""{"items":[]}""")))

            val raw = clientFor(server).gateway(
                template = "ScoreService/QueryStuScore/{xh}/",
                payload = mapOf("xh" to "synthetic"),
                menu = "6226"
            )

            assertEquals("""{"items":[]}""", raw)
        }
    }

    /**
     * **裸 JSON 数组响应**：课表接口实测就是这种（响应体以 `[{` 开头）。
     *
     * 旧实现无条件 `JSONObject(raw)`，`JSONObject("[…]")` 抛 JSONException，
     * 于是课表导入必然报「研究生教务返回了无法识别的数据」——真机上登录成功、验证码也过了，
     * 却卡在导入这一步。数组没有 ret/success 包装，整段就是数据本身，必须原样返回。
     */
    @Test
    fun gatewayReturnsBareJsonArrayAsIs() {
        MockWebServer().use { server ->
            val scheduleRows = """[{"JieCi":"1,2","Monday":"合成课程\r\n测试教师\r\n节次:1,2节 \r\n周次:2-3\r\n地点:测试教室"}]"""
            server.enqueue(MockResponse().setResponseCode(200).setBody(scheduleRows))

            val raw = clientFor(server).gateway(
                template = "TimeTableNewService/GetTimeTableByStudent/{Year}/{Term}/{XH}?IsConti={IsConti}",
                payload = mapOf(
                    "Year" to "2026-2027", "Term" to "1", "XH" to "synthetic", "IsConti" to "0"
                ),
                menu = "6224"
            )

            // 原样返回，不能被 data 解包、也不能被当成错误。
            assertEquals(scheduleRows, raw)
            // 交给解析器就能拿到课程——这条链路是真机失败的那条。
            assertEquals(listOf("合成课程"), GraduateParser.schedule(raw).map { it.title })
        }
    }

    /** 带 `data` 包装的对象响应仍要走解包分支（与裸数组分支并列）。 */
    @Test
    fun gatewayUnwrapsObjectResponsesThatCarryData() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"success":true,"ret":0,"data":{"SyncTime":"2026-09-07"}}""")
            )

            val raw = clientFor(server).gateway("RedisService/IsRedis")

            assertEquals("""{"SyncTime":"2026-09-07"}""", raw)
        }
    }

    /** `success:false` / `ret != 0` 仍必须按失败处理，不能被数组分支绕过。 */
    @Test
    fun gatewayStillRejectsFailedObjectResponses() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"success":false,"ret":1,"message":"synthetic"}""")
            )

            val error = runCatching { clientFor(server).gateway("RedisService/IsRedis") }.exceptionOrNull()

            // 失败响应必须报错（不能因为新增了数组分支就顺带放过 ret != 0 的对象响应），
            // 且文案要指向「查询失败」而不是「无法识别的数据」。
            assertTrue("失败响应必须报错，实际=$error", error is IllegalArgumentException)
            assertTrue(
                "错误文案应指向查询失败，实际=${error?.message}",
                error?.message.orEmpty().contains("查询失败")
            )
        }
    }

    // ---- 网关请求的形状（真实往返）----

    @Test
    fun gatewaySendsSignedUrlWithBusinessParametersAfterTheSignature() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(gatewayBody("[]")))

            clientFor(server).gateway(
                template = "ScoreService/QueryStuScore/{xh}/",
                payload = mapOf("xh" to "synthetic"),
                menu = "6226"
            )

            val request = server.takeRequest()
            val url = request.requestUrl!!
            // 四个 Y-* 都在；Y-UriTemplate 经 escape() 上链、解析回来仍是原始模板。
            assertEquals("ScoreService/QueryStuScore/{xh}/", url.queryParameter("Y-UriTemplate"))
            assertEquals("GET", url.queryParameter("Y-Method"))
            assertEquals("6226", url.queryParameter("Y-menuid"))
            assertEquals(expectedCft(), url.queryParameter("Y-cft"))
            assertTrue(
                "Y-sign 必须是 32 位十六进制：${url.queryParameter("Y-sign")}",
                url.queryParameter("Y-sign")!!.matches(Regex("[0-9a-f]{32}"))
            )
            // 真实抓包里 Y-sign 排在业务参数之前。
            val path = request.path.orEmpty()
            assertTrue(path, path.indexOf("Y-sign") < path.indexOf("xh="))
            // GET：业务参数在查询串上，且经过 encode_ 编码。
            assertEquals("encode_c3ludGhldGlj", url.queryParameter("xh"))
        }
    }

    /** POST 时业务参数进表单体，URL 上不带它们。 */
    @Test
    fun postGatewayPutsBusinessParametersInTheBody() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody(gatewayBody("""{"UserID":"synthetic"}"""))
            )

            clientFor(server).gateway(
                template = "UserService/getLoggedInUser/",
                menu = "",
                method = "POST"
            )

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            val url = request.requestUrl!!
            assertEquals("UserService/getLoggedInUser/", url.queryParameter("Y-UriTemplate"))
            assertEquals("POST", url.queryParameter("Y-Method"))
            assertFalse(request.path.orEmpty().contains("UserID"))
        }
    }

    // ---- bootstrap 端到端（含白名单投影）----

    /**
     * `bootstrap()` 一次真实往返，同时验证校历字段被读出来，以及**白名单投影**：
     * 门户返回的 `PrivateKey` 等无关敏感字段不得进入上下文。
     */
    @Test
    fun bootstrapReadsPortalCalendarAndProjectsOnlyWhitelistedFields() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    gatewayBody(
                        """{"CurrentYear":"2026-2027","CurrentTerm":"1",""" +
                            """"CurrentTermFrom":"/Date(1788710400000+0800)/",""" +
                            """"CurrentTermEnd":"/Date(1799942400000+0800)/",""" +
                            """"TeachWeekEnd":19,"PrivateKey":"SYNTHETIC-SECRET","Unrelated":"x"}"""
                    )
                )
            )
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    gatewayBody(
                        """{"UserID":"synthetic","UserName":"合成用户",""" +
                            """"Data":{"Grade":"2025"},"PrivateKey":"SYNTHETIC-SECRET"}"""
                    )
                )
            )

            val context = clientFor(server).bootstrap()

            assertEquals("2026-2027", context.year)
            assertEquals("1", context.term)
            assertEquals(LocalDate.of(2026, 9, 7), context.currentSemesterStartMonday)
            assertEquals(DayOfWeek.MONDAY, context.currentSemesterStartMonday!!.dayOfWeek)
            // 结束日不做周一对齐：门户给的就是周五那一天。
            assertEquals(LocalDate.of(2027, 1, 15), context.currentSemesterEndDate)
            assertEquals(DayOfWeek.FRIDAY, context.currentSemesterEndDate!!.dayOfWeek)
            assertEquals(19, context.teachWeekEndValue)
            assertEquals("synthetic", context.studentId)
            assertEquals("2025", context.grade)
            assertEquals("合成用户", context.name)
            // 白名单投影：无关敏感字段一个都不许带出来。
            assertFalse(context.toString().contains("SYNTHETIC-SECRET"))
            assertFalse(context.toString().contains("PrivateKey"))
        }
    }

    /** 没有 `z-oauth-csrftoken` 时网关必须直接判定会话失效，而不是发一个没有签名的请求。 */
    @Test
    fun gatewayWithoutCsrfCookieFailsBeforeSendingAnything() {
        MockWebServer().use { server ->
            val client = GraduateClient(cookie = "", baseUrl = server.url("/").toString())

            val error = runCatching { client.gateway("RedisService/IsRedis") }.exceptionOrNull()

            assertTrue(
                "应抛会话失效异常，实际=$error",
                error is GraduateSessionExpiredException
            )
            assertEquals(0, server.requestCount)
        }
    }

    /** HTTP 401/403/3xx 一律归类为会话失效，供上层引导重新登录。 */
    @Test
    fun unauthorizedResponseIsReportedAsSessionExpiry() {
        listOf(401, 403, 302).forEach { code ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(code))
                val error = runCatching { clientFor(server).captcha() }.exceptionOrNull()
                assertTrue("HTTP $code 应判为会话失效，实际=$error", error is GraduateSessionExpiredException)
            }
        }
    }

    /** 与客户端内部同口径地算出 `Y-cft`（`md5(decodeURI(csrf))`）。 */
    /**
     * 门户的 `Pub_Key` 有两种形态，两种都必须能加密。
     *
     * 裸 base64 与 PEM 都要覆盖：旧实现直接把整段（可能带 PEM armor 的）文本交给
     * `Base64.decode`，在 `-----` 上抛 `Illegal base64 character 2d`，登录第一步就死。
     */
    @Test
    fun loginEncryptsPasswordForBothBareBase64AndPemPublicKeys() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val bareBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val pem = "-----BEGIN PUBLIC KEY-----\n$bareBase64\n-----END PUBLIC KEY-----"

        listOf(bareBase64, pem).forEach { servedKey ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(200).setBody("""var Pub_Key = '$servedKey';"""))
                server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ret":0}"""))

                clientFor(server).login("synthetic", "合成密码", "1234")

                server.takeRequest()
                val body = server.takeRequest().body.readUtf8()
                // 口令必须已被 RSA 加密（不再是明文），且能被对应私钥解回原文。
                assertFalse("请求体里不得出现明文口令", body.contains("合成密码"))
                val encryptedBase64 = URLDecoder.decode(
                    body.substringAfter("userpwd=").substringBefore('&'),
                    "UTF-8"
                )
                val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding")
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keyPair.private)
                val decrypted = String(
                    cipher.doFinal(Base64.getDecoder().decode(encryptedBase64)),
                    Charsets.UTF_8
                )
                assertEquals("合成密码", decrypted)
            }
        }
    }

    private fun expectedCft(): String {
        val csrf = syntheticCookie.substringAfter("z-oauth-csrftoken=")
        return GraduateProtocol.md5(URLDecoder.decode(csrf.replace("+", "%2B"), "UTF-8"))
    }
}
