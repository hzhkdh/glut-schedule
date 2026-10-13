package com.glut.schedule.service.academic

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * WebView 顶层导航的分类结果。
 *
 * 只对**顶层导航**分类：子资源（JS/CSS/图标/iframe）一律放行，
 * 拦子资源只会让页面白屏，换不来任何安全性。
 */
internal enum class WechatNav {
    /** 统一身份认证页：放行。 */
    CAS_PAGE,

    /** 微信二维码的渲染域：放行。 */
    THIRD_PARTY_OK,

    /** 教务的已登录落地页：**登录成功**。 */
    ACADEMIC_LANDING,

    /**
     * 教务的登录页 / 登出页：说明教务**没有**建立会话。
     *
     * 这是「CAS 登录成功但教务没认账」这种半成功的唯一判别点，绝不能当成功。
     */
    ACADEMIC_LOGIN_PAGE,

    /** 白名单外：拦截并在界面内提示，不转外部浏览器。 */
    BLOCKED
}

/** 出现在顶层导航里就等于「没有会话」的教务端点。 */
private val academicLoginMarkers = listOf("affairlogin.do", "logout_security_check")

/** 校方域名后缀。**必须带前导点**，否则 `jw.glut.edu.cn.evil.example` 会漏进来。 */
private const val CAMPUS_HOST_SUFFIX = ".glut.edu.cn"

/** 会话票据名，与 [CapturingCookieJar.cookieHeader] 的筛选口径保持一致。 */
private val sessionCookieNames = setOf("jsessionid", "castgc", "tgc")

/**
 * 判断一次顶层导航该放行、该判成功、还是该拦截。
 *
 * 只认 https：与 `network_security_config.xml` 的口径一致。CAS 若把链路降级到明文，
 * 宁可停住并如实提示，也不能放行——否则校园网里的中间人可以替换统一身份认证页拿到密码。
 */
internal fun classifyWechatNavigation(url: String): WechatNav {
    val parsed = url.toHttpUrlOrNull() ?: return WechatNav.BLOCKED
    if (parsed.scheme != "https") return WechatNav.BLOCKED
    val host = parsed.host.lowercase()
    val path = parsed.encodedPath.lowercase()

    return when {
        host == CasWebLoginConfig.WECHAT_OAUTH_HOST -> WechatNav.THIRD_PARTY_OK
        host == CasWebLoginConfig.CAS_HOST -> WechatNav.CAS_PAGE
        host == CasWebLoginConfig.ACADEMIC_HOST && path.startsWith("/academic/") ->
            if (academicLoginMarkers.any(path::contains)) WechatNav.ACADEMIC_LOGIN_PAGE
            else WechatNav.ACADEMIC_LANDING

        // 其余校方子域（例如一站式服务大厅）允许继续走，但不算登录成功——
        // 停在那儿说明 ticket 没回到教务，不能骗自己说登上了。
        host.endsWith(CAMPUS_HOST_SUFFIX) -> WechatNav.CAS_PAGE

        else -> WechatNav.BLOCKED
    }
}

/**
 * 合并 WebView 里两个域名各自的 Cookie 头。
 *
 * **教务侧优先**：两侧都可能有 `JSESSIONID`，只有 `jw.glut.edu.cn` 的那个才是本地教务会话；
 * 取错就会把 CAS 的会话当成教务会话，后续二十来个探测全部白跑。
 *
 * 只保留 [sessionCookieNames] 里的票据名，其余（`route=`、`remember-me=` 之类）一律丢掉——
 * 下游 [CapturingCookieJar.seedFromCookieHeader] 要的正是这种 name=value 拼串。
 */
