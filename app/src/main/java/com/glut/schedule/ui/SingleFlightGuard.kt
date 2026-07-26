package com.glut.schedule.ui

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 同一时刻只允许一个同类异步任务运行。
 *
 * 使用原子状态而不是只依赖界面 StateFlow，可封住连续点击发生在状态渲染前的竞态窗口。
 */
internal class SingleFlightGuard {
    private val running = AtomicBoolean(false)

    fun tryStart(): Boolean = running.compareAndSet(false, true)

    fun finish() {
        running.set(false)
    }
}
