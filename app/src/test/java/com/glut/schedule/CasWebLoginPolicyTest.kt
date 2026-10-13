package com.glut.schedule

import com.glut.schedule.service.academic.CasWebLoginConfig
import com.glut.schedule.service.academic.WechatNav
import com.glut.schedule.service.academic.classifyWechatNavigation
import com.glut.schedule.service.academic.extractStudentNumberFromHandoffUrl
import com.glut.schedule.service.academic.hasLocalAcademicSession
import com.glut.schedule.service.academic.mergeWebLoginCookies
import com.glut.schedule.service.academic.shouldClearStoredCredentialsForWechatLogin
import com.glut.schedule.service.academic.upgradeAcademicHttpToHttps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CasWebLoginPolicyTest {

    /**
     * 必须走注册应用入口，而不是自己拼 `redirectUri=`。
     *
     * `redirectUri` 只负责「登录后去哪」，不带任何凭证——教务收到空请求后发的那个
     * 匿名 JSESSIONID 会让导入在最后一步报「登录状态已失效」。
     */
    @Test
    fun casLoginUrlUsesTheRegisteredClientId() {
        assertEquals(
            "https://cas.glut.edu.cn/sso/request?client_id=APP007",
            CasWebLoginConfig.casLoginUrl()
        )
    }

    @Test
    fun casAndAcademicHostsAreNavigable() {
        assertEquals(
            WechatNav.CAS_PAGE,
            classifyWechatNavigation("https://cas.glut.edu.cn/portal/login.html?redirectUri=x")
        )
        assertEquals(
            WechatNav.ACADEMIC_LANDING,
            classifyWechatNavigation("https://jw.glut.edu.cn/academic/preGotoAffairFrame.do#/menu")
        )
        assertEquals(
            WechatNav.ACADEMIC_LANDING,
            classifyWechatNavigation("https://jw.glut.edu.cn/academic/ssoLogin.do?ticket=ST-1")
        )
    }

    /**
     * 最关键的一条：教务的登录页 / 登出页出现在顶层导航里，等于「教务没认这个会话」。
     * 一旦把它们当成落地页，用户会看到「导入成功」而实际什么都没拿到。
     */
    @Test
    fun academicLoginPagesAreNotTreatedAsLanding() {
        assertEquals(
            WechatNav.ACADEMIC_LOGIN_PAGE,
            classifyWechatNavigation("https://jw.glut.edu.cn/academic/affairLogin.do")
        )
        assertEquals(
            WechatNav.ACADEMIC_LOGIN_PAGE,
            classifyWechatNavigation(
                "https://jw.glut.edu.cn/academic/logout_security_check;jsessionid=6C06CF24.TA3"
            )
        )
    }

    @Test
    fun wechatOauthHostIsAllowedSoTheQrCanRender() {
        assertEquals(
            WechatNav.THIRD_PARTY_OK,
            classifyWechatNavigation(
                "https://open.weixin.qq.com/connect/oauth2/authorize?appid=wwb12e29dea5ca02af"
            )
        )
    }

    @Test
    fun lookalikeHostsAndSuffixTricksAreBlocked() {
        assertTrue(
            listOf(
                "https://cas.glut.edu.cn.evil.example/portal/login.html",
                "https://evilglut.edu.cn/portal/login.html",
                "https://glut.edu.cn/portal/login.html",
                "https://jw.glut.edu.cn.evil.example/academic/ssoLogin.do",
                "https://evil.example/academic/ssoLogin.do"
            ).all { classifyWechatNavigation(it) == WechatNav.BLOCKED }
        )
    }

    @Test
    fun nonHttpSchemesAndPlainHttpAreBlocked() {
        assertTrue(
            listOf(
                "intent://scan/#Intent;scheme=zxing;end",
                "weixin://dl/business/",
                "market://details?id=com.tencent.mm",
                // 明文入口必须停住：放行等于让校园网里的中间人替换统一身份认证页。
                "http://cas.glut.edu.cn/portal/login.html",
                "http://jw.glut.edu.cn/academic/ssoLogin.do"
            ).all { classifyWechatNavigation(it) == WechatNav.BLOCKED }
        )
    }

    @Test
    fun jwCookiesWinOverCasCookiesWithTheSameName() {
        val merged = mergeWebLoginCookies(
            jwCookieHeader = "JSESSIONID=jw-session",
            casCookieHeader = "JSESSIONID=cas-session; CASTGC=cas-ticket"
        )

        assertTrue(merged.contains("JSESSIONID=jw-session"))
        assertFalse(merged.contains("cas-session"))
        assertTrue(merged.contains("CASTGC=cas-ticket"))
    }

    @Test
    fun onlySessionCookieNamesSurviveTheMerge() {
        val merged = mergeWebLoginCookies(
            jwCookieHeader = "JSESSIONID=jw; route=server2; remember-me=false; _ga=GA1.2",
            casCookieHeader = ""
        )

        assertEquals("JSESSIONID=jw", merged)
    }

    /**
     * CASTGC 只说明「CAS 侧登录了」，不说明教务消费了 ticket。
     * 这与 [com.glut.schedule.service.academic.shouldUseExistingAcademicCookie] 把 CASTGC 算作
     * 可用的口径**刻意不同**，两条语义各自钉死，互不串味。
     */
    @Test
    fun castgcAloneIsNotALocalAcademicSession() {
        assertFalse(hasLocalAcademicSession("CASTGC=cas-ticket"))
        assertFalse(hasLocalAcademicSession("TGC=tgt"))
        assertFalse(hasLocalAcademicSession(""))
        assertTrue(hasLocalAcademicSession("CASTGC=t; JSESSIONID=jw-session"))
    }

    /**
     * 统一身份认证递给教务的单点入口是明文的，里面带着学号和签名。
     * 升级到 https 再放行，校园网里那次明文请求就不存在了。
     */
    @Test
    fun academicPlainHttpIsUpgradedToHttps() {
        assertEquals(
            "https://jw.glut.edu.cn/academic/caLoginZF.do?verify=abc&userName=2022123456",
            upgradeAcademicHttpToHttps(
                "http://jw.glut.edu.cn/academic/caLoginZF.do?verify=abc&userName=2022123456"
            )
        )
        // 已经是 https 的不动，非教务域名不擅自升级（否则白名单形同虚设）。
        assertNull(upgradeAcademicHttpToHttps("https://jw.glut.edu.cn/academic/caLoginZF.do"))
        assertNull(upgradeAcademicHttpToHttps("http://cas.glut.edu.cn/portal/login.html"))
        assertNull(upgradeAcademicHttpToHttps("http://evil.example/academic/caLoginZF.do"))
        assertNull(upgradeAcademicHttpToHttps("intent://scan/#Intent;end"))
    }

    /**
     * 统一身份认证递给教务的那两跳必须被判成「已登录落地页」。
     *
     * `caLoginZF.do` 是验签入口，`j_sso_security_check` 是真正建会话的那一步——
     * 任何一个被判成 ACADEMIC_LOGIN_PAGE 或 BLOCKED，SSO 都会断在半路。
     */
    @Test
    fun academicSsoHandoffHopsAreLandingPages() {
        assertEquals(
            WechatNav.ACADEMIC_LANDING,
            classifyWechatNavigation(
                "https://jw.glut.edu.cn/academic/caLoginZF.do?verify=ABC123" +
                    "&userName=2022123456&strSysDatetime=2026-10-1317:03:37&jsName=teacher" +
                    "&openType=1&gnmkdm=M011&url=index_new.jsp"
            )
        )
        assertEquals(
            WechatNav.ACADEMIC_LANDING,
            classifyWechatNavigation(
                "https://jw.glut.edu.cn/academic/j_sso_security_check?j_username=2022123456&sso_password=abc"
            )
        )
        assertEquals(
            WechatNav.ACADEMIC_LANDING,
            classifyWechatNavigation("https://jw.glut.edu.cn/academic/index_new.jsp")
        )
    }

    /**
     * 学号必须从 IAM 递给教务的跳转里取——微信登录没有密码框，这是唯一权威来源。
     */
    @Test
    fun studentNumberComesFromTheHandoffUrl() {
        assertEquals(
            "2022123456",
            extractStudentNumberFromHandoffUrl(
                "https://jw.glut.edu.cn/academic/caLoginZF.do?verify=ABC&userName=2022123456" +
                    "&strSysDatetime=2026-10-1317:03:37&jsName=teacher"
            )
        )
        assertEquals(
            "2022123456",
            extractStudentNumberFromHandoffUrl(
                "https://jw.glut.edu.cn/academic/j_sso_security_check?j_username=2022123456&sso_password=x"
            )
        )
        // 别家域名、没有学号参数、以及不合形态的值，一律不认。
        assertNull(extractStudentNumberFromHandoffUrl("https://cas.glut.edu.cn/portal/login.html?userName=2022123456"))
        assertNull(extractStudentNumberFromHandoffUrl("https://jw.glut.edu.cn/academic/index_new.jsp"))
        assertNull(extractStudentNumberFromHandoffUrl("https://jw.glut.edu.cn/academic/caLoginZF.do?userName=abc"))
        assertNull(extractStudentNumberFromHandoffUrl("https://jw.glut.edu.cn/academic/caLoginZF.do?userName=123"))
    }

    @Test
    fun storedCredentialsAreClearedOnlyForADifferentStudent() {
        assertTrue(shouldClearStoredCredentialsForWechatLogin("2021000001", "2022000002"))
        assertFalse(shouldClearStoredCredentialsForWechatLogin(" 2022000002 ", "2022000002"))
        assertFalse(shouldClearStoredCredentialsForWechatLogin("", "2022000002"))
        assertFalse(shouldClearStoredCredentialsForWechatLogin("2021000001", ""))
        assertFalse(shouldClearStoredCredentialsForWechatLogin("", ""))
    }
}
