# 桂林理工大学课表

一个面向桂林理工大学学生的本地 Android 课表 App。

## 功能

- 启动即进入课表页面，无需登录注册
- 顶部显示当前周数、星期和日期，左右滑动切换周次（1-22 周）
- 左侧节次列显示节次与上下课时间，顶部显示周一至周日
- 彩色课程卡片展示课程名、教室、教师、周次
- 同一时段多门冲突课程自动分组，点击轮换显示
- 暗色星空渐变背景，支持自定义背景图片（带采样解码和 LRU 缓存）
- 设置面板：切换显示周末、考试安排入口、关于信息
- 通过 WebView 从 GLUT 教务系统导入个人课表
- 考试安排：从教务系统获取考试数据，时间线 UI 按日期分组展示（含状态标签），支持下拉刷新
- 静默登录：加密存储教务凭据，后台自动登录刷新考试数据
- Room 本地数据库持久化课程与考试数据

## 技术栈

- Kotlin 2.2.21
- Jetpack Compose（BOM 2026.04.01）+ Material 3
- Room 2.8.4（KSP 编译）
- DataStore Preferences 1.2.1
- OkHttp 5.3.2
- Security-Crypto 1.1.0-alpha06（EncryptedSharedPreferences 凭据加密）
- kotlinx-coroutines 1.10.2
- Gradle 8.13 + AGP 8.13.0

## 环境要求

- Android Studio 2025.x 或更新版本
- Android SDK Platform 36，minSdk 26
- JDK 17（Kotlin 2.2.x 不支持 Java 25）
- 安卓真机或模拟器

## 本地运行

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot"

# 构建 Debug APK
.\gradlew.bat assembleDebug

# 构建 Release APK（需 keystore.properties）
.\gradlew.bat assembleRelease

# 运行单元测试
.\gradlew.bat testDebugUnitTest

# 运行单个测试
.\gradlew.bat testDebugUnitTest --tests "com.glut.schedule.*Test"

# 清理
.\gradlew.bat clean
```

APK 输出路径：
- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/glutShedule_<version>.apk`

## 项目结构

```
app/src/main/java/com/glut/schedule/
  MainActivity.kt                   入口 Activity，单页面导航
  ScheduleApplication.kt            Application，手动 DI 容器

  data/
    model/
      ScheduleModels.kt             课表模型（ScheduleCourse, CourseOccurrence, ScheduleWeek 等）
      ExamModels.kt                 考试模型（ExamInfo）
      CourseColorMapper.kt          课程颜色分配器（12 色调色板，避免相近色冲突）
    local/
      ScheduleEntities.kt           Room 实体
      ScheduleDao.kt                Room DAO
      ScheduleDatabase.kt           Room 数据库
    repository/
      ScheduleRepository.kt         数据仓库，组合 DAO Flow，填充示例数据
    settings/
      ScheduleSettingsStore.kt      DataStore（当前周数、显示周末、背景图片）

  service/
    academic/
      AcademicSessionStore.kt       DataStore 存储 Cookie/考试 URL
      AcademicExamService.kt        考试数据获取
      AcademicLoginService.kt       静默登录工作流
      AcademicWebScripts.kt         注入 WebView 的 JS 代码
      AcademicTodayPlanParser.kt    今日课程 JSON 解析
      AcademicImportConfig.kt       教务系统 URL 与页面识别
      ApiProbeService.kt            多端点探测引擎
      CredentialStore.kt            加密凭据存储
      DebugCaptureService.kt        调试数据导出（默认关闭）
    parser/
      AcademicScheduleParser.kt     课表 HTML 解析器（7 种策略）
      ExamParser.kt                 考试 JSON/HTML 解析器

  ui/
    components/
      ScheduleBackground.kt         星空渐变背景 + 自定义图片
      ScheduleHeader.kt             顶部栏（周次、日期、操作按钮）
      ScheduleGrid.kt               课表网格（节次列 + 日期列 + 课程卡片）
    pages/
      ScheduleScreen.kt             主课表页面
      ScheduleViewModel.kt          主页面状态管理
      AcademicImportScreen.kt       WebView 导入页面
      AcademicImportViewModel.kt    导入状态与 API 探测编排
      ExamScreen.kt                 考试列表页面
      ExamViewModel.kt              考试刷新与静默登录编排
    theme/
      Theme.kt                      深色 Material 3 主题
```

## 数据流

- `ScheduleSettingsStore`（DataStore）→ 当前周数 → `ScheduleViewModel` → 计算 `ScheduleWeek`
- `ScheduleRepository`（Room DAO Flow）→ `observeCourses()` + `observeOccurrences()` → 合并为课程列表
- `ScheduleViewModel.uiState` = `combine(week, showWeekend, periods, courses)` → `ScheduleUiState`

## 导入流程

1. AcademicImportScreen 加载 `jw.glut.edu.cn` WebView
2. 用户在 WebView 中登录教务系统（凭据自动捕获），导航到课表或考试页面
3. 页面加载时 JS 注入拦截 API 响应，自动检测登录表单并保存 Cookie
4. 点击 FAB 下载按钮 → 优先使用拦截的 API 数据，回退到 HTML 解析
5. 解析器处理数据 → 通过 `ScheduleRepository.replaceImportedCourses()` / `replaceExams()` 保存
6. 静默登录：EncryptedSharedPreferences 保存凭据，后台自动登录刷新数据

## 测试

- JUnit 4.13.2 + kotlinx-coroutines-test
- 测试目录：`app/src/test/java/`
