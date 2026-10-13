package com.glut.schedule.service.academic

/**
 * 学校统一身份认证（金智 IAM）网页登录的入口配置。
 *
 * 桂林本部的教务系统自己不提供第三方登录，唯一能免密的路径是绕道学校的统一身份认证：
 * 教务登录页上的「统一身份认证」链接指向 [CAS_LOGIN_PAGE]，而该页已开通微信扫码登录。
 *
 * 实测确认（2026-10，公网直连）：
 *  - [CAS_LOGIN_PAGE] 接受 `?redirectUri=<目标>`，并把它原样回显到隐藏字段 `redirectUri`；
 *    裸 CAS 那套 `?service=` **不被识别**，所以不能照搬标准 CAS 的拼法。
 *  - 该页的二维码由 `login6.js` 用 canvas 渲染，内容是
 *    `open.weixin.qq.com/connect/oauth2/authorize?appid=<企业微信AppID>&...`，
 *    画完立即调用 `checkLoginStatus(iamSessionId)` 轮询结果。
 *
 * 最后一条是本方案的立身之本：**登录是「发起方」自己轮询完成的**，微信只是确认方。
 * 会话只会落在显示二维码的那一方，所以必须用 WebView 把那个页面跑起来，
 * 而不是把二维码抠出来另作展示（那样轮询就断了）。
 */
object CasWebLoginConfig {
    const val CAS_HOST = "cas.glut.edu.cn"
    const val ACADEMIC_HOST = "jw.glut.edu.cn"

    const val CAS_LOGIN_PAGE = "https://$CAS_HOST/portal/login.html"

    /**
     * 微信二维码的渲染域名，必须放行。
     *
     * 二维码内容是微信的 OAuth 授权链接，但金智是用 canvas 把这段文本**画**成二维码的，
     * 并不真的导航过去。这里仍然放行，是为了兜住「某些版本改成顶层跳转」的情况——
     * 拦掉的话二维码会直接不出现。该域不承载任何本校数据。
     */
    const val WECHAT_OAUTH_HOST = "open.weixin.qq.com"

    /**
     * 教务系统在统一身份认证里注册的 client_id。
     *
     * 微信扫码登录**必须**从这里进，绝不能用 `redirectUri=` 自己拼一个地址。
     *
     * 两者的区别是致命的：`redirectUri` 只表达「登录完把我送到这个地址」，**什么凭证都不带**。
     * 教务收到一个没有 ticket 的空请求，只能吐一张空提示页、顺手发个匿名 JSESSIONID ——
     * 表面上 cookie 里有会话，真去导入时教务就说「登录状态已失效」。
     * 而 `sso/request?client_id=` 是 IAM 认可的应用入口：凭证会被送回这个注册应用，
     * 由教务消费 ticket、建立真正的会话。
     *
     * 这个 id 是学校一站式服务大厅里「教务系统」图标指向的地址上读出来的。
     */
    const val ACADEMIC_CLIENT_ID = "APP007"

    /**
     * 抓 Cookie 时用的教务 URL。
     *
     * 必须带 `/academic` 路径：`CookieManager.getCookie(url)` 是按 URL 匹配 path 的，
     * 教务的 JSESSIONID 是 `path=/academic`，拿根路径去问会一问三不知。
     * 用登录后的落地页只是为了让这个常量名实相符——凡 `/academic/` 下的地址都等效。
     */
    const val ACADEMIC_COOKIE_URL = "https://$ACADEMIC_HOST/academic/preGotoAffairFrame.do"

    /** CAS 页自身的 cookie 也一并抓，但合并时教务处优先。 */
    const val CAS_COOKIE_URL = CAS_LOGIN_PAGE

    /**
     * 微信登录的起点。
     *
     * 未登录时 IAM 会把它导向统一身份认证的登录页（微信扫码就在那里）；
     * 登录完成后凭证被送回教务，最终落到 `preGotoAffairFrame.do`——教务的已登录落地页。
     */
    fun casLoginUrl(): String = "https://$CAS_HOST/sso/request?client_id=$ACADEMIC_CLIENT_ID"
}
