package com.glut.schedule

import com.glut.schedule.data.model.ProfessionalScoreCalculator
import com.glut.schedule.data.model.ScoreInfo
import com.glut.schedule.data.model.StudyPlanGroupWithCourses
import com.glut.schedule.service.academic.AcademicLoginHttpClient
import com.glut.schedule.service.academic.AcademicLoginResult
import com.glut.schedule.service.academic.AcademicOALoginClient
import com.glut.schedule.service.parser.ScoreParser
import com.glut.schedule.service.parser.StudyPlanParser
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

class ProfessionalScoreRemoteSmokeTest {
    private val scoreParser = ScoreParser()
    private val studyPlanParser = StudyPlanParser()

    @Test
    fun logsInFetchesRealDataAndCalculatesProfessionalScore() = runBlocking {
        val username = System.getenv("GLUT_REMOTE_USERNAME").orEmpty()
        val password = System.getenv("GLUT_REMOTE_PASSWORD").orEmpty()
        assumeTrue(
            "Set GLUT_REMOTE_USERNAME and GLUT_REMOTE_PASSWORD to run the real remote smoke test.",
            username.isNotBlank() && password.isNotBlank()
        )

        val loginResult = login(username, password)
        assertTrue(
            "Expected remote academic login to succeed, got " + loginResult.safeName(),
            loginResult is AcademicLoginResult.Success
        )
        val success = loginResult as AcademicLoginResult.Success

        val groupsWithCourses = fetchStudyPlan(success.cookie, success.campusBaseUrl)
        val groupCount = groupsWithCourses.size
        val planCourseCount = groupsWithCourses.sumOf { it.courses.size }
        val semesters = ProfessionalScoreCalculator.availableSemesters(groupsWithCourses)
        println(
            "studyPlanFetch(groups=" + groupCount +
                ", courses=" + planCourseCount +
                ", selectableSemesters=" + semesters.size + ")"
        )
        assertTrue("Expected at least one parsed study-plan group.", groupCount > 0)
        assertTrue("Expected at least one parsed study-plan course.", planCourseCount > 0)
        assertTrue("Expected at least one selectable semester from required/limited study-plan courses.", semesters.isNotEmpty())

        val scoreFetch = fetchScores(success.cookie, success.campusBaseUrl)
        println(scoreFetch.summary())
        if (scoreFetch.blockReason != null) {
            throw AssertionError(
                "Remote score page is blocked by academic system after study plan was fetched: " +
                    scoreFetch.blockReason + "; groups=" + groupCount +
                    ", courses=" + planCourseCount +
                    ", selectableSemesters=" + semesters.size
            )
        }
        val scores = scoreFetch.scores
        assertTrue(
            "Expected at least one parsed score from the remote score page. " + scoreFetch.summary(),
            scores.isNotEmpty()
        )

        val calculated = semesters.asReversed()
            .map { semester ->
                ProfessionalScoreCalculator.calculate(
                    semester = semester,
                    groupsWithCourses = groupsWithCourses,
                    scores = scores
                )
            }
            .firstOrNull { it.professionalScore != null }

        assertNotNull("Expected at least one semester with matched scores and credits.", calculated)
        val result = calculated!!

        println(
            "ProfessionalScoreRemoteSmokeTest summary: " +
                "scoreCount=" + scores.size +
                ", studyPlanGroups=" + groupCount +
                ", studyPlanCourses=" + planCourseCount +
                ", selectedSemester=" + result.semester +
                ", includedCourses=" + result.courses.size +
                ", missingCourses=" + result.missingCourses.size +
                ", totalCredit=" + format(result.totalCredit) +
                ", professionalScore=" + format(result.professionalScore ?: 0.0)
        )
    }

    private suspend fun login(username: String, password: String): AcademicLoginResult {
        val direct = AcademicLoginHttpClient().login(username, password)
        if (direct is AcademicLoginResult.Success || direct is AcademicLoginResult.InvalidCredentials) return direct

        val oa = AcademicOALoginClient().login(username, password)
        return if (oa is AcademicLoginResult.Success) oa else direct
    }

    private data class ScoreFetchResult(
        val scores: List<ScoreInfo>,
        val httpCode: Int,
        val bodyLength: Int,
        val contentType: String,
        val containsDatalist: Boolean,
        val looksLikeLoginPage: Boolean,
        val blockReason: String?,
        val preview: String
    ) {
        fun summary(): String {
            return "scoreFetch(httpCode=" + httpCode +
                ", bodyLength=" + bodyLength +
                ", contentType=" + contentType +
                ", containsDatalist=" + containsDatalist +
                ", looksLikeLoginPage=" + looksLikeLoginPage +
                ", blockReason=" + (blockReason ?: "") +
                ", parsedScores=" + scores.size +
                ", preview=" + preview.take(120) + ")"
        }
    }

