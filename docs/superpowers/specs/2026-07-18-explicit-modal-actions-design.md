# 更新与通知弹窗显式操作设计

## 目标

更新弹窗和通知弹窗不能因点击卡片外遮罩或按系统返回键而关闭。用户必须点击卡片内部的明确按钮，才能关闭弹窗、切换状态或跳转页面。

## 交互规则

- `NoticePopupDialog` 禁止点击遮罩关闭，也禁止返回键关闭。
- `UpdateDialog` 的 `Idle`、`Downloading`、`DownloadFailed`、`Done` 四种状态均禁止点击遮罩关闭，也禁止返回键关闭。
- 所有弹窗的 `onDismissRequest` 不改变业务状态。
- 通知弹窗仅由“知道了”关闭，或由“查看通知”关闭并进入通知页。
- 更新弹窗继续由卡片内现有按钮驱动：“取消”“取消下载”“稍后”关闭；“立即更新”“重试”切换下载状态；“立即安装”启动安装并关闭。
- 强制更新原有按钮限制保持不变。
- 其他对话框不在本次范围内。

## 实现

在 Compose `AlertDialog` 上显式传入 `DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)`，并将目标弹窗的 `onDismissRequest` 设为空操作。提取共享的不可外部关闭属性，避免四个更新状态与通知弹窗出现配置漂移。

## 测试与发布

- 增加源码契约测试，覆盖通知弹窗及更新弹窗所有状态的不可外部关闭配置，并确认卡片内按钮仍绑定业务回调。
- 运行 `testDebugUnitTest` 与 `assembleRelease`，验证 APK 签名及包内版本。
- 保持 `versionCode = 117`、`versionName = "0.19.0"`，不调用发布任务。
