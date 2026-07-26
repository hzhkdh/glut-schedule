# 侧边栏问候语 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Android 侧边栏加入可关闭、可远程维护且离线可用的动态问候语，并发布 0.21.0。

**Architecture:** 纯 Kotlin 规划器负责上下文选择和模板渲染；远程仓库只提供受限模板字符串。DataStore 保存姓名、开关和最后有效远程缓存，Compose 只消费完整的问候状态。

**Tech Stack:** Kotlin、Jetpack Compose、DataStore、OkHttp、org.json、JUnit 4、ADB。

## Global Constraints

- 直接在用户明确授权的 `main` 分支工作。
- 核心逻辑和复杂流程使用清晰的中文注释。
- 先写失败测试，再写最小实现。
- Android 版本固定为 `versionCode 121`、`versionName 0.21.0`。
- 本期不实现天气、定位、AI 文案、今日课程或微信端代码。

---

### Task 1: 远程模板与姓名

**Files:**
- Create: `app/src/main/java/com/glut/schedule/service/greeting/GreetingTemplates.kt`
- Modify: `app/src/main/java/com/glut/schedule/service/parser/AcademicSemesterParser.kt`
- Modify: `app/src/main/java/com/glut/schedule/service/academic/AcademicSessionStore.kt`
- Test: `app/src/test/java/com/glut/schedule/DrawerGreetingDataTest.kt`

**Interfaces:**
- Produces: `GreetingTemplateSet`、`parseGreetingTemplates(json)`、`parseStudentName(html)`。

- [ ] 写姓名、合法模板、非法占位符和分类回退测试并确认失败。
- [ ] 实现最小解析与校验逻辑，确认目标测试通过。
- [ ] 将姓名与学号原子保存并覆盖账号切换测试。

### Task 2: 缓存与问候规划

**Files:**
- Create: `app/src/main/java/com/glut/schedule/service/greeting/GreetingTemplateRepository.kt`
- Create: `app/src/main/java/com/glut/schedule/service/greeting/DrawerGreetingPlanner.kt`
- Modify: `app/src/main/java/com/glut/schedule/data/settings/ScheduleSettingsStore.kt`
- Test: `app/src/test/java/com/glut/schedule/DrawerGreetingPlannerTest.kt`

**Interfaces:**
- Consumes: `GreetingTemplateSet`、姓名、考试、当前学期日期。
- Produces: `DrawerGreeting` 与 `GreetingTemplateRepository.refreshIfDue(now)`。

- [ ] 写今明考试独占、2～7 天窗口、分类随机、连续去重和 15 秒冷却测试并确认失败。
- [ ] 实现纯规划器和模板渲染，确认测试通过。
- [ ] 写 24 小时刷新、1 小时失败退避和最后有效缓存测试，再实现仓库。

### Task 3: Android 集成

**Files:**
- Modify: `app/src/main/java/com/glut/schedule/ScheduleApplication.kt`
- Modify: `app/src/main/java/com/glut/schedule/ui/pages/DirectLoginViewModel.kt`
- Modify: `app/src/main/java/com/glut/schedule/MainActivity.kt`

**Interfaces:**
- Consumes: AppContainer 中的模板仓库、Repository exams 和当前学期设置。
- Produces: 设置页“问候语”开关和稳定单行的侧边栏动态副标题。

- [ ] 接入教务姓名保存及应用启动异步刷新。
- [ ] 监听侧边栏 Closed→Open 转换并生成新问候语。
- [ ] 实现打字动画、无障碍完整语义、关闭动画回退和固定行高。
- [ ] 接入默认开启的“问候语”总开关与重置逻辑。

### Task 4: 远程文件、文档和发布

**Files:**
- Create: `../app-update-host/greetings.json`
- Create: `../app-update-host/greetingsJsonFormat.md`
- Modify: `app/build.gradle.kts`
- Modify: `docs/开发文档.md`
- Modify: `docs/功能更新文档.md`

- [ ] 增加混合语气的合法初始模板与微信端同步契约。
- [ ] 将版本升级为 121 / 0.21.0，并同步版本测试与发布说明。
- [ ] 运行 `.\gradlew.bat testDebugUnitTest` 和 `.\gradlew.bat assembleDebug`。
- [ ] 使用 ADB 在真机验证开关、随机、快速重开、离线缓存和布局稳定性。
- [ ] 构建签名 Release，提交并推送两个仓库，创建 GitHub Release，更新并验证 Cloudflare 文件。
