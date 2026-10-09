package com.glut.schedule.service.graduate

import com.glut.schedule.service.academic.CapturingCookieJar
import com.glut.schedule.service.network.MAX_HTML_RESPONSE_BYTES
import com.glut.schedule.service.network.MAX_IMAGE_RESPONSE_BYTES
import com.glut.schedule.service.network.readBytesLimited
import com.glut.schedule.service.network.readStringLimited
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher

/**
 * 研究生门户的签名与参数编码。
 *
 * 与南宁的双重 MD5 协议完全不同，因此独立成一份，绝不与本科/南宁共用实现。
 * 这里只做纯函数，便于用合成值做单元测试；任何真实账号、Cookie、响应原文都不落日志。
 */
object GraduateProtocol {
    const val ENCODE_PREFIX = "encode_"

    fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    /**
     * `Y-sign` 的算法，**与门户 `Global.js#Global.Common.DataSign` 逐字对齐**：
     *
     * ```js
     * DataSign: function(t, e, a) {
     *   var n = [];
     *   for (r in t = $.extend(Global.Request.GetUrlQueryObject(e), t)) {
     *     void 0 !== t[r] && null !== t[r] && "null" !== t[r] || (t[r] = "");
     *     let e = decodeURI(t[r].toString().replace(/%/g, "%25")).toLowerCase();
     *     "null" === (e = e.replace(/[^0-9a-zA-Z]/g, "")) && (e = "");
     *     n.push(e)
     *   }
     *   return n.sort(), hex_md5(n.join(""))
     * }
     * ```
     *
     * 关键点：签名输入 = **整条请求 URL 的查询参数**（含 `Y-UriTemplate` / `Y-Method` /
     * `Y-menuid` / `Y-cft`）再叠加 payload，而不是「模板自己的参数」。
     * 这一点曾写错，导致**每一个网关请求都被服务端拒绝**，研究生功能一步都走不通；
     * 现已用真实抓包的 4 组向量在 `GraduateSupportTest` 里定标。
     *
     * @param url 已带 4 个 `Y-*` 参数的完整请求 URL。业务参数**不**含在内，与门户一致：
     *            门户正是先算签名，再由 jQuery 追加业务参数。
     */
    fun sign(url: String, payload: Map<String, String>): String {
        val params = linkedMapOf<String, String>()
        // 与 GetUrlQueryObject 一致：只取 ? 之后的查询串，并对值做一次 URL 解码。
        url.substringAfter('?', "").split('&')
            .filter { it.isNotEmpty() }
            .forEach { pair ->
                val name = percentDecode(pair.substringBefore('='))
                val value = percentDecode(pair.substringAfter('=', ""))
                params[name] = value
            }
        // payload 覆盖同名 URL 参数，与 $.extend 的行为一致。
        params.putAll(payload)
        return md5(params.values.map(::normalizeForSign).sorted().joinToString(""))
    }

    /**
     * 与门户 JS 的 `escape()` 等价：保留 `A-Za-z0-9@*_+-./`，其余按 `%XX` 编码。
     *
     * 只用于 `Y-UriTemplate` 这一个参数（门户写的是 `.format(escape(e), …)`）。
     * 与 `encodeURIComponent` 的差别恰好在 `/`、`?`、`=`、`{`、`}`：
     * `escape()` 保留 `/` 却编码 `?` `=` `{` `}`，所以不能直接用 `URLEncoder`。
     * 模板固定为 ASCII；非 ASCII 走 UTF-8 的 `%XX`（`decodeURIComponent` 解得开，
     * 而 `escape()` 的 `%uXXXX` 反而解不开）。
     */
    fun escapeTemplate(value: String): String = buildString {
        value.forEach { character ->
            if (character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' ||
                character in "@*_+-./"
            ) {
                append(character)
            } else {
                character.toString().toByteArray(Charsets.UTF_8).forEach { byte ->
                    // 注意不能用 "%%02X".format(byte)：%% 会吃掉唯一的转换，
                    // 结果原样输出字面量 %02X。先 append('%') 再格式化。
                    append('%')
                    append("%02X".format(byte))
                }
            }
        }
    }

