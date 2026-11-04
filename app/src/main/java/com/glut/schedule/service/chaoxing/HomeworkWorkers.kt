package com.glut.schedule.service.chaoxing

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.NetworkType
import kotlinx.coroutines.CancellationException
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private val sessionGate = HomeworkSessionGate()
private const val REMINDER_TAG = "chaoxing_reminders"

class HomeworkSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = sessionGate.sync {
        // 详情占用会话时等待；只有另一同步已认领名额时，才复用已有同步。
        withContext(Dispatchers.IO) {
            val store = HomeworkStore(applicationContext)
            store.markAttempt()
            try {
                val result = ChaoxingClient().sync()
                store.merge(result)
                scheduleHomeworkReminders(applicationContext, store.read())
                Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                store.saveError("同步失败，请检查网络或重新登录学习通")
                Result.failure()
            } finally {
                store.finishDetailRefresh()
            }
        }
    } ?: Result.success()
}

fun scheduleHomeworkSync(context: Context) {
    // 首次同步由手动任务承担，避免周期任务和手动任务连续全量查询。
    val work = PeriodicWorkRequestBuilder<HomeworkSyncWorker>(6, TimeUnit.HOURS)
        .setInitialDelay(6, TimeUnit.HOURS)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork("chaoxing_sync", ExistingPeriodicWorkPolicy.UPDATE, work)
}

fun refreshHomework(context: Context) {
    WorkManager.getInstance(context).enqueueUniqueWork("chaoxing_manual", ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<HomeworkSyncWorker>().build())
}

/** 只清理学习通域名，不调用会影响教务登录的全局 Cookie 清理。 */
suspend fun disconnectChaoxing(context: Context) = sessionGate.withSession {
    withContext(Dispatchers.IO) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork("chaoxing_sync").result.get()
        manager.cancelUniqueWork("chaoxing_manual").result.get()
        manager.cancelAllWorkByTag(REMINDER_TAG).result.get()
        homeworkAccesses.clear()
        HomeworkStore(context).clear()
        val cookieManager = android.webkit.CookieManager.getInstance()
        listOf("passport2.chaoxing.com", "i.mooc.chaoxing.com", "mooc1.chaoxing.com", "mooc1-api.chaoxing.com", "mooc2-ans.chaoxing.com").forEach { host ->
            val url = "https://$host/"
            cookieManager.getCookie(url).orEmpty().split(';').map { it.substringBefore('=').trim() }
                .filter { it.isNotEmpty() }.forEach { name ->
                    cookieManager.setCookie(url, "$name=; Max-Age=0; Path=/")
                    cookieManager.setCookie(url, "$name=; Max-Age=0; Domain=.chaoxing.com; Path=/")
                }
        }
        cookieManager.flush()
    }
}

fun scheduleHomeworkReminders(context: Context, rows: List<CachedHomework>) {
    val manager = WorkManager.getInstance(context)
    // 等待取消完成，避免异步取消误删刚安排的新提醒。
    manager.cancelAllWorkByTag(REMINDER_TAG).result.get()
    val now = System.currentTimeMillis()
    rows.filter { homeworkState(it.status) == HomeworkState.TODO && it.deadline != null }.forEach { row ->
        listOf(24L, 1L).forEach { hours ->
            val delay = row.deadline!! - TimeUnit.HOURS.toMillis(hours) - now
            if (delay > 0) {
                val identity = "${row.courseId}|${row.classId}|${row.title}|$hours"
                val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
                val request = OneTimeWorkRequestBuilder<HomeworkReminderWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .setInputData(Data.Builder().putString("title", row.title).putString("course", row.courseName)
                        .putInt("id", digest.take(8).toLong(16).toInt()).build()).addTag(REMINDER_TAG).build()
                manager.enqueueUniqueWork("chaoxing_reminder_$digest", ExistingWorkPolicy.REPLACE, request)
            }
        }
    }
}

class HomeworkReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("homework_deadline", "作业截止提醒", NotificationManager.IMPORTANCE_DEFAULT))
        val notification = NotificationCompat.Builder(applicationContext, "homework_deadline")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("学习通作业即将截止")
            .setContentText("${inputData.getString("course").orEmpty()}：${inputData.getString("title").orEmpty()}")
            .setAutoCancel(true).build()
        manager.notify(inputData.getInt("id", 0), notification)
        return Result.success()
    }
}

/** 列表同步和详情会进入同一课程会话，必须串行，避免签名与 Cookie 轮换互相穿插。 */
suspend fun fetchHomeworkDetail(row: CachedHomework, store: HomeworkStore, generation: Long): String = sessionGate.withSession {
    check(store.generation == generation) { "学习通账号已变化，请重新打开详情" }
    withContext(Dispatchers.IO) { ChaoxingClient().detail(row) }
}
