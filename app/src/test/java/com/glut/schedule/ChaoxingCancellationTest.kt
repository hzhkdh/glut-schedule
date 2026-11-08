package com.glut.schedule

import com.glut.schedule.service.chaoxing.ChaoxingClient
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** 用合成传输验证取消不会被课程级失败兜底吞掉，也不会继续请求下一课程。 */
class ChaoxingCancellationTest {
    @Test fun cancelledClientDoesNotStartAnyRequest() {
        var requests = 0
        val client = ChaoxingClient { url, _ -> requests++; url to "{\"channelList\":[]}" }
        client.cancel()
        try { client.sync(); fail("取消后不得继续同步") } catch (_: CancellationException) { }
        assertEquals(0, requests)
    }

    @Test fun cancellationInsideCourseStopsFurtherTaskRequests() {
        var requests = 0
        lateinit var client: ChaoxingClient
        client = ChaoxingClient { url, _ ->
            requests++
            if (requests == 1) {
                url to """{"channelList":[{"cpi":"3","content":{"id":"2","course":{"data":[{"id":"1","name":"合成课程"}]}}}]}"""
            } else {
                client.cancel()
                throw java.io.IOException("合成取消中的网络错误")
            }
        }
        try { client.sync(); fail("取消必须退出课程循环") } catch (_: CancellationException) { }
        assertEquals(2, requests)
    }
}
