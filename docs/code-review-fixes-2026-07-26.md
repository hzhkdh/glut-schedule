# 2026-07-26 Code Review 修复记录

## 修复摘要

本轮保持版本 `0.21.0`，在当前 `main` 分支完成客户端加固；未推送远程、未创建 Release、未执行 `publishUpdate`。

### 1. 教务网络与凭据

- 新增 `AcademicUrlPolicy`，只允许桂林、南宁教务及桂林统一认证的明确 HTTP 主机、端口和路径。
- OA 重定向、API 探测及菜单动态 URL 均拒绝外部目标。
- 新增域名级 `network_security_config.xml`，默认禁止明文流量。
- `CredentialStore` 加密不可用时停止持久化，不再回退明文 SharedPreferences；登录页禁用“记住密码”并显示 HTTP 风险提示。

### 2. 账号隔离与导入语义

- 新学号登录成功前检查旧身份；账号变化时清理旧课表、学期、考试、成绩、等级考试、教学计划、调课和会话。
- 南宁登录成功判断增加页面特征校验。
- 课表以外的模块分别记录失败；失败模块保留原缓存，界面提示“部分导入失败：…；已保留原缓存”。

### 3. 更新供应链

- 更新 JSON 必须包含 `versionCode`、`apkSha256`、`apkSize`，下载 URL 必须为受信 HTTPS APK。
- 下载过程检查每次重定向、声明/实际大小、100 MiB 硬上限和 SHA-256；错误或取消时删除临时文件。
- 安装前解析 APK，校验包名、版本递增及签名证书集合与已安装应用一致。
- `publishUpdate` 使用确定性文件名、生成哈希/大小，只暂存指定文件；GitHub Release 失败会使发布任务失败。

### 4. 数据一致性、并发与性能

- 调课替换改为“请求开始时捕获学期 ID + Room 事务删除/插入”。
- 登录、课表刷新、学期信息/调课刷新接入原子 single-flight 守卫。
- 全量导入探测从大量猜测端点收敛为 7 个实际消费端点，并设置 60 秒总预算。
- HTML 最大 4 MiB、JSON 最大 512 KiB、验证码图片最大 2 MiB、布尔响应最大 1 KiB；同时检查 Content-Length 和实际流式字节数。

### 5. 启动、图片、生命周期与隐私

- 移除 Activity 启动阶段的 `runBlocking` 和同步图片解码，改为 Compose 内异步预载。
- 背景图仍按屏幕目标采样/裁剪，缓存从“2 张”改为按 ARGB 实际字节计量，总预算 24 MiB。
- 页面 StateFlow 统一使用 `collectAsStateWithLifecycle()`。
- 历史学期进度统一使用该历史学期的起止日期。
- 删除成绩、等级考试 HTML 正文预览、学生 ID 和本地下载绝对路径日志。

## 新增/更新测试

- `AcademicSecurityPolicyTest`：教务 URL/重定向白名单、菜单外链、端点收敛、加密不可用时记住密码关闭。
- `DirectLoginSafetyTest`：账号切换清理、南宁登录判断、部分导入失败提示。
- `UpdateSecurityTest`、`AppUpdaterDownloadTest`：HTTPS 来源、元数据、大小、哈希、APK 包名/版本/签名。
- `ScheduleRepositoryTest`：显式目标学期调课替换。
- `SingleFlightGuardTest`：重复入口拒绝和完成后恢复。
- `LimitedResponseBodyTest`：响应体边界与超限拒绝。
- `ScheduleBackgroundTest`：缓存字节计量与溢出保护。
- `SemesterProgressTest`：历史学期进度日期。

上述新增行为均先运行目标测试观察失败，再实现并运行至通过。

## 验证记录

| 项目 | 结果 |
|---|---|
| `testDebugUnitTest` | 通过：466 个测试，0 失败，1 跳过。 |
| `lintDebug` | 通过：0 errors、120 warnings、5 hints；本轮曾发现 1 个可疑缩进，修正后重跑通过。 |
| `assembleRelease` | 通过：生成 `app/build/outputs/apk/release/glutShedule_0.21.0.apk`。 |
| `git diff --check` | 通过，无空白错误；仅有工作区 LF/CRLF 提示。 |
| ADB 安装与启动 | vivo V2463A，Android 16；`adb install -r` 成功，版本 0.21.0（121），冷启动 340 ms，无崩溃/ANR。首次安装确认被误触取消，重新确认后成功。 |
| 首页/背景 | 已保留数据覆盖安装；自定义背景异步显示，课程卡片和首页滚动正常。 |
| 侧边栏 | 实测宽度 1080/1440，即 75%；随机“Hi～姓名，下午好”问候正常显示。 |
| 设置页 | “问候语”命名正确；底部“重置应用”卡片完整显示，未被压缩。 |
| 校区与上课时间切换 | 雁山第 1 节 08:30；切换屏风后设置页和首页均立即显示 08:20；最后恢复雁山并确认首页 08:30。 |
| 登录/导入入口 | 页面正常打开，已保存账号和密码保持可用；持续显示“校方教务目前仅支持 HTTP”安全提示。为避免改变用户教务数据，本轮未再次提交导入。 |