internal fun mergeWebLoginCookies(jwCookieHeader: String, casCookieHeader: String): String {
    val merged = linkedMapOf<String, String>()
    // CAS 先放、教务后放，同名时后放的覆盖前者。
    listOf(casCookieHeader, jwCookieHeader).forEach { header ->
        header.split(';').forEach cookieLoop@{ segment ->
            val pair = segment.trim()
            val separator = pair.indexOf('=')
            if (separator <= 0) return@cookieLoop
            val name = pair.substring(0, separator).trim()
            val value = pair.substring(separator + 1).trim()
            if (name.isEmpty() || value.isEmpty()) return@cookieLoop
            if (name.lowercase() !in sessionCookieNames) return@cookieLoop
            merged[name] = value
        }
    }
    return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

/**
 * 教务**本人**的会话是否已经建立。
 *
 * 刻意不复用 [hasUsableAcademicCookie]：那个函数把 `CASTGC=` 也算作可用，
 * 而 CASTGC 只证明「CAS 侧登录了」，**不证明教务消费了 ticket**。
 * 两者的语义差正是半成功的判别依据，所以这里必须是独立函数。
 */
internal fun hasLocalAcademicSession(cookieHeader: String): Boolean =
    cookieHeader.contains("JSESSIONID=", ignoreCase = true)

/**
 * 微信登录后，是否该清掉本机保存的学号密码。
 *
 * 微信登录不带密码，但机器上可能还留着**另一个人**的凭据。留着会有实际危害：
 * 各页刷新时的 [AcademicLoginService.silentLogin] 会拿那份凭据重建会话，
 * 于是「看着是 B 的界面，刷新一下变回 A 的数据」。
 *
 * 三条规则：
 *  - 同号 → 保留（那份密码仍然有效，静默刷新还能继续用）；
 *  - 异号 → 清除；
 *  - 任一侧为空（学号没解析出来）→ **不清**，保守为上；这种情况本就会走用户手输兜底。
 */
internal fun shouldClearStoredCredentialsForWechatLogin(
    storedUsername: String,
    wechatStudentNumber: String
): Boolean {
    val stored = storedUsername.trim()
    val incoming = wechatStudentNumber.trim()
    return stored.isNotEmpty() && incoming.isNotEmpty() && stored != incoming
}

/**
 * 从统一身份认证递给教务的跳转里取出学号。
 *
 * 微信登录这条路没有密码输入框，学号只能从别处获取。**从教务页面反查是不可靠的**：
 * 页面里同时躺着内部账号、工号、模块 id 等一串纯数字，抓错了也照样是「6~14 位纯数字」，
 * 一路绿灯存下去，然后用它去推算年级、选学期——错得非常安静。
 *
 * 而 IAM 下发的跳转 URL 里是明写的：`…caLoginZF.do?userName=<学号>…`、
 * `…j_sso_security_check?j_username=<学号>…`。这是权威来源。
 */
internal fun extractStudentNumberFromHandoffUrl(url: String): String? {
    val parsed = url.toHttpUrlOrNull() ?: return null
    // 只认教务主机。用 `.glut.edu.cn` 后缀会连统一身份认证自己的跳转也一起收进来
    // （`cas.glut.edu.cn` 同样以它结尾），那不是我们要的东西。
    if (!parsed.host.equals(CasWebLoginConfig.ACADEMIC_HOST, ignoreCase = true)) return null
    return sequenceOf("userName", "j_username")
        .mapNotNull { parsed.queryParameter(it) }
        .map { it.trim() }
        .firstOrNull { it.length in 6..14 && it.all(Char::isDigit) }
}

/**
 * 把教务的**明文**地址升级成 https，非教务域名一律返回 null。
 *
 * 统一身份认证下发给教务的单点入口是明文的：
 * `http://jw.glut.edu.cn/academic/caLoginZF.do?verify=…&userName=…&strSysDatetime=…`
 * 教务自己也会 301 到 https——但**那一次明文请求**已经把学号和签名摊在校园网上，
 * 中间人拿到就能重放。在 App 侧直接升级，这一步就根本不会发生。
 *
 * 升级是安全的：`verify` 只对 `userName + 共享密钥 + strSysDatetime` 做 MD5，与 scheme 无关
 * （已实测 https 版本换票成功）。
 *
 * 只认教务主机，不擅自升级别人的地址——否则白名单就形同虚设。
 */
internal fun upgradeAcademicHttpToHttps(url: String): String? {
    val parsed = url.toHttpUrlOrNull() ?: return null
    if (parsed.scheme != "http") return null
    if (!parsed.host.equals(CasWebLoginConfig.ACADEMIC_HOST, ignoreCase = true)) return null
    return parsed.newBuilder().scheme("https").build().toString()
}
