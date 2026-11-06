package com.glut.schedule.service.refresh

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

const val UNIQUE_SCHEDULED_REFRESH = "academic_scheduled_refresh"

/** 包括午夜两侧在内，每两个相邻时刻至少相隔 WorkManager 要求的 15 分钟。 */
fun validateRefreshTimes(times: List<Int>): Boolean {
    if (times.size !in 1..8 || times.any { it !in 0..1439 } || times.distinct().size != times.size) return false
    val sorted = times.sorted()
    if (sorted.zipWithNext().any { (first, second) -> second - first < 15 }) return false
    return sorted.first() + 1440 - sorted.last() >= 15
}

/** 严格选择未来的本地固定时刻；同一时刻已到达时直接寻找下一个。 */
fun nextRefreshInstant(times: List<Int>, now: Instant, zone: ZoneId): Instant {
    require(validateRefreshTimes(times)) { "定时刷新时刻无效" }
    var date = now.atZone(zone).toLocalDate()
    while (true) {
        val candidates = times.map { minute ->
            // atZone 将夏令时缺口向后平移；重叠时使用较早偏移，每个本地时刻每天只运行一次。
            date.atTime(LocalTime.of(minute / 60, minute % 60)).atZone(zone).toInstant()
        }
        candidates.filter { it.isAfter(now) }.minOrNull()?.let { return it }
        date = date.plusDays(1)
    }
}

/** 每次运行后重算下一本地时刻，避免按固定 24 小时间隔漂移或遗漏一天中的其他时刻。 */
fun reconcileScheduledRefresh(
    context: Context,
    config: ScheduledRefreshConfig,
    supported: Boolean,
    now: Instant = Instant.now()
) {
    val manager = WorkManager.getInstance(context)
    if (!config.enabled || !supported) {
        manager.cancelUniqueWork(UNIQUE_SCHEDULED_REFRESH)
        return
    }
    val next = nextRefreshInstant(config.times, now, ZoneId.systemDefault())
    val request = PeriodicWorkRequestBuilder<ScheduledRefreshWorker>(24, TimeUnit.HOURS)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        // override 明确指定下一次执行时间；UPDATE 保留正在运行的刷新，不因编辑配置中断它。
        .setNextScheduleTimeOverride(next.toEpochMilli())
        .build()
    manager.enqueueUniquePeriodicWork(UNIQUE_SCHEDULED_REFRESH, ExistingPeriodicWorkPolicy.UPDATE, request)
}
