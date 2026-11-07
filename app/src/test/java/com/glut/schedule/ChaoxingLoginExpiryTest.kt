package com.glut.schedule

import com.glut.schedule.service.chaoxing.ChaoxingClient
import com.glut.schedule.service.chaoxing.ChaoxingLoginRequiredException
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class ChaoxingLoginExpiryTest {
    private val courseUrl = "https://mooc1-api.chaoxing.com/mycourse/backclazzdata".toHttpUrl()

    @Test fun redirectedAuthenticationPageRequiresLogin() {
        val client = ChaoxingClient { _, _ ->
            "https://passport2.chaoxing.com/login?refer=safe".toHttpUrl() to "<html>登录</html>"
        }
        assertThrows(ChaoxingLoginRequiredException::class.java) { client.sync() }
    }

    @Test fun recognizableLoginFormRequiresLoginWithoutRedirect() {
        val client = ChaoxingClient { _, _ -> courseUrl to """
            <form id="loginFrom"><input id="phone"><input type="password" id="pwd"></form>
        """ }
        assertThrows(ChaoxingLoginRequiredException::class.java) { client.sync() }
    }

    @Test fun malformedResponseAndUnrelatedPasswordFieldRemainOrdinaryFailures() {
        for (body in listOf("<html>服务暂不可用，请稍后重试</html>",
            "<form><input type='password'></form>", "{\"unexpected\":[]}")) {
            val client = ChaoxingClient { _, _ -> courseUrl to body }
            val error = assertThrows(IllegalStateException::class.java) { client.sync() }
            assertEquals("学习通课程列表格式发生变化", error.message)
        }
    }

    @Test fun validEmptyCourseListRemainsSuccessful() {
        val client = ChaoxingClient { _, _ -> courseUrl to "{\"channelList\":[]}" }
        assertTrue(client.sync().failed.isEmpty())
    }
}
