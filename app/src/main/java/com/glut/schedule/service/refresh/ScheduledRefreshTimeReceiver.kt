package com.glut.schedule.service.refresh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.glut.schedule.ScheduleApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** 系统时钟或时区改变后重算本地固定时刻，仅修改排程，不触发教务请求。 */
class ScheduledRefreshTimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIME_CHANGED && intent.action != Intent.ACTION_TIMEZONE_CHANGED) return
        val application = context.applicationContext as? ScheduleApplication ?: return
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                // 广播有执行时限；本地配置读取异常或协程取消都必须及时归还 PendingResult。
                withTimeout(8_000) {
                    val config = ScheduledRefreshStore(application).config.first()
                    reconcileScheduledRefresh(application, config, true, recalibrateClock = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Log.w("ScheduledRefresh", "系统时间变化后的排程更新失败，重新打开应用后将再次校准")
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }
}
