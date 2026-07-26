# 2026-07-26 完整 Code Review

## 审查范围

- 分支：`main`
- 版本：`0.21.0`
- 范围：Android 客户端、Room/DataStore、教务导入、更新发布链路、Compose UI、自动化测试与构建配置。
- 交叉验证：通过 Chrome DevTools 检查桂林和南宁教务实际登录/跳转行为；两校区教务当前均只提供 HTTP，HTTPS 握手会被服务器关闭。
- 修复前基线：447 个单元测试，0 失败、1 跳过；Lint 0 errors、124 warnings、5 hints；Release 构建成功。

## 审查结论

| 问题位置（文件名 + 行号） | 问题描述 | 严重程度 | 修改建议 |
|---|---|---|---|
| `service/academic/AcademicLoginService.kt:225`、`ApiProbeService.kt:40` | OA 重定向及菜单/探测 URL 原先没有统一主机、端口和路径白名单，可能把教务 Cookie 发送到外部地址。 | Critical | 对初始 URL、相对/绝对重定向以及菜单解析后的 URL 统一执行严格白名单校验。 |
| `AndroidManifest.xml:15` | 应用原先全局允许明文流量，任何误用的 HTTP 地址都能绕过传输限制。 | Major | 默认禁用明文，只对三项校方遗留 HTTP 服务按域名开放，并在登录页持续提示风险。 |
| `service/academic/CredentialStore.kt:8` | 加密存储初始化失败时原先回退到普通 SharedPreferences，账号密码可能以明文落盘。 | Critical | 加密不可用时关闭“记住密码”，不再进行任何明文持久化，并清理旧回退文件。 |
| `ui/pages/DirectLoginViewModel.kt:519` | 教务缓存没有按学号隔离；切换账号时可能短暂展示上一账号的课表、成绩、考试和教学计划。 | Major | 在写入新身份前比较学号，不同账号时事务性清空全部教务缓存和旧会话。 |
| `ui/pages/DirectLoginViewModel.kt:552` | 多个导入模块异常被吞成 0 条，最终仍可能显示“导入完成”，且失败路径可能覆盖旧缓存。 | Major | 每个模块返回明确成功/失败结果；失败时保留旧缓存，最终汇总失败模块。 |
| `ui/pages/DirectLoginViewModel.kt:493` | 南宁登录只凭通用 2xx 响应判断成功，登录页或中间页也可能被误判。 | Major | 同时校验状态码、跳转地址、登录表单标记和已登录页面特征。 |
| `service/UpdateChecker.kt:163`、`service/AppUpdater.kt:45` | 更新下载原先缺少 HTTPS/主机、体积、SHA-256、包名、版本号和签名校验，存在更新供应链风险。 | Critical | 发布元数据携带版本号、大小和哈希；下载及每次重定向校验来源；安装前与已安装包进行身份和签名比对。 |
| `app/build.gradle.kts:170` | 发布任务原先使用宽泛暂存并容忍 GitHub Release 失败，可能提交无关文件或形成不完整发布。 | Major | 使用确定性 APK 名称，只暂存指定产物；任何发布通道失败都中止任务。 |
| `data/repository/ScheduleRepository.kt:231`、`data/local/ScheduleDao.kt:212` | 调课刷新完成时才读取可变化的查看学期，且删除/插入不在同一事务，可能写错学期或留下空缓存。 | Major | 请求开始时捕获显式学期 ID，并用 Room `@Transaction` 原子替换。 |
| `ui/pages/ScheduleViewModel.kt:352`、`SemesterOverviewViewModel.kt:221`、`DirectLoginViewModel.kt:248` | 连续点击可在 StateFlow 更新/重组前启动多个登录或刷新任务，产生重复请求和竞态写入。 | Major | 使用原子 single-flight 守卫同步抢占入口，在所有完成/异常路径释放。 |
| `service/academic/ApiProbeService.kt:139` | 导入时串行探测大量猜测端点，最坏等待时间长，且读取响应体没有硬上限。 | Major | 仅保留实际解析会消费的 7 个端点，设置整体时间预算，并对所有网络响应实行流式硬上限。 |
| `MainActivity.kt:190`（修复前） | Activity 启动使用 `runBlocking` 读取 DataStore 和解码背景，可能直接阻塞首帧。 | Major | 启动先渲染内置背景，在 Compose 生命周期内异步读取和预载自定义背景。 |
| `ui/components/ScheduleBackground.kt:52` | 背景缓存按“2 张”限制，未按实际像素字节计量；两张大图仍可能造成明显内存压力。 | Major | 以 ARGB 字节数作为 `LruCache.sizeOf`，设定总字节预算并继续采样/裁剪解码。 |
| `ui/pages/*Screen.kt` | 多个页面直接 `collectAsState()`，页面进入后台后仍可能持续收集并触发重组。 | Minor | 统一改为 `collectAsStateWithLifecycle()`。 |
| `ui/pages/SemesterOverviewViewModel.kt:82` | 历史学期进度的已过天数原先混用了当前设置的开始日期。 | Minor | 用正在查看学期的开始、结束日期统一计算百分比、已过和剩余天数。 |
| `ui/pages/ScoreViewModel.kt`、`GradeExamViewModel.kt` | 调试日志曾打印教务 HTML 前 300 字符，可能包含姓名、课程或成绩信息。 | Major | 仅记录状态码、长度和错误类型，不记录正文、账号、Cookie、学号或本地绝对路径。 |
| `app/src/test/java/com/glut/schedule` | 安全边界、账号切换、更新包身份、调课事务、并发入口和响应体上限原先缺少直接回归测试。 | Suggestion | 为每个风险边界增加纯函数或 MockWebServer/DAO 回归测试，并保留 RED→GREEN 记录。 |

## 上游遗留限制

- 桂林、南宁教务服务均未提供可用 HTTPS。客户端已把明文范围收缩到校方明确域名/端口，但无法从客户端消除同一网络中的被动监听或主动篡改风险。
- 更新元数据和 APK 哈希同源发布，主要用于防损坏和阻止非预期重定向；真正的发布身份最终由 Android 安装包签名与当前已安装应用的签名一致性保证。
- 南宁教务有图片验证码，无法做无人值守的完整重新登录；真机验证应复用有效会话或由用户现场输入验证码。