    /** 与门户 JS 的 `encodeURI` 等价：先 URL 编码，再把 encodeURI 不转义的字符还原回去。 */
    fun encodeUri(value: String): String = URLEncoder.encode(value, "UTF-8")
        .replace("+", "%20")
        .replace("%21", "!").replace("%27", "'").replace("%28", "(").replace("%29", ")")
        .replace("%7E", "~").replace("%3B", ";").replace("%2F", "/").replace("%3F", "?")
        .replace("%3A", ":").replace("%40", "@").replace("%26", "&").replace("%3D", "=")
        .replace("%2B", "+").replace("%24", "$").replace("%2C", ",").replace("%23", "#")

    /**
     * 网关参数的编码：`encode_` 前缀 + encodeURI 结果的 Base64。
     *
     * 已经带前缀的值必须原样透传，否则重复编码会让服务端解析出错误的关键字。
     */
    fun encode(value: String): String = if (value.isEmpty() || value.startsWith(ENCODE_PREFIX)) {
        value
    } else {
        ENCODE_PREFIX + Base64.getEncoder()
            .encodeToString(encodeUri(value).replace("+", "%2B").toByteArray(Charsets.UTF_8))
    }

    /**
     * RSA/PKCS1 加密登录密码。
     *
     * **按「字符」而不是按字节切块**：密码含中文时按字节切会把一个 UTF-8 字符劈成两半，
     * 服务端解出来的口令就是乱码。每块不超过 模长-11（PKCS1 填充开销）。
     *
     * 公钥可能是裸 base64，也可能是 PEM（见 [decodePublicKey]）——两种都要能解，
     * 否则登录会在加密这一步就抛异常。
     */
    fun encryptPassword(password: String, publicKey: String): String {
        val key = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(decodePublicKey(publicKey))) as RSAPublicKey
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val maxBytes = key.modulus.bitLength() / 8 - 11
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        password.forEach { character ->
            if ((current.toString() + character).toByteArray(Charsets.UTF_8).size > maxBytes) {
                chunks += current.toString()
                current.clear()
            }
            current.append(character)
        }
        if (current.isNotEmpty()) chunks += current.toString()
        val encrypted = chunks.flatMap { chunk -> cipher.doFinal(chunk.toByteArray(Charsets.UTF_8)).toList() }
        return Base64.getEncoder().encodeToString(encrypted.toByteArray())
    }

    /**
     * 把门户公钥解成 DER 字节。
     *
     * 门户 `Global.js` 里的 `Pub_Key` **可能是裸 base64**，也可能自带 PEM armor；
     * `X509EncodedKeySpec` 要的是 DER，因此 PEM 的头尾行与所有空白都必须先剥掉。
     * 直接把整段 PEM 交给 `Base64.decode` 会在 `-----` 字符上抛
     * `IllegalArgumentException: Illegal base64 character 2d`——
     * 表现是「研究生登录失败」，与 body 被提前关闭属于同一类「第一步就死」。
     * 这两种形态都用 MockWebServer 的真实往返覆盖在 `GraduateClientHttpTest` 里。
     */
    internal fun decodePublicKey(value: String): ByteArray {
        val body = if (value.contains("-----BEGIN")) {
            value.lineSequence().filterNot { it.startsWith("-----") }.joinToString("")
        } else {
            value
        }
        return Base64.getDecoder().decode(body.filterNot { it.isWhitespace() })
    }

    private fun normalizeForSign(raw: String): String {
        if (raw == "null") return ""
        val cleaned = raw.lowercase().replace(Regex("[^0-9a-zA-Z]"), "")
        return if (cleaned == "null") "" else cleaned
    }

    private fun percentDecode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}

/**
 * 门户 bootstrap 的**白名单投影**。
 *
 * 门户的 `GetUniversityInfo` / `getLoggedInUser` 返回整份大学配置与用户对象，其中含
 * `PrivateKey` 等与排课无关的敏感字段。这里只保留排课必需的项目，绝不把原始 JSON 带出去。
 *
 * `CurrentTermEnd` / `TeachWeekEnd` 是门户给出的**权威校历**：实测
 * `CurrentTermFrom=/Date(1788710400000+0800)/`（2026-09-07）、
 * `CurrentTermEnd=/Date(1799942400000+0800)/`（2027-01-15）、`TeachWeekEnd=19`。
 * 有它就必须用，不得再拿「课表里出现过的最大周次」反推学期结束日——
 * 那会算出 2026-12-20，比真实结束日早了 26 天。
 */
