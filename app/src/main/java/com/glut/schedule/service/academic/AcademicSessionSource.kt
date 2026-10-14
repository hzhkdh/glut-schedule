package com.glut.schedule.service.academic

import com.glut.schedule.data.settings.ImportSource
import com.glut.schedule.service.graduate.GraduateClient

/**
 * 由「当前持有的会话主机」反推它属于哪个来源。
 *
 * **为什么不用 `lastImportSource`**：那两个值会分叉。cookie 与 `campusBaseUrl` 是在登录成功
 * 那一刻连着写的（`DirectLoginViewModel` 里 `saveCookie` 与 `saveCampusBaseUrl` 相邻两行），
 * 而 `lastImportSource` 要等**整份课表导入成功**才落库（`:964-965`）。
 * 于是「登录成功、导入中途失败」时，会话已经换成新的了、来源却还是旧的——
 * 续期会按旧来源分派：南宁会话被当成桂林 → 不弹窗、静默换票必然失败、功能静默失效，
 * 而用户完全看不出发生了什么。
 *
 * `campusBaseUrl` 与 cookie 同一次写入，所以它才是「这条会话是谁的」的可靠答案。
 *
 * 认不出来时一律回退桂林：猜错的代价不对称——猜成南宁/研究生会给用户弹一个他根本用不上的
 * 验证码框（桂林没有验证码取图通道，输了也永远不对），而猜成桂林最坏只是静默换票失败，
 * 用户看到的是原来那句提示。
 */
internal fun importSourceForCampusBaseUrl(baseUrl: String): ImportSource {
    // 落库值与常量理当逐字相同，但多一个斜杠就静默走错来源的话，排查成本远高于这里 trim 一下。
    return when (baseUrl.trim().trimEnd('/')) {
        AcademicLoginResult.NANNING_URL -> ImportSource.NANNING
        GraduateClient.BASE_URL -> ImportSource.GRADUATE
        else -> ImportSource.GUILIN
    }
}
