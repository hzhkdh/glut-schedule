package com.glut.schedule.service.refresh

import com.glut.schedule.data.model.*
import com.glut.schedule.service.academic.looksLikeAcademicLoginPage
import com.glut.schedule.service.academic.scorePageUnavailableReason
import com.glut.schedule.service.network.MAX_HTML_RESPONSE_BYTES
import com.glut.schedule.service.network.readBytesLimited
import com.glut.schedule.service.network.awaitResponse
import com.glut.schedule.service.parser.GradeExamParser
import com.glut.schedule.service.parser.ScoreParser
import com.glut.schedule.service.parser.StudyPlanParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

internal class AcademicDataUnavailableException(message: String) : IOException(message)

/** 与手动页面使用同一门户协议及解析器；只取数，不触发界面、续期或写库。 */
object AcademicRefreshQueries {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    internal fun validateAcademicResponse(code: Int, html: String) {
        // 403 也可能是权限或网关拦截，只有明确认证失败才触发续期。
        if (code == 401 || looksLikeAcademicLoginPage(html)) {
            throw ScheduledRefreshLoginRequiredException()
        }
        if (code !in 200..299) throw IOException("教务服务暂不可用")
        // 评教限制与错误页不能伪装成合法空数据而清空缓存。
        scorePageUnavailableReason(html)?.let { throw AcademicDataUnavailableException(it) }
    }

    private suspend fun html(cookie: String, base: String, path: String,
                             post: FormBody? = null, fallback: String = "UTF-8"): String = withContext(Dispatchers.IO) {
        val requestContext = currentCoroutineContext()
        requestContext.ensureActive()
        val request = Request.Builder().url(base.trimEnd('/') + path)
            .header("Cookie", cookie).header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            .apply { if (post != null) post(post) }.build()
        client.newCall(request).awaitResponse { response ->
            val bytes = response.body?.readBytesLimited(MAX_HTML_RESPONSE_BYTES) ?: throw IOException("教务返回空响应")
            val declared = Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE)
                .find(response.header("Content-Type").orEmpty())?.groupValues?.get(1)?.trim('"', '\'')
            val charset = runCatching { Charset.forName(declared ?: fallback) }.getOrElse { Charset.forName(fallback) }
            val text = String(bytes, charset)
            requestContext.ensureActive()
            validateAcademicResponse(response.code, text)
            text
        }
    }

    suspend fun scores(cookie: String, base: String): List<ScoreInfo> {
        val form = FormBody.Builder().add("year", "").add("term", "").add("prop", "")
            .add("groupName", "").add("para", "0").add("sortColumn", "").add("Submit", "查询").build()
        val text = html(cookie, base, "/academic/manager/score/studentOwnScore.do", form, "GBK")
        return ScoreParser().parseScoreHtml(text).also {
            // 手动页对空成绩同样不覆盖已有记录；后台沿用这一保守语义。
            if (it.isEmpty()) throw AcademicDataUnavailableException("未获得可用成绩，保留已有缓存")
        }
    }

    suspend fun gradeExams(cookie: String, base: String): List<GradeExamInfo> {
        val text = html(cookie, base, "/academic/student/skilltest/skilltest.jsdo?moduleId=2090", fallback = "GBK")
        validateGradeExamMarkup(text)
        return GradeExamParser().parse(text)
    }

    internal fun validateGradeExamMarkup(text: String) {
        if (!text.contains("infolist_tab", true) || !text.contains("我参加的考试")) {
            throw IOException("无法识别考级成绩页面，保留已有缓存")
        }
    }

    suspend fun studyPlan(cookie: String, base: String): Pair<List<StudyPlanGroup>, List<StudyPlanCourse>> {
        val parser = StudyPlanParser()
        val self = html(cookie, base, "/academic/manager/studyschedule/studentSelfSchedule.jsdo", fallback = "GBK")
        val (student, clazz) = parser.parseStudentIds(self) ?: throw IOException("无法识别教学计划入口")
        val line = html(cookie, base,
            "/academic/manager/studyschedule/studentScheduleLineShow.do?z=z&studentId=${encode(student)}&classId=${encode(clazz)}")
        val (groups, courses) = parser.parseData(line)
        if (groups.isEmpty() && courses.isEmpty()) throw IOException("未获得可用教学计划，保留已有缓存")
        val mergedGroups = groups.toMutableList()
        val mergedCourses = courses.toMutableList()
        // 任选课组不在平铺表中，必须继续走框架入口，避免后台更新丢失选修课程。
        parser.parseFrameStudentId(self)?.let { frameStudent ->
            val frame = html(cookie, base,
                "/academic/manager/studyschedule/studentScheduleShowFrame.do?z=z&studentId=${encode(frameStudent)}&classId=${encode(clazz)}")
            for ((id, _) in parser.extractFreeGroupIds(frame)) {
                val detail = html(cookie, base,
                    "/academic/manager/studyschedule/scheduleFreeGroupCourseList.do?pojoTypeId=2&id=${encode(id)}")
                val (group, members) = parser.parseFreeGroupDetail(detail)
                if (group != null) {
                    val index = mergedGroups.indexOfFirst { it.groupName == group.groupName }
                    if (index >= 0) mergedGroups[index] = group else mergedGroups.add(group)
                    mergedCourses.addAll(members)
                }
            }
        }
        return mergedGroups.distinctBy { it.id } to mergedCourses.distinctBy { it.id }
    }
}
