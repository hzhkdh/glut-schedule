# 安卓与微信端课程名称计数实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将安卓端和微信端所有课表业务中的“课程门数”统一为按规范化课程名称去重计数。

**Architecture:** 两端各增加一个无状态纯函数，只负责规范化课程名称和去重计数。现有解析器、课程实体、缓存和空课表判断不变；学期概览、导入结果和课表刷新提示改为调用统一计数函数。

**Tech Stack:** Kotlin、JUnit 4、JavaScript、Node.js Test Runner、Gradle

## Global Constraints

- 唯一判定标准是规范化后的课程名称。
- 规范化只去除首尾空白并把连续空白压缩为一个空格。
- 教师、教室、校区、星期、节次、周次和课程 ID 不参与计数。
- 空名称不计数。
- 不合并底层课程实体。
- 不改变课表是否为空的业务判断。
- 安卓使用 JDK 17。
- 核心统计函数添加清晰中文注释。

---

### Task 1: 安卓端统一课程名称计数

**Files:**
- Modify: `app/src/main/java/com/glut/schedule/data/model/ScheduleModels.kt`
- Modify: `app/src/main/java/com/glut/schedule/ui/pages/SemesterOverviewViewModel.kt`
- Modify: `app/src/main/java/com/glut/schedule/ui/pages/DirectLoginViewModel.kt`
- Modify: `app/src/main/java/com/glut/schedule/ui/pages/ScheduleViewModel.kt`
- Test: `app/src/test/java/com/glut/schedule/CourseTitleCountTest.kt`
- Test: `app/src/test/java/com/glut/schedule/MultiSemesterUiContractTest.kt`

**Interfaces:**
- Produces: `fun Iterable<ScheduleCourse>.countDistinctCourseTitles(): Int`
- Consumes: 课表导入或 Repository 返回的 `Iterable<ScheduleCourse>`

- [ ] **Step 1: 写失败的纯函数测试**

新增 `CourseTitleCountTest`，构造同名异教室、同名异教师、空白差异、不同名称和空名称课程，期望：

```kotlin
assertEquals(2, courses.countDistinctCourseTitles())
```

- [ ] **Step 2: 运行测试确认函数尚不存在**

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.glut.schedule.CourseTitleCountTest" --no-daemon
```

预期：编译失败，提示 `countDistinctCourseTitles` 未解析。

- [ ] **Step 3: 实现最小纯函数**

在课表领域模型文件中增加扩展函数：

```kotlin
fun Iterable<ScheduleCourse>.countDistinctCourseTitles(): Int =
    map { it.title.trim().replace(Regex("""\s+"""), " ") }
        .filter { it.isNotEmpty() }
        .toSet()
        .size
```

- [ ] **Step 4: 将三个用户可见计数入口改为调用纯函数**

- `SemesterOverviewViewModel`：`base.courses.countDistinctCourseTitles()`
- `DirectLoginViewModel`：`currentPayload.courses.countDistinctCourseTitles()`
- `ScheduleViewModel`：刷新前后课程数量均调用该函数

保持 `courses.isNotEmpty()`、响应分类和 Repository 存储逻辑不变。

- [ ] **Step 5: 运行安卓相关测试**

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.glut.schedule.CourseTitleCountTest" --tests "com.glut.schedule.MultiSemesterUiContractTest" --tests "com.glut.schedule.AcademicSemesterImportServiceTest" --no-daemon
```

### Task 2: 微信端统一课程名称计数

**Files:**
- Create: `utils/courseCount.js`
- Modify: `utils/semesterOverview.js`
- Modify: `pages/import/import.js`
- Create: `tests/course-count.test.js`
- Modify: `tests/semester-overview.test.js`
- Modify: `tests/import-semester-ui.test.js`

**Interfaces:**
- Produces: `countDistinctCourseTitles(courses): number`
- Consumes: 小程序课表课程数组

- [ ] **Step 1: 写失败的纯函数测试**

新增测试，输入同名异教室、同名异教师、空白差异、不同名称和空名称，期望唯一课程名称数为 2。

- [ ] **Step 2: 运行测试确认模块尚不存在**

```powershell
node --test tests/course-count.test.js
```

预期：失败并提示找不到 `utils/courseCount`。

- [ ] **Step 3: 实现纯函数**

```javascript
function normalizeCourseTitle(title) {
  return String(title || '').trim().replace(/\s+/g, ' ')
}

function countDistinctCourseTitles(courses) {
  return new Set((courses || []).map(item => normalizeCourseTitle(item && item.title)).filter(Boolean)).size
}
```

- [ ] **Step 4: 接入学期概览和导入结果**

- `buildHistoricalOverview` 使用 `countDistinctCourseTitles(courses)`。
- 首次导入、最新学期导入、历史学期下载的 `importedCount` 和“成功导入 X 门课程”使用统一计数。
- 空课表判断和缓存状态继续使用 `courses.length`。

- [ ] **Step 5: 运行微信端相关测试**

```powershell
node --test tests/course-count.test.js tests/semester-overview.test.js tests/import-semester-ui.test.js
```

### Task 3: 跨端完整验证

**Files:**
- Verify all files from Task 1 and Task 2.

**Interfaces:**
- Consumes: 两端统一计数函数及调用入口。
- Produces: 两端一致的课程门数语义。

- [ ] **Step 1: 运行安卓完整单元测试**

```powershell
.\gradlew.bat testDebugUnitTest --no-daemon
```

- [ ] **Step 2: 构建安卓 Debug APK**

```powershell
.\gradlew.bat assembleDebug --no-daemon
```

- [ ] **Step 3: 运行微信端完整测试**

```powershell
node --test
```

- [ ] **Step 4: 检查两仓库差异**

分别运行 `git diff --check` 和 `git status --short`，确认只新增课程名称计数相关修改，保留其他现有未提交内容。