data class GraduatePortalContext(
    val year: String,
    val term: String,
    val currentTermFrom: String,
    val currentTermEnd: String,
    val teachWeekEnd: String,
    val studentId: String,
    val grade: String,
    val name: String
) {
    /** 门户校历的开学日（周一）；缺失或无法解析时为 null，绝不臆造。 */
    val currentSemesterStartMonday: java.time.LocalDate?
        get() = GraduateParser.portalDate(currentTermFrom)

    /**
     * 门户校历的学期结束日。
     *
     * **不做周一对齐**：门户给的就是学期最后一天（2027-01-15 是周五），
     * 对齐到周一反而会把学期缩短。缺失即为 null。
     */
    val currentSemesterEndDate: java.time.LocalDate?
        get() = GraduateParser.portalDate(currentTermEnd)

    /** 门户给出的教学周上界；缺失时为 null，由调用方按课次反推兜底。 */
    val teachWeekEndValue: Int?
        get() = teachWeekEnd.trim().toIntOrNull()?.takeIf { it > 0 }
}

/** 会话失效与其它失败分开，供上层决定是否引导用户重新登录。 */
class GraduateSessionExpiredException(message: String) : IllegalStateException(message)

/**
 * 研究生门户 HTTP 客户端。
 *
 * 与桂林/南宁的客户端毫无共享：主机、登录方式（RSA + 验证码）、网关签名都不同。
 * Cookie 交给 [CapturingCookieJar] 托管，因为网关要求带上 `z-oauth-csrftoken`，
 * 而它既不是 JSESSIONID 也不是 CASTGC，不能被本科那套筛选逻辑丢掉。
 */
