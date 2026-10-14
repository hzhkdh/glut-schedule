package com.glut.schedule

import com.glut.schedule.data.settings.ImportSource
import com.glut.schedule.service.academic.AcademicLoginResult
import com.glut.schedule.service.academic.importSourceForCampusBaseUrl
import com.glut.schedule.service.graduate.GraduateClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 续期来源必须由**会话主机**反推，而不是读 `lastImportSource`。
 *
 * 两者会分叉：cookie 与 `campusBaseUrl` 在登录成功那一刻连着写
 *（`DirectLoginViewModel` 里 `saveCookie` 与 `saveCampusBaseUrl` 是相邻两行），
 * 而 `lastImportSource` 要等**整份课表导入成功**才落库（`:964-965`）。
 * 所以「登录成功、导入中途失败」时，会话已经是新的、来源却还是旧的——
 * 续期会按旧来源走：南宁会话被当成桂林 → 不弹窗、静默换票必然失败、功能静默失效。
 */
class AcademicSessionSourceTest {

    @Test
    fun nanningSessionMapsToNanning() {
        assertEquals(
            ImportSource.NANNING,
            importSourceForCampusBaseUrl(AcademicLoginResult.NANNING_URL)
        )
    }

    @Test
    fun graduateSessionMapsToGraduate() {
        assertEquals(
            ImportSource.GRADUATE,
            importSourceForCampusBaseUrl(GraduateClient.BASE_URL)
        )
    }

    @Test
    fun guilinSessionMapsToGuilin() {
        assertEquals(
            ImportSource.GUILIN,
            importSourceForCampusBaseUrl(AcademicLoginResult.DEFAULT_GUILIN_URL)
        )
    }

    @Test
    fun unknownOrEmptyNeverGuessesACaptchaSource() {
        // 认不出来时一律当桂林。猜错的代价是不对称的：猜成南宁/研究生会给用户弹一个
        // 他根本用不上的验证码框（而桂林没有验证码取图通道，输了也永远不对）；
        // 猜成桂林最坏只是静默换票失败，用户看到的是原有那句提示。
        assertEquals(ImportSource.GUILIN, importSourceForCampusBaseUrl(""))
        assertEquals(ImportSource.GUILIN, importSourceForCampusBaseUrl("https://example.com"))
    }

    @Test
    fun trailingSlashIsTolerated() {
        // 落库值与常量理当逐字相同，但多一个斜杠就静默走错来源的话，
        // 排查成本远高于这里 trim 一下。
        assertEquals(
            ImportSource.NANNING,
            importSourceForCampusBaseUrl(AcademicLoginResult.NANNING_URL + "/")
        )
    }
}
