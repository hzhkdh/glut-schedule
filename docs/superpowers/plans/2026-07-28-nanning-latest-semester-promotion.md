# 南宁最新学期晋升实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让安卓端南宁校区在下一学期存在真实课程时，与桂林校区一样晋升并只完整导入最新学期。

**Architecture:** 保留现有校区无关的 `AcademicSemesterProbePlanner` 和首次导入编排，只修正 `AcademicSemesterParser` 的学期值识别：稳定门户值优先、中文标签兜底。通过目录解析测试和南宁非空课表晋升测试锁定端到端决策。

**Tech Stack:** Kotlin、JUnit 4、Gradle、OkHttp MockWebServer

## Global Constraints

- 桂林春秋值保持 `1/2`，南宁春秋值使用 `1/3`。
- 只探测门户当前学期的紧邻下一学期。
- 只有真实非空个人课表可以触发晋升。
- 探测失败、空课表、登录失效或学期不匹配时保留门户当前学期。
- 不修改历史学期下载、补充数据流程或 Room 表结构。
- 使用 JDK 17 运行测试与构建。
- 核心学期识别逻辑添加清晰中文注释。

---

### Task 1: 用稳定门户值识别南宁春秋学期

**Files:**
- Modify: `app/src/main/java/com/glut/schedule/service/parser/AcademicSemesterParser.kt`
- Test: `app/src/test/java/com/glut/schedule/AcademicSemesterCatalogTest.kt`

**Interfaces:**
- Consumes: `AcademicSemesterParser.parseCatalogPlan(html, campus, enrollmentDate, today)`
- Produces: 保留 `selected` 状态的 `PortalTerm`，南宁值 `1/3` 能解析为春/秋。

- [ ] **Step 1: 写入中文标签乱码的失败测试**

在 `AcademicSemesterCatalogTest` 增加：

```kotlin
@Test
fun nanningUsesStableTermValuesWhenChineseLabelsAreUnreadable() {
    val html = """
        <select name="year">
          <option value="46" selected>2026</option>
          <option value="47">2027</option>
        </select>
        <select name="term">
          <option value="1" selected>��</option>
          <option value="3">��</option>
        </select>
    """.trimIndent()

    val plan = AcademicSemesterParser.parseCatalogPlan(
        html = html,
        campus = CampusType.NANNING,
        enrollmentDate = LocalDate.of(2024, 9, 1),
        today = LocalDate.of(2026, 8, 20)
    )

    assertEquals(SemesterSeason.SPRING, plan.semesters.single { it.isCurrent }.season)
    assertEquals(SemesterSeason.AUTUMN, plan.nextSemester?.season)
    assertEquals("3", plan.nextSemester?.portalTermId)
}
```

- [ ] **Step 2: 运行测试并确认因按月份误判而失败**

运行：

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.glut.schedule.AcademicSemesterCatalogTest.nanningUsesStableTermValuesWhenChineseLabelsAreUnreadable" --no-daemon
```

预期：当前学期被误判为秋季，断言失败。

- [ ] **Step 3: 实现门户值优先、中文标签兜底**

在 `AcademicSemesterParser` 增加校区感知的季节解析：

```kotlin
private fun seasonForTerm(campus: CampusType, value: String, text: String): SemesterSeason? {
    val normalizedValue = value.trim()
    return when {
        normalizedValue == "1" -> SemesterSeason.SPRING
        campus == CampusType.GUILIN && normalizedValue == "2" -> SemesterSeason.AUTUMN
        campus == CampusType.NANNING && normalizedValue == "3" -> SemesterSeason.AUTUMN
        text.contains("春") -> SemesterSeason.SPRING
        text.contains("秋") -> SemesterSeason.AUTUMN
        else -> null
    }
}
```

`parsedTerms` 调用该函数，并继续把原始 `selected` 状态写入 `PortalTerm`。

- [ ] **Step 4: 运行目录测试**

运行：

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.glut.schedule.AcademicSemesterCatalogTest" --no-daemon
```

预期：全部通过，桂林 `1/2` 和南宁 `1/3` 均无回归。

### Task 2: 锁定南宁真实非空课表的晋升决策

**Files:**
- Modify: `app/src/test/java/com/glut/schedule/AcademicSemesterProbePlannerTest.kt`
- Reuse: `app/src/main/java/com/glut/schedule/service/academic/AcademicSemesterProbePlanner.kt`

**Interfaces:**
- Consumes: `AcademicSemesterProbePlanner.decide(plan, probeResult)`
- Produces: 南宁下一秋季非空则成为唯一当前学期；空响应仍保留春季。

- [ ] **Step 1: 增加南宁 `term=3` 非空晋升回归测试**

构造 `CampusType.NANNING` 的 2026 春目录和 2026 秋候选，候选 `portalTermId` 必须为 `3`；传入包含一门南宁课程的 `VALID_NON_EMPTY_SCHEDULE` payload，断言：

```kotlin
assertEquals("nanning:2026:autumn", decision.currentSemester.id)
assertEquals("3", decision.currentSemester.portalTermId)
assertEquals(1, decision.catalog.count { it.isCurrent })
assertSame(payload, decision.promotedPayload)
```

- [ ] **Step 2: 先临时要求错误结果并验证测试能捕获回归**

将期望当前学期暂设为春季运行单测，确认实际结果为秋季而失败；随后恢复正确期望。该步骤证明测试确实覆盖晋升决策，而不是仅验证夹具。

- [ ] **Step 3: 运行规划器与南宁解析器测试**

运行：

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.glut.schedule.AcademicSemesterProbePlannerTest" --tests "com.glut.schedule.NanningCurrcourseParserTest" --no-daemon
```

预期：全部通过。

### Task 3: 完整回归与构建验证

**Files:**
- Verify: `app/src/main/java/com/glut/schedule/service/parser/AcademicSemesterParser.kt`
- Verify: `app/src/test/java/com/glut/schedule/AcademicSemesterCatalogTest.kt`
- Verify: `app/src/test/java/com/glut/schedule/AcademicSemesterProbePlannerTest.kt`

**Interfaces:**
- Consumes: Task 1 和 Task 2 的代码与测试。
- Produces: 可编译且完整单元测试通过的安卓代码。

- [ ] **Step 1: 运行学期导入相关测试**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.glut.schedule.AcademicSemesterCatalogTest" --tests "com.glut.schedule.AcademicSemesterProbePlannerTest" --tests "com.glut.schedule.AcademicSemesterImportServiceTest" --tests "com.glut.schedule.NanningCurrcourseParserTest" --no-daemon
```

- [ ] **Step 2: 运行完整单元测试**

```powershell
.\gradlew.bat testDebugUnitTest --no-daemon
```

- [ ] **Step 3: 构建 Debug APK**

```powershell
.\gradlew.bat assembleDebug --no-daemon
```

- [ ] **Step 4: 检查差异边界**

```powershell
git diff --check -- app/src/main/java/com/glut/schedule/service/parser/AcademicSemesterParser.kt app/src/test/java/com/glut/schedule/AcademicSemesterCatalogTest.kt app/src/test/java/com/glut/schedule/AcademicSemesterProbePlannerTest.kt
git diff --stat
```

确认未覆盖当前工作区中的校历、问候语及版本号修改。