## 验证后的状态

- 真机最后恢复为桂林雁山校区时间配置，未修改账号、课表或其他业务数据。
- 未执行远程推送、Release 发布或更新主机部署。
- `.playwright-mcp/` 为用户已有未跟踪目录，本轮未读取、修改或删除。

## 后续真机问题修复：首次导入、通知兼容与问候语

### 问题原因

- 桂林首次导入只消费通用探测中的单个考试接口，并把可解析的空列表当成成功；考试页手动刷新则使用完整的多接口兜底。南宁首选接口能够直接返回考试，因此未复现。
- 覆盖安装保留的通知缓存与启动网络刷新共同写入同一个 Compose 局部状态；任一刷新失败又会被静默解释成“暂无通知”。清除数据后恢复说明远程通知源和全新安装路径正常，问题集中在升级遗留状态及加载状态表达。
- 侧边栏问候语被固定为单行、20dp 高度和省略号，即使抽屉已占屏幕 75%，较长文本仍无法使用第二行。

### 修复内容

- 首次导入改为复用 `AcademicExamService`，与考试页共享多接口兜底及成功接口记忆；空列表不覆盖旧考试缓存。
- 从通用导入探测中移除重复考试请求，避免同一次导入重复访问考试接口。
- 移除导入页可见的 HTTP 安全提示，但保留桂林、南宁官方教务域名白名单和 Cookie 目标校验。
- 通知刷新与版本检查改为独立协程；新增加载、内容、真正为空和失败四种状态，刷新失败时保留有效缓存，并提供“重新加载”。
- 侧边栏头部拆分为品牌行和问候语行；问候语使用完整宽度、固定 40dp 高度和最多两行，避免打字时菜单跳动。

### 新增回归测试

- `DirectLoginSafetyTest`：空考试结果不覆盖缓存；有效考试结果写入缓存并保存成功接口。
- `NoticeLoadStateTest`：覆盖升级缓存优先、刷新失败保留缓存、失败与真正空状态区分。
- `AcademicSecurityPolicyTest`：通用导入不再重复探测考试接口。
- `DrawerGreetingIntegrationContractTest`：导入页提示移除及双行问候语布局约束。

### 验证结果

| 项目 | 结果 |
|---|---|
| 目标回归测试 | `DirectLoginSafetyTest`、`NoticeLoadStateTest`、`NoticeParserTest`、`AcademicExamServiceTest`、`AcademicSecurityPolicyTest`、`DrawerGreetingIntegrationContractTest` 全部通过。 |
| 完整单元测试 | 强制重跑 `testDebugUnitTest --rerun-tasks` 成功，共 474 项、0 失败、1 跳过。首次全量运行曾遇到 1 个既有体测测试的 Main Dispatcher 偶发失败，单独重跑及强制全量重跑均通过。 |
| Debug 构建 | `assembleDebug` 成功，生成 `app/build/outputs/apk/debug/app-debug.apk`。 |
| Release 构建 | `assembleRelease` 成功，R8、资源收缩和 lint vital 均通过。 |
| 覆盖安装 | Debug APK 因与已安装 Release 签名不同而拒绝；改用同密钥 Release APK，经 vivo“超级守护”授权后覆盖安装成功，`lastUpdateTime=2026-07-26 15:04:00`，未清除数据。 |
| 通知页面 | 覆盖安装后直接显示远程通知 `v0.20.0 更新日志`，无需清除数据。 |
| 导入页面 | 真机确认 HTTP 安全提示已移除，账号、记住密码和历史课表状态保留。 |
| 桂林首次导入 | 使用已授权教务账号完成 2026·秋首次导入，结果为 20 门课程、21 场考试，不再显示 0。 |
| 南宁导入 | 修复前真机已确认首次导入为 26 门课程、22 场考试；本轮只统一考试服务调用，没有改动南宁登录与验证码流程。 |
| 侧边栏问候语 | 品牌和问候语已拆行，问候语无障碍区域高度为 160px（设备密度 4，对应 40dp），支持两行且保留完整朗读文本。 |

本轮未推送远端、未发布 GitHub Release，也未部署更新主机。
