package com.glut.schedule

import com.glut.schedule.service.greeting.GreetingCategory
import com.glut.schedule.service.greeting.GreetingTemplateParser
import com.glut.schedule.service.greeting.builtInGreetingTemplates
import com.glut.schedule.service.academic.resolveAuthenticatedStudent
import com.glut.schedule.service.parser.AcademicSemesterParser
import com.glut.schedule.data.settings.greetingEnabledFromStored
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DrawerGreetingDataTest {

    @Test
    fun studentNamePrefersKnownInputFieldAndDecodesHtml() {
        val html = """
            <input name="studentName" value=" 张&amp;三 " />
            <input name="xm" value="备用姓名" />
        """.trimIndent()

        assertEquals("张&三", AcademicSemesterParser.parseStudentName(html))
    }

    @Test
    fun studentNameFallsBackToVisibleNameLabel() {
        val html = """
            <table>
              <tr><th>姓名</th><td>李四</td></tr>
            </table>
        """.trimIndent()

        assertEquals("李四", AcademicSemesterParser.parseStudentName(html))
        assertNull(AcademicSemesterParser.parseStudentName("<html>登录失效</html>"))
    }

    @Test
    fun remoteTemplateParserKeepsOnlyKnownPlaceholdersAndCategories() {
        val json = """
            {
              "schemaVersion": 1,
              "contentVersion": 7,
              "updatedAt": "2026-07-26T12:00:00+08:00",
              "templates": {
                "greeting": ["Hi～{name}，{period}好", "坏模板 {unknown}", ""],
                "examTomorrow": ["{course}明天登场，准备接招"],
                "notSupported": ["不应进入结果"]
              }
            }
        """.trimIndent()

        val document = GreetingTemplateParser.parse(json)

        assertEquals(7, document?.contentVersion)
        assertEquals(
            listOf("Hi～{name}，{period}好"),
            document?.templates?.forCategory(GreetingCategory.GREETING)
        )
        assertEquals(
            listOf("{course}明天登场，准备接招"),
            document?.templates?.forCategory(GreetingCategory.EXAM_TOMORROW)
        )
        assertTrue(document?.templates?.forCategory(GreetingCategory.EXAM_TODAY).isNullOrEmpty())
    }

    @Test
    fun categoryOverlayFallsBackOnlyForMissingRemoteCategory() {
        val builtIn = builtInGreetingTemplates()
        val remote = GreetingTemplateParser.parse(
            """
                {
                  "schemaVersion": 1,
                  "contentVersion": 1,
                  "templates": {
                    "greeting": ["远程问候 {name}"],
                    "examToday": []
                  }
                }
            """.trimIndent()
        )!!.templates

        val resolved = builtIn.overlay(remote)

        assertEquals(listOf("远程问候 {name}"), resolved.forCategory(GreetingCategory.GREETING))
        assertEquals(
            builtIn.forCategory(GreetingCategory.EXAM_TODAY),
            resolved.forCategory(GreetingCategory.EXAM_TODAY)
        )
    }

    @Test
    fun parserRejectsUnsupportedSchemaAndOversizedTemplates() {
        val unsupported = """{"schemaVersion":2,"contentVersion":1,"templates":{"greeting":["你好"]}}"""
        val oversized = "a".repeat(61)
        val partlyValid = """
            {
              "schemaVersion": 1,
              "contentVersion": 1,
              "templates": {"greeting": ["你好 {name}", "$oversized"]}
            }
        """.trimIndent()

        assertNull(GreetingTemplateParser.parse(unsupported))
        val templates = GreetingTemplateParser.parse(partlyValid)!!.templates
            .forCategory(GreetingCategory.GREETING)
        assertEquals(listOf("你好 {name}"), templates)
        assertFalse(templates.contains(oversized))
    }

    @Test
    fun authenticatedStudentNeverCarriesNameAcrossAccounts() {
        val switchedWithoutName = resolveAuthenticatedStudent(
            existingStudentNumber = "2024001",
            existingStudentName = "张三",
            newStudentNumber = "2025002",
            parsedStudentName = null
        )
        val sameAccountParseFailure = resolveAuthenticatedStudent(
            existingStudentNumber = "2024001",
            existingStudentName = "张三",
            newStudentNumber = "2024001",
            parsedStudentName = null
        )

        assertEquals("2025002", switchedWithoutName.studentNumber)
        assertEquals("", switchedWithoutName.studentName)
        assertEquals("张三", sameAccountParseFailure.studentName)
    }

    @Test
    fun greetingSettingDefaultsToEnabledButRespectsExplicitOff() {
        assertTrue(greetingEnabledFromStored(null))
        assertFalse(greetingEnabledFromStored(false))
    }

    @Test
    fun placeholderRegexEscapesClosingBraceForAndroidIcu() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "app").isDirectory }
        val source = File(
            root,
            "app/src/main/java/com/glut/schedule/service/greeting/GreetingTemplates.kt"
        ).readText()

        assertTrue(source.contains("""\{([A-Za-z]+)\}"""))
    }
}
