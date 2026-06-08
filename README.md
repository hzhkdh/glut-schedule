# 桂林理工大学课表

面向桂林理工大学（含南宁分校）学生的本地 Android 课表 App。支持直接输入学号密码导入教务数据，无需手动操作 WebView。

## 功能

### 课表
- 启动即进入课表页面，无需登录注册
- 顶部显示当前周数、星期和日期，左右滑动切换周次（1-22 周）
- 左侧节次列显示节次与上下课时间，顶部显示周一至周日
- 彩色课程卡片展示课程名、教室、教师、周次
- 同一时段多门冲突课程自动分组，点击轮换显示
- 调课处的卡片消失，在补课的节次补上卡片
- 暗色星空渐变背景，支持自定义背景图片

### 数据导入
- 直接输入学号密码登录教务系统（桂林 / 南宁自动检测）
- 南宁分校支持验证码登录
- 登录后自动拉取：课表、考试安排、考试成绩、等级考试、教学计划
- 凭据加密存储（EncryptedSharedPreferences），静默登录刷新

### 菜单功能
| 菜单 | 功能 | 数据来源 |
|------|------|---------|
| 课程表 | 当前学期课表 | currcourse.jsdo / showTimetable.do |
| 考试成绩 | 历年课程成绩 + 绩点 | studentOwnScore.do |
| 考试安排 | 考试时间、地点、座位号 | 多端点探测 (JSON/HTML) |
| 等级考级 | 英语四六级、普通话等国家考试 | skilltest.jsdo (moduleId=2090) |
| 教学计划 | 课组学分/门数要求与完成情况 | studentSelfSchedule.jsdo → studentScheduleLineShow.do |
| 导入课表 | 学号密码登录，一键导入全部数据 | 多端点并行探测 |
| 设置 | 显示周末开关、自定义背景 | — |
| 关于 | 版本信息、更新检测 | GitHub Releases |

### 数据持久化
- Room 本地数据库，所有数据离线可用
- 切换账号自动清除旧数据，各菜单独立刷新按钮

## 技术栈

- Kotlin 2.2.21
- Jetpack Compose（BOM 2026.04.01）+ Material 3
- Room 2.8.4（KSP 编译）
- DataStore Preferences 1.2.1
- OkHttp 5.3.2
- Security-Crypto 1.1.0-alpha06
- kotlinx-coroutines 1.10.2
- Gradle 8.13 + AGP 8.13.0

## 环境要求

- Android Studio 2025.x+
- Android SDK Platform 36，minSdk 26
- JDK 17（Kotlin 2.2.x 不支持 Java 25）
- Android 8.0 及以上真机或模拟器

## 构建

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot"

# Debug APK
.\gradlew.bat assembleDebug

# Release APK（需 keystore.properties）
.\gradlew.bat assembleRelease

# 运行所有测试
.\gradlew.bat testDebugUnitTest

# 运行单个测试
.\gradlew.bat testDebugUnitTest --tests "com.glut.schedule.*Test"

# 清理
.\gradlew.bat clean
```

APK 输出：
- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/glutShedule_<version>.apk`

## 架构

```
app/src/main/java/com/glut/schedule/
  MainActivity.kt                   单 Activity，侧边抽屉导航，Compose UI
  ScheduleApplication.kt            AppContainer 手动 DI

  data/
    model/
      ScheduleModels.kt             课表（ScheduleCourse, CourseOccurrence, ClassPeriod 等）
      ScoreModels.kt                成绩（ScoreInfo）
      ExamModels.kt                 考试（ExamInfo）
      GradeExamModels.kt            等级考试（GradeExamInfo）
      StudyPlanModels.kt            教学计划（StudyPlanGroup）
      CourseColorMapper.kt          课程颜色分配
    local/
      ScheduleEntities.kt           Room 实体
      ScheduleDao.kt                Room DAO
      ScheduleDatabase.kt           Room 数据库（version 5）
    repository/
      ScheduleRepository.kt         统一数据仓库，组合各 DAO Flow
    settings/
      ScheduleSettingsStore.kt      DataStore（周数、周末、背景、校区类型、更新版本）

  service/
    academic/
      AcademicSessionStore.kt       Cookie / campus URL 持久化
      AcademicExamService.kt        考试数据获取 + 多端点探针
      AcademicLoginService.kt       静默登录（凭据 → Cookie）
      AcademicLoginHttpClient.kt    HTTP 登录客户端（CapturingCookieJar）
      AcademicOALoginClient.kt      OA 统一认证登录（ca.glut.edu.cn）
      AcademicImportConfig.kt       教务 URL 构建
      ApiProbeService.kt            多端点并行探测引擎
      CredentialStore.kt            加密凭据存储
      NanningPasswordHash.kt        南宁密码 MD5(MD5(password)) 哈希
    parser/
      AcademicScheduleParser.kt     GLUT 课表 HTML 解析器
      NanningCurrcourseParser.kt    南宁课表 HTML 解析器
      CompositeScheduleParser.kt    组合解析器（南宁优先 → GLUT 兜底）
      ExamParser.kt                 考试 JSON/HTML 双模式解析
      ScoreParser.kt                成绩 HTML 解析（桂林/南宁列映射）
      GradeExamParser.kt            等级考试 HTML 解析
      StudyPlanParser.kt            教学计划 HTML 解析（selfSchedule → 课组提取）
    UpdateChecker.kt                版本更新检测

  ui/
    components/
      ScheduleBackground.kt         星空渐变背景 + 自定义图片（LRU 缓存）
      ScheduleHeader.kt             周次、日期、操作按钮
      ScheduleGrid.kt               课表网格
    navigation/
      DrawerItem.kt                 侧边抽屉菜单项枚举
    pages/
      ScheduleScreen.kt / ScheduleViewModel.kt
      ScoreScreen.kt / ScoreViewModel.kt
      ExamScreen.kt / ExamViewModel.kt
      GradeExamScreen.kt / GradeExamViewModel.kt
      StudyPlanScreen.kt / StudyPlanViewModel.kt
      DirectLoginScreen.kt / DirectLoginViewModel.kt
      AboutScreen.kt
    theme/
      Theme.kt                      深色 Material 3 主题
```

## 数据流

- `ScheduleSettingsStore`（DataStore）→ 当前周数 → `ScheduleViewModel` → 计算 `ScheduleWeek`
- `ScheduleRepository`（Room DAO Flow）→ 各 DAO Flow → ViewModel `combine()` → `StateFlow<UiState>`
- 导入时 `ApiProbeService.probeAllEndpoints()` 并行探测多个教务端点 → 挑选最佳结果 → 解析保存

## 教务端点

| 功能 | 端点 | 编码 |
|------|------|------|
| 课表 | currcourse.jsdo, showTimetable.do | GBK/UTF-8 |
| 成绩 | studentOwnScore.do (POST) | GBK |
| 考试 | studentQueryAllExam.do, queryExam.do 等 | JSON/HTML |
| 等级考试 | skilltest.jsdo?moduleId=2090 | GBK |
| 教学计划 | studentSelfSchedule.jsdo → studentScheduleLineShow.do | GBK/UTF-8 |

## 测试

- JUnit 4.13.2 + kotlinx-coroutines-test
- 覆盖：Parser、Repository、Service、Model 层
- 测试目录：`app/src/test/java/`
