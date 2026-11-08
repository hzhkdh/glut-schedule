package com.glut.schedule.service.refresh

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** 只读取 Android 标准电池优化状态，不能据此判断厂商的后台耗电、自启动开关。 */
fun androidBatteryOptimizationIgnored(context: Context): Boolean? = runCatching {
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName)
}.getOrNull()

/** 入口只导航到设置，由用户自行选择；不申请直接豁免电池优化的权限。 */
object BackgroundRunSettings {
    fun openBatterySettings(context: Context): Boolean {
        // 该页面已在 vivo 系统包中核对为导出页面；不同版本仍需检查可用性并捕获启动失败。
        if (Build.MANUFACTURER.equals("vivo", ignoreCase = true)) {
            val component = ComponentName("com.iqoo.powersaving", "com.iqoo.powersaving.BackgroundHighUsageActivity")
            val info = runCatching { context.packageManager.getActivityInfo(component, 0) }.getOrNull()
            if (info != null && info.exported && info.enabled && info.applicationInfo.enabled &&
                (info.permission.isNullOrEmpty() || context.checkSelfPermission(info.permission) == PackageManager.PERMISSION_GRANTED)
            ) {
                if (launch(context, Intent().setComponent(component))) return true
            }
        }
        // 标准列表只供用户检查设置，不触发 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 直接豁免。
        return launch(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) || openAppDetails(context)
    }

    /** 厂商自启动页没有统一协议，使用应用详情并由界面给出手动路径，避免误开后台弹出界面。 */
    fun openAppDetails(context: Context): Boolean =
        launch(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) ||
            launch(context, Intent(Settings.ACTION_SETTINGS))

    private fun launch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: android.content.ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