class GraduateClient(
    private val cookie: String = "",
    /**
     * 门户根地址。**只为可测试性暴露**：默认就是生产地址，
     * 测试里指向 MockWebServer，好让「body 在 `use` 内读」这条不变量能用真实 OkHttp 往返验证
     * （假 client 测不出 Response 的关闭语义）。
     */
    private val baseUrl: String = BASE_URL
) {
    companion object {
        const val BASE_URL = "https://gsems.glut.edu.cn"
        const val MAIN_FRAME = "$BASE_URL/MainFrame.htm"
        private const val CSRF_COOKIE = "z-oauth-csrftoken="
        /** 与门户一致的菜单模块号；留空表示不限定菜单（bootstrap 用）。 */
        private const val MENU_TIMETABLE = "6224"
        private const val MENU_SCORE = "6226"
        private const val MENU_STUDY_PLAN = "6225"
    }

    /**
     * 去掉尾随 `/` 的根地址。
     *
     * 与 `AcademicSemesterImportService.normalizedBaseUrl` 同一约定：调用方可能传
     * `https://host/`（MockWebServer 的 `server.url("/")` 就是这种），直接拼会得到
     * `//VerifyCode.ashx` 这种双斜杠路径。
     */
    private val root: String = baseUrl.trimEnd('/')

    private val gatewayUrl: String get() = "$root/Ajax.ashx"
    private val referer: String get() = "$root/MainFrame.htm"

    private val jar = CapturingCookieJar().apply {
        if (cookie.isNotBlank()) seedFromCookieHeader(cookie, root.toHttpUrl(), path = "/")
    }
    private val client = OkHttpClient.Builder()
        .cookieJar(jar)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    /** 完整 Cookie 头（含 csrf），网关请求需要。 */
    fun cookieHeader(): String = jar.fullCookieHeader()

    /**
     * 执行请求并在 `use` **内部**读完响应体。
     *
     * 绝不能把 `Response` 从 `use` 里返回：`use` 在返回前就会 `close()`，而 OkHttp 的
     * `Response.close()` 会一并关掉 body，之后再读 body 抛 `IllegalStateException`。
     * 那样一来 `captcha()` 与 `text()`（`login()` 和所有网关请求都走它）会全部失败，
     * 表现是「无法获取研究生教务验证码」——第一步就死，且异常被上层吞掉、看不到原因。
     * 本科/南宁的客户端一直是「在 use 内部读 body」，这里对齐同一种写法。
     */
    private fun <T> withBody(request: Request, read: (okhttp3.Response) -> T): T =
        client.newCall(request).execute().use { response ->
            val code = response.code
            if (code == 401 || code == 403 || code in 300..399) {
                throw GraduateSessionExpiredException("研究生登录已失效，请重新登录")
            }
            require(code in 200..299) { "研究生教务服务暂时异常（HTTP $code）" }
            read(response)
        }

    private fun text(request: Request): String = withBody(request) { response ->
        response.body?.readStringLimited(MAX_HTML_RESPONSE_BYTES) ?: error("研究生教务返回空响应")
    }

    /** 登录页验证码图片。参数名与门户一致（`VerifyCode.ashx?time=`）。 */
    fun captcha(): ByteArray = withBody(
        Request.Builder().url("$root/VerifyCode.ashx?time=${System.currentTimeMillis()}").build()
    ) { response ->
        response.body?.readBytesLimited(MAX_IMAGE_RESPONSE_BYTES)?.takeIf { it.size > 10 }
            ?: error("验证码图片为空")
    }

    /** 登录：密码用门户公钥加密，`ret` 非 0 一律按失败处理。 */
    fun login(username: String, password: String, captcha: String) {
        val script = text(Request.Builder().url("$root/js/global/Global.js").build())
        val publicKey = Regex("""Pub_Key\s*[:=]\s*["']([^"']+)["']""").find(script)?.groupValues?.get(1)
            ?.let { value ->
                if (value.contains("BEGIN")) value
                else "-----BEGIN PUBLIC KEY-----\n$value\n-----END PUBLIC KEY-----"
            }
            ?: error("研究生登录加密配置无法识别，请稍后重试")
        val form = FormBody.Builder()
            .add("userid", username)
            .add("userpwd", GraduateProtocol.encryptPassword(password, publicKey))
            .add("usertype", "STU")
            .add("verifyCode", captcha)
            .add("functype", "")
            .build()
        // 登录响应是对象（`{ret:…}`，实测如此）。这里仍把解析失败换成可读文案：
        // 三番五次被「笼统错误」耽误定位，宁可多这三行也不要再抛裸 JSONException。
        val rawLogin = text(Request.Builder().url("$root/Login.ashx").post(form).build())
        val result = runCatching { JSONObject(rawLogin.trim()) }.getOrElse {
            error("研究生登录返回了无法识别的数据，请稍后重试")
        }
        when (result.optInt("ret", -1)) {
            0 -> Unit
            3 -> error("此账号需要额外身份验证，请先在研究生门户完成验证")
            else -> error("研究生登录失败，请检查教务密码与验证码")
        }
    }

    /**
     * 调用 `/Ajax.ashx` 网关。
     *
     * 与门户 `Global.Request.GetPostUrl` 同构造顺序：
     * 1. 先拼 4 个 `Y-*` 参数（`Y-UriTemplate` 用 `escape()` 转义，保留 `/`）；
     * 2. **拿这条 URL 去算 `Y-sign`**（这是唯一正确的口径，见 [GraduateProtocol.sign]）；
     * 3. 再把 `Y-sign` 附上——真实抓包里它排在业务参数**之前**；
     * 4. 业务参数：GET 追加到 URL 查询串，POST 放进表单体（与 jQuery 的 `$.ajax` 一致）。
     *
     * 返回网关响应里的 `data`；没有 `data` 时返回整个响应体文本。
     */
    fun gateway(
        template: String,
        payload: Map<String, String> = emptyMap(),
        menu: String = "",
        method: String = "GET"
    ): String {
        val csrf = cookieHeader().split(';').map(String::trim)
            .firstOrNull { it.startsWith(CSRF_COOKIE) }
            ?.substringAfter('=')
            .orEmpty()
        if (csrf.isBlank()) throw GraduateSessionExpiredException("研究生登录已失效，请重新登录")
        // Y-UriTemplate 必须是 escape() 后的形态；用 addEncodedQueryParameter 提交，
        // 否则 OkHttp 会把其中的 % 再编码一次（%7B → %257B），服务端解不出模板。
        val signedUrl = gatewayUrl.toHttpUrl().newBuilder()
            .addEncodedQueryParameter("Y-UriTemplate", GraduateProtocol.escapeTemplate(template))
            .addQueryParameter("Y-Method", method)
            .addQueryParameter("Y-menuid", menu)
            .addQueryParameter("Y-cft", GraduateProtocol.md5(URLDecoder.decode(csrf.replace("+", "%2B"), "UTF-8")))
            .build()
        val urlBuilder = signedUrl.newBuilder()
            .addQueryParameter("Y-sign", GraduateProtocol.sign(signedUrl.toString(), payload))
        if (method == "GET") {
            payload.forEach { (key, value) -> urlBuilder.addQueryParameter(key, GraduateProtocol.encode(value)) }
        }
        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("Referer", referer)
        if (method != "GET") {
            request.post(
                FormBody.Builder().apply {
                    payload.forEach { (key, value) -> add(key, GraduateProtocol.encode(value)) }
                }.build()
            )
        }
        val raw = text(request.build())
        val trimmed = raw.trim()
        // 门户部分接口直接返回**裸 JSON 数组**（实测 `GetTimeTableByStudent` 的响应体以 `[{` 开头）。
        // `JSONObject("[]")` 会抛 JSONException，于是课表导入必然落进下面那句「无法识别的数据」。
        // 数组没有 ret/success 包装，整段就是数据本身，原样交给调用方。
        if (trimmed.startsWith("[")) return raw
        val root = runCatching { JSONObject(trimmed) }.getOrElse {
            error("研究生教务返回了无法识别的数据，请重新登录后重试")
        }
        require(root.optBoolean("success", true) && root.optInt("ret", 0) == 0) {
            "研究生教务查询失败，请重新登录后重试"
        }
        return if (root.has("data")) root.get("data").toString() else raw
    }

    /** 门户当前学年/学期与登录用户，**只投影**排课必需字段。 */
    fun bootstrap(): GraduatePortalContext {
        val university = runCatching { JSONObject(gateway("UserService/GetUniversityInfo")) }
            .getOrElse { error("研究生门户学年信息无法识别，请稍后重试") }
        val user = runCatching {
            JSONObject(gateway("UserService/getLoggedInUser/", method = "POST"))
        }.getOrElse { error("研究生门户用户信息无法识别，请稍后重试") }
        val studentId = user.optString("UserID").trim()
        if (studentId.isEmpty()) error("研究生门户未返回学号，请重新登录")
        return GraduatePortalContext(
            year = university.optString("CurrentYear").trim(),
            term = university.optString("CurrentTerm").trim(),
            // 校历用权威字段：有 CurrentTermEnd / TeachWeekEnd 就绝不反推。
            currentTermFrom = university.optString("CurrentTermFrom").trim(),
            currentTermEnd = university.optString("CurrentTermEnd").trim(),
            teachWeekEnd = university.optString("TeachWeekEnd").trim(),
            studentId = studentId,
            grade = user.optJSONObject("Data")?.optString("Grade").orEmpty().trim(),
            name = user.optString("UserName").trim()
        )
    }

    fun schedule(year: String, term: String, studentId: String): String = gateway(
        "TimeTableNewService/GetTimeTableByStudent/{Year}/{Term}/{XH}?IsConti={IsConti}",
        mapOf("Year" to year, "Term" to term, "XH" to studentId, "IsConti" to "0"),
        menu = MENU_TIMETABLE
    )

    /**
     * 调课查询。
     *
     * 当前样本为空，因此**只作为独立提示**上报：绝不据此二次修改主课表，也不套用本科调课算法。
     */
    fun adjustments(year: String, term: String, studentId: String): String = gateway(
        "TimeTableNewService/GetCheckedAdjustCourseList/{Year}/{Term}?GraduateID={GraduateID}",
        mapOf("Year" to year, "Term" to term, "GraduateID" to studentId),
        menu = MENU_TIMETABLE
    )

    fun scores(studentId: String): String =
        gateway("ScoreService/QueryStuScore/{xh}/", mapOf("xh" to studentId), menu = MENU_SCORE)

    fun studyPlan(studentId: String): String =
        gateway("SelectCourseService/sc/{xh}", mapOf("xh" to studentId), menu = MENU_STUDY_PLAN)
}
