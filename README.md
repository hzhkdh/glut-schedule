# 桂林理工大学课表

一个面向桂林理工大学学生的本地 Android 课表 App。第一阶段已完成原生 Android 项目骨架、课表首页 UI、周切换、Room 本地数据结构和模拟课表展示。

## 功能列表

- App 启动后直接进入课表页
- 顶部显示当前周、星期和日期
- 左右滑动切换上一周 / 下一周
- 左侧显示节次和上下课时间
- 顶部显示周一到周日
- 彩色课程卡片展示课程名、教室、教师、周次
- 默认柔和暗色星空风格背景
- Room 本地数据库结构
- DataStore 保存当前周
- 预留教务系统 parser 接口

## 技术栈

- Kotlin
- Jetpack Compose
- Material 3
- Room
- DataStore
- Gradle 8.13
- Android Gradle Plugin 8.13.0

## 环境要求

- Android Studio 2025.x 或更新版本
- Android SDK Platform 36
- Android SDK Build Tools
- JDK 17 或 Android Studio 自带 JBR
- 安卓真机或模拟器

如果系统默认 Java 版本过新导致 Gradle 报错，请在本机环境变量里把 `JAVA_HOME` 设置为 Android Studio 自带 JBR，或在 Android Studio 内直接运行项目。不要把本机 JBR 绝对路径提交到 Git。

## 本地运行命令

```powershell
$env:JAVA_HOME="你的 Android Studio JBR 路径"
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

用 Android Studio 打开项目后，选择真机或模拟器，点击 Run 即可运行。

## APK 打包命令

Debug APK：

```powershell
.\gradlew.bat assembleDebug
```

输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

Release APK 后续需要配置签名文件后再启用。

## 项目结构

```text
app/src/main/java/com/glut/schedule/
  data/model          课程、课次、节次、周信息模型
  data/local          Room Entity、DAO、Database
  data/repository     课表数据访问入口
  data/settings       DataStore 设置
  service/parser      教务课表解析接口预留
  ui/components       课表背景、顶部栏、课表网格、课程卡片
  ui/pages            首页页面和 ViewModel
  ui/theme            Compose 主题
```

## 后续开发计划

1. 完成课程添加、编辑、删除。
2. 完成单次调课、地点和备注修改。
3. 接入 WebView 教务登录，保存 Cookie 到本地安全存储。
4. 解析桂林理工大学个人课表 HTML。
5. 实现重新导入课表与本地修改合并。
6. 支持自定义背景图片。
7. 配置正式签名并打包 release APK。

## Git 提交建议

第一阶段完成后建议执行：

```powershell
git status
git add .
git commit -m "feat: 完成课表首页 UI"
```

提交前确认没有账号密码、Cookie、Token、本地绝对路径或构建产物被纳入 Git。
