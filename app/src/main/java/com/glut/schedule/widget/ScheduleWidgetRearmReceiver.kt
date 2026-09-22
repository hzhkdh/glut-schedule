package com.glut.schedule.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 重新武装小组件的刷新链。
 *
 * 刷新链是「排一个一次性任务 → 触发后自我续期」的形式，**只**在添加小组件、App 进程启动、
 * 以及课表数据变化这三处会被点燃。手机重启或 App 升级之后，WorkManager 里那条待命的任务
 * 可能已经不在了，而系统那个 30 分钟的 `updatePeriodMillis` 在 Doze 下又极不可靠——
 * 结果就是小组件长时间停在旧内容上。这里补上两个最容易被忽略的重新武装时机。
 *
 * 单独一个文件而不是塞进 `ScheduleWidgetReceivers.kt`：那里装的是三个小组件自身的
 * AppWidgetProvider，而 `ScheduleWidgetPreviewContractTest` 按「三个 widget 各请求一次刷新」
 * 做计数断言，混进来会让那条断言失去意义。
 */
class ScheduleWidgetRearmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "收到 ${intent.action}，重新武装小组件刷新链")
                ScheduleWidgetRefreshScheduler.requestImmediate(context)
            }
        }
    }

    private companion object {
        const val TAG = "ScheduleWidget"
    }
}