    private fun fetchScores(cookie: String, campusBaseUrl: String): ScoreFetchResult {
        val client = httpClient()
        val formBody = FormBody.Builder()
            .add("year", "")
            .add("term", "")
            .add("prop", "")
            .add("groupName", "")
            .add("para", "0")
            .add("sortColumn", "")
            .add("Submit", "查询")
            .build()

        val request = Request.Builder()
            .url(campusBaseUrl + "/academic/manager/score/studentOwnScore.do")
            .header("Cookie", cookie)
            .header("User-Agent", USER_AGENT)
            .post(formBody)
            .build()

        val (bytes, code, contentType) = client.newCall(request).execute().use { response ->
            Triple(
                response.body?.bytes() ?: ByteArray(0),
                response.code,
                response.header("Content-Type").orEmpty()
            )
        }
        val html = String(bytes, detectCharset(contentType))
        val isNanning = campusBaseUrl == AcademicLoginResult.NANNING_URL
        val attributeMap = if (isNanning) fetchCourseAttributeMap(cookie, campusBaseUrl, client) else emptyMap()
        val scores = scoreParser.parseScoreHtml(html, isNanning = isNanning, attributeMap = attributeMap)
        val compactPreview = html
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""<[^>]+>"""), "")
            .trim()
        return ScoreFetchResult(
            scores = scores,
            httpCode = code,
            bodyLength = bytes.size,
            contentType = contentType,
            containsDatalist = html.contains("datalist", ignoreCase = true),
            looksLikeLoginPage = looksLikeLoginPage(html),
            blockReason = scoreAccessBlockReason(html),
            preview = compactPreview
        )
    }

    private fun fetchStudyPlan(cookie: String, campusBaseUrl: String): List<StudyPlanGroupWithCourses> {
        val client = httpClient()
        val selfScheduleUrl = campusBaseUrl + "/academic/manager/studyschedule/studentSelfSchedule.jsdo"
        val selfHtml = client.newCall(
            Request.Builder()
                .url(selfScheduleUrl)
                .header("Cookie", cookie)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
        ).execute().use { response ->
            require(response.isSuccessful) { "Study-plan self page returned HTTP " + response.code }
            String(response.body?.bytes() ?: ByteArray(0), Charset.forName("GBK"))
        }

        val (studentId, classId) = requireNotNull(studyPlanParser.parseStudentIds(selfHtml)) {
            "Could not parse study-plan student/class ids from the remote page."
        }
        val lineShowUrl = campusBaseUrl +
            "/academic/manager/studyschedule/studentScheduleLineShow.do?z=z&studentId=" +
            studentId +
            "&classId=" +
            classId
        val (lineBytes, lineContentType) = client.newCall(
            Request.Builder()
                .url(lineShowUrl)
                .header("Cookie", cookie)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
        ).execute().use { response ->
            require(response.isSuccessful) { "Study-plan line page returned HTTP " + response.code }
            (response.body?.bytes() ?: ByteArray(0)) to response.header("Content-Type").orEmpty()
        }

        val (groups, courses) = studyPlanParser.parseData(
            String(lineBytes, detectCharset(lineContentType, defaultCharsetName = "UTF-8"))
        )
        return groups.map { group ->
            StudyPlanGroupWithCourses(
                group = group,
                courses = courses.filter { it.groupId == group.id }
            )
        }
    }

    private fun fetchCourseAttributeMap(
        cookie: String,
        campusBaseUrl: String,
        client: OkHttpClient
    ): Map<String, String> = runCatching {
        val selfHtml = client.newCall(
            Request.Builder()
                .url(campusBaseUrl + "/academic/manager/studyschedule/studentSelfSchedule.jsdo")
                .header("Cookie", cookie)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
        ).execute().use { response ->
            String(response.body?.bytes() ?: ByteArray(0), Charset.forName("GBK"))
        }
        val (studentId, classId) = studyPlanParser.parseStudentIds(selfHtml) ?: return emptyMap()
        val lineUrl = campusBaseUrl +
            "/academic/manager/studyschedule/studentScheduleLineShow.do?z=z&studentId=" +
            studentId +
            "&classId=" +
            classId
        val lineHtml = client.newCall(
            Request.Builder()
                .url(lineUrl)
                .header("Cookie", cookie)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
        ).execute().use { response ->
            String(response.body?.bytes() ?: ByteArray(0), Charsets.UTF_8)
        }
        studyPlanParser.parseCourseAttributeMap(lineHtml)
    }.getOrDefault(emptyMap())

    private fun scoreAccessBlockReason(html: String): String? {
        val compact = html.replace(Regex("""\s+"""), "")
        return when {
            compact.contains("没有参加评教") && compact.contains("不能查看成绩") ->
                "有课程未参加评教，暂时不能查看成绩"
            else -> null
        }
    }

    private fun looksLikeLoginPage(body: String): Boolean {
        val compact = body.replace(Regex("""\s+"""), "")
        return compact.contains("欢迎登录") ||
            compact.contains("请输入密码") ||
            compact.contains("账号登录") ||
            compact.contains("统一身份认证") ||
            compact.contains("验证码")
    }

    private fun detectCharset(
        contentType: String,
        defaultCharsetName: String = "GBK"
    ): Charset {
        val charsetName = if (contentType.contains("charset=", ignoreCase = true)) {
            contentType.substringAfter("charset=").trim().removePrefix("\"").removeSuffix("\"")
        } else {
            defaultCharsetName
        }
        return runCatching { Charset.forName(charsetName) }
            .getOrElse { Charset.forName(defaultCharsetName) }
    }

    private fun httpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun AcademicLoginResult.safeName(): String = when (this) {
        is AcademicLoginResult.Success -> "Success"
        AcademicLoginResult.MissingCredentials -> "MissingCredentials"
        AcademicLoginResult.InvalidCredentials -> "InvalidCredentials"
        AcademicLoginResult.CaptchaOrInteractiveLoginRequired -> "CaptchaOrInteractiveLoginRequired"
        is AcademicLoginResult.NetworkError -> "NetworkError"
    }

    private fun format(value: Double): String = String.format("%.2f", value).trimEnd('0').trimEnd('.')

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
    }
}
