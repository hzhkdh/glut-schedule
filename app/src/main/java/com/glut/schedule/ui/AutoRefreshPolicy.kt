package com.glut.schedule.ui

import com.glut.schedule.BuildConfig

/**
 * 自动刷新「该不该动」的时间判定。
 *
 * 与 [com.glut.schedule.service.greeting.GreetingTemplateRepository] 的取数策略同形状：
 * 只在超过间隔时放行一次，把「上次尝试」而不是「上次成功」当作节流键。区别在于这里
 * 只做纯计算，不碰存储、不发请求，方便单测。
 *
 * 为什么用「上次尝试」：如果按「上次成功」算，密码错误期间用户每打开一次 App 就会撞
 * 一次登录，一天可能撞十几次，有被教务锁号的风险；按「上次尝试」算，8 小时内只撞一次。
 */
internal object AutoRefreshPolicy {
    /** 正式包的距离：8 小时。一天最多触发两三次，足够跟上教务的排课变动。 */
    private const val RELEASE_INTERVAL_MILLIS: Long = 8L * 60L * 60L * 1000L

    /**
     * debug 包的距离：1 分钟。
     *
     * 自动刷新要验证的是「到点会不会触发」，真按 8 小时等一轮没法调试。debug 包缩短到
     * 1 分钟：既能反复观察，又足够长到可以稳定构造「本轮不刷新」的对照组——30 秒时
     * 人工打开 App 的间隔几乎总超过窗口，反而测不出「不刷新」的样子。正式包不受影响。
     */
    private const val DEBUG_INTERVAL_MILLIS: Long = 60L * 1000L

    val INTERVAL_MILLIS: Long =
        if (BuildConfig.DEBUG) DEBUG_INTERVAL_MILLIS else RELEASE_INTERVAL_MILLIS

    /**
     * 距离上次尝试是否已经够久。
     *
     * [lastAttemptEpochMillis] 传 null 或非正数都视为「从未尝试过」，直接放行。
     */
    fun isDue(nowEpochMillis: Long, lastAttemptEpochMillis: Long?): Boolean {
        if (lastAttemptEpochMillis == null || lastAttemptEpochMillis <= 0L) return true
        return nowEpochMillis - lastAttemptEpochMillis >= INTERVAL_MILLIS
    }
}
