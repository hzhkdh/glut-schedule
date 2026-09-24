package com.glut.schedule.service.holiday

/**
 * 补齐当前学期跨越年份里本地缺失的年度节假日数据。
 *
 * **只在用户主动刷新时调用**（刷新课表 / 刷新学期概览 / 重新导入课表），不做任何
 * 定时或启动时请求——用户没主动更新数据时，App 一次都不该打 timor 接口。
 *
 * 放在这里而不是各 ViewModel 里各写一遍：三个入口的取数口径必须完全一致，
 * 否则「首页能拿到、学期概览拿不到」这类漂移迟早会出现。
 *
 * @param cachedYears 已过读闸门的缓存表（被污染 / 未公布过的年份在这里就是缺失）
 * @param saveYear 写入单年缓存；实现方内部带写闸门，空载荷不会落盘
 * @return 实际拉取到的年份
 */
internal suspend fun refreshMissingHolidayYears(
    client: TimorHolidayClient,
    years: IntRange,
    cachedYears: Map<Int, String>,
    saveYear: suspend (year: Int, json: String) -> Unit
): List<Int> {
    // 契约：**永不抛出**。这是尽力而为的旁路刷新——它挂在导入 / 刷新课表这些主流程上，
    // 节假日接口或本地存储出问题绝不能把主流程也拖失败（用户会看到「导入失败」，
    // 而真正的原因只是节假日接口抽风）。
    return runCatching {
        val missing = holidayYearsMissingFromCache(years, cachedYears)
        val loaded = mutableListOf<Int>()
        missing.forEach { year ->
            val raw = client.fetchYear(year)
            // 未公布年份返回的是合法但内容为空的文档：写闸门会拦下它，
            // 于是它保持「缺失」，等用户下次主动刷新时再问一次。
            if (isUsableHolidayYearPayload(raw, year)) {
                saveYear(year, raw)
                loaded += year
            }
        }
        loaded
    }.getOrDefault(emptyList())
}
