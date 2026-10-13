package com.glut.schedule.service.holiday

/**
 * 拉取调休补课对照表并落盘。
 *
 * 调用点：**冷启动同步一次**、以及用户主动刷新（刷新课表 / 刷新学期概览 / 重新导入课表 /
 * 编辑页的显式按钮）。与 [refreshMissingHolidayYears] 同一约定：**进入页面只读缓存**，
 * 用户不主动更新时不该反复打这个接口——冷启动那次是例外，因为学校通知发布不定时，
 * 让用户为了拿到新表先手动刷新一次没有道理。
 *
 * 契约：**永不抛出**。它挂在导入 / 刷新课表这些主流程上，对照表接口或本地存储出问题
 * 绝不能把主流程也拖失败——那样用户看到的是「导入失败」，而真实原因只是对照表抽风。
 *
 * @param saveJson 写入原文；实现方内部带写闸门（见 [isUsableMakeupTablePayload]）
 * @return 是否真的更新成功（供编辑页的按钮反馈用；三个刷新入口忽略返回值）
 */
internal suspend fun refreshMakeupTable(
    client: MakeupTableClient,
    saveJson: suspend (json: String) -> Unit
): Boolean = runCatching {
    val body = client.fetch() ?: return@runCatching false
    // 写闸门：拿到的不是一份能解析的表（例如静态托管的 200 + HTML 兜底页）就**不落盘**，
    // 保留上一次同步成功的表，而不是把「表被清空」写进磁盘。
    if (!isUsableMakeupTablePayload(body)) return@runCatching false
    saveJson(body)
    true
}.getOrDefault(false)
