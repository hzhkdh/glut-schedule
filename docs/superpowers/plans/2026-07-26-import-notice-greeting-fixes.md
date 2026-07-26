# Import, Notice, and Greeting Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复桂林首次导入考试为零、覆盖升级后通知空白、导入页冗余安全提示和侧边栏长问候语省略问题。

**Architecture:** 首次导入通过 `AcademicExamService` 与考试页共享考试接口选择；通知使用独立刷新控制器和显式页面状态；侧边栏头部拆成品牌行与双行问候语。网络安全配置继续只对白名单教务域名放行 HTTP。

**Tech Stack:** Kotlin、Jetpack Compose、Coroutines、DataStore、JUnit 4、Android Gradle Plugin

## Global Constraints

- 所有新增或修改的关键逻辑必须有清晰、准确的中文注释。
- 所有修改直接在 `main` 分支进行。
- 不扩大 HTTP 白名单，不记录 Cookie、账号或密码。
- 使用 JDK 17；交付前运行完整单元测试和 Debug 构建。

---

### Task 1: 统一首次导入考试链路

**Files:**
- Modify: `app/src/main/java/com/glut/schedule/ui/pages/DirectLoginViewModel.kt`
- Modify: `app/src/main/java/com/glut/schedule/MainActivity.kt`
- Test: `app/src/test/java/com/glut/schedule/DirectLoginSafetyTest.kt`
- Test: `app/src/test/java/com/glut/schedule/AcademicExamServiceTest.kt`

**Interfaces:**
- Consumes: `AcademicExamService.fetchExamData(cookie, storedExamApiUrl, baseUrl): Result<List<ExamInfo>>`
- Produces: 首次导入仅在非空结果成功时调用 `ScheduleRepository.replaceExams`

- [x] **Step 1: 写入失败测试**

增加契约测试，要求 `DirectLoginViewModel` 依赖 `AcademicExamService`，且首次导入代码调用 `fetchExamData`，不再直接调用 `findExamJsonResult`/`findExamHtmlResult`。

- [x] **Step 2: 验证测试因旧实现失败**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.glut.schedule.DirectLoginSafetyTest"`

Expected: FAIL，指出首次导入仍直接解析通用探测结果。

- [x] **Step 3: 实现最小修复**

为 `DirectLoginViewModel` 和工厂注入 `AcademicExamService`。首次导入使用：

```kotlin
academicExamService.fetchExamData(
    cookie = cookie,
    storedExamApiUrl = sessionStore.examApiUrl.first(),
    baseUrl = campusBaseUrl
).onSuccess { exams ->
    scheduleRepository.replaceExams(exams)
    examCount = exams.size
    sessionStore.saveExamApiUrl(academicExamService.lastSuccessfulExamUrl)
}.onFailure {
    failedModules += "考试"
}
```

保持南宁和桂林共用该逻辑，不用空列表覆盖缓存。

- [x] **Step 4: 验证相关测试通过**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.glut.schedule.DirectLoginSafetyTest" --tests "com.glut.schedule.AcademicExamServiceTest"`

Expected: PASS。

### Task 2: 移除可见 HTTP 提示并保留安全边界

**Files:**
- Modify: `app/src/main/java/com/glut/schedule/ui/pages/DirectLoginScreen.kt`
- Test: `app/src/test/java/com/glut/schedule/AcademicSecurityPolicyTest.kt`
- Test: `app/src/test/java/com/glut/schedule/DirectLoginSafetyTest.kt`

**Interfaces:**
- Consumes: `AcademicUrlPolicy` 和 `network_security_config.xml`
- Produces: 无冗余提示的导入页

- [x] **Step 1: 写入失败测试**

断言 `DirectLoginScreen.kt` 不包含“安全提示”文案，同时已有域名白名单测试继续保留。

- [x] **Step 2: 验证测试失败**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.glut.schedule.DirectLoginSafetyTest"`

Expected: FAIL，指出安全提示仍存在。

- [x] **Step 3: 删除提示及其专用间距**

移除提示 `Text`，把说明文字到账号输入框之间保留为统一的 20dp 间距，不修改底层 HTTP 白名单。

- [x] **Step 4: 验证安全与页面契约**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.glut.schedule.DirectLoginSafetyTest" --tests "com.glut.schedule.AcademicSecurityPolicyTest"`

