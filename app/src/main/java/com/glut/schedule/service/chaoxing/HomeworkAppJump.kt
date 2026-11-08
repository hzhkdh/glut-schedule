package com.glut.schedule.service.chaoxing

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder

private const val HOMEWORK_LIST = "https://mooc1-api.chaoxing.com/work/stu-work"

/** 仅交给学习通接管官方作业入口，拒绝任意网页、作答或提交地址。签名链接不落盘。 */
internal fun homeworkAppDeepLink(target: String): String {
    val url = requireNotNull(target.toHttpUrlOrNull())
    require(url.scheme == "https" && url.username.isEmpty() && url.password.isEmpty())
    require(target == HOMEWORK_LIST || (url.host == "mooc1.chaoxing.com" &&
        url.encodedPath in setOf("/mooc-ans/mooc2/work/task", "/mooc2/work/task") &&
        !url.queryParameter("workId").isNullOrBlank()))
    // 与超星官方唤起链接保持一致：内层省略协议，整段编码避免作业参数溢出到外层。
    val resource = url.toString().removePrefix("https://")
    return "chaoxingshareback://url=" + URLEncoder.encode(resource, "UTF-8").replace("+", "%20") +
        "&title=homework&sharebacktype=1"
}

private fun launchHomework(context: Context, target: String): String? = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(homeworkAppDeepLink(target)))
        .setPackage("com.chaoxing.mobile").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    null
} catch (_: ActivityNotFoundException) {
    "未检测到可打开此入口的学习通，请安装或更新后重试"
} catch (_: SecurityException) {
    "系统未允许打开学习通，请检查应用跳转设置"
}

fun openChaoxingHomeworkList(context: Context): String? = launchHomework(context, HOMEWORK_LIST)

/** 只获取入口，不请求作答页面；跳转后身份与权限由学习通自己的登录状态决定。 */
suspend fun openChaoxingHomework(context: Context, row: CachedHomework, store: HomeworkStore, generation: Long): String? {
    if (row.kind != "homework" || homeworkState(row.status) != HomeworkState.TODO) return "此项不是未完成作业"
    return try {
        val target = resolveHomeworkAppEntry(row, store, generation)
        withContext(Dispatchers.Main) {
            if (store.generation != generation) "学习通账号已变化，请重新打开任务"
            else launchHomework(context, target)
        }
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) { "暂时无法定位这份作业，可打开学习通作业列表查找" }
}