Expected: PASS。

### Task 3: 通知加载状态与覆盖升级恢复

**Files:**
- Create: `app/src/main/java/com/glut/schedule/ui/NoticeLoadState.kt`
- Modify: `app/src/main/java/com/glut/schedule/MainActivity.kt`
- Modify: `app/src/main/java/com/glut/schedule/ui/pages/NoticeScreen.kt`
- Test: `app/src/test/java/com/glut/schedule/NoticeLoadStateTest.kt`

**Interfaces:**
- Produces: `sealed interface NoticeLoadState`，包含 `Loading`、`Content`、`Empty`、`Error`
- Produces: `fun resolveNoticeLoadState(cached, refreshed, refreshFinished, refreshFailed): NoticeLoadState`
- Consumes: `NoticeScreen(state, onRetry)`

- [x] **Step 1: 写入状态推导失败测试**

覆盖有效旧缓存、无缓存加载中、刷新成功为空、无缓存刷新失败四种情况，并验证刷新失败时不会丢弃有效缓存。

- [x] **Step 2: 验证测试因类型不存在而失败**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.glut.schedule.NoticeLoadStateTest"`

Expected: FAIL，指出 `NoticeLoadState` 不存在。

- [x] **Step 3: 实现状态推导与独立刷新**

新增纯 Kotlin 状态推导函数。`MainActivity` 使用单独的通知刷新触发计数启动请求，版本检查和通知检查放入两个 `LaunchedEffect`；网络成功写缓存，失败保留缓存并进入错误态。

- [x] **Step 4: 增加页面重试入口**

`NoticeScreen` 根据状态显示加载提示、通知列表、真正空状态或“通知加载失败”与“重新加载”按钮。点击按钮仅重新请求通知。

- [x] **Step 5: 验证通知测试**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.glut.schedule.NoticeLoadStateTest" --tests "com.glut.schedule.NoticeParserTest"`

Expected: PASS。

### Task 4: 双行问候语头部

**Files:**
- Modify: `app/src/main/java/com/glut/schedule/MainActivity.kt`
- Test: `app/src/test/java/com/glut/schedule/DrawerGreetingIntegrationContractTest.kt`

**Interfaces:**
- Consumes: `TypewriterGreetingText(fullText, animate, animationRunId)`
- Produces: 品牌行下方固定 40dp、最多两行的问候语区域

- [x] **Step 1: 写入失败契约测试**

要求 `TypewriterGreetingText` 使用 `maxLines = 2` 和 `height(40.dp)`，并要求 `DrawerHeader` 在品牌行结束后再调用问候语组件。

- [x] **Step 2: 验证测试失败**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.glut.schedule.DrawerGreetingIntegrationContractTest"`

Expected: FAIL，指出当前仍是单行 20dp。

- [x] **Step 3: 实现双层头部**

品牌行保留 Logo 与标题；在其后增加 8dp 间距并使用全宽问候语。文字使用 13sp、20sp 行高、最多两行和固定 40dp 高度。

- [x] **Step 4: 验证问候语测试**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.glut.schedule.DrawerGreetingIntegrationContractTest"`

Expected: PASS。

### Task 5: 文档与完整验证

**Files:**
- Modify: `docs/code-review-fixes-2026-07-26.md`
- Modify: `docs/superpowers/plans/2026-07-26-import-notice-greeting-fixes.md`

**Interfaces:**
- Produces: 可追溯的原因、修复与验证记录

- [x] **Step 1: 更新修复记录**

记录桂林/南宁差异、通知覆盖安装触发条件、HTTP 白名单取舍、问候语布局和测试命令。

- [x] **Step 2: 运行完整单元测试**

Run: `.\gradlew.bat testDebugUnitTest`

Expected: BUILD SUCCESSFUL，零失败。

- [x] **Step 3: 构建 Debug APK**

Run: `.\gradlew.bat assembleDebug`

Expected: BUILD SUCCESSFUL，并生成 `app/build/outputs/apk/debug/app-debug.apk`。

- [x] **Step 4: ADB 真机验证**

确认设备重新连接后覆盖安装 APK，验证桂林首次导入考试、南宁首次导入考试、通知失败重试、长问候语双行显示和导入页提示移除。
