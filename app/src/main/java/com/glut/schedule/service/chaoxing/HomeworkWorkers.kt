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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private val sessionGate = HomeworkSessionGate()
private const val REMINDER_TAG = "chaoxing_reminders"
internal const val CHAOXING_SCOPE_SYNC_TAG = "chaoxing_scope_sync"

class HomeworkSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val targetKeys = inputData.getStringArray("courses")?.toSet()
        val failedOnly = inputData.getBoolean("failedOnly", false)
        suspend fun runSync(): Result =
        // 详情占用会话时等待；只有另一同步已认领名额时，才复用已有同步。
        withContext(Dispatchers.IO) {
            val store = HomeworkStore(applicationContext)
            if (!store.connected) return@withContext Result.success()
            val failed = if (failedOnly) store.issues().map { it.course.subjectKey() to it.kind }.toSet() else null
            store.markAttempt()
            try {
                val result = ChaoxingClient().sync { course, kind ->
                    val key = course.subjectKey()
                    store.allows(key) && (targetKeys == null || key in targetKeys) && (failed == null || (key to kind) in failed)
                }
                store.merge(result)
                scheduleHomeworkReminders(applicationContext, store.read())
                Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (_: ChaoxingLoginRequiredException) {
                store.saveError("学习通登录已失效，请重新登录")
                Result.failure()
            } catch (_: Exception) {
                store.saveError("同步失败，请检查网络或重新登录学习通")
                Result.failure()
            } finally {
                store.finishDetailRefresh()
            }
        }
        return if (targetKeys != null || failedOnly) sessionGate.queuedSync { runSync() }
        else sessionGate.sync { runSync() } ?: Result.success()
    }
}

fun scheduleHomeworkSync(context: Context) {
    WorkManager.getInstance(context).cancelUniqueWork("chaoxing_sync")
}

/** 与手动同步、详情共用会话门卫，后台不建立任何交互登录。 */
suspend fun refreshHomeworkForUnified(context: Context): Boolean = sessionGate.sync {
    val store = HomeworkStore(context)
    if (!store.connected) throw com.glut.schedule.service.refresh.ScheduledRefreshLoginRequiredException()
    val generation = store.generation
    val result = coroutineScope {
        val client = ChaoxingClient()
        // 同步方法在 IO 线程阻塞时，由独立子协程把后台超时传递给在途 OkHttp Call。
        val cancellationWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { client.cancel() }
        }
        try {
            withContext(Dispatchers.IO) { client.sync { course, _ -> store.allows(course.subjectKey()) } }
        } catch (_: ChaoxingLoginRequiredException) {
            store.saveError("学习通登录已失效，请重新登录")
            throw com.glut.schedule.service.refresh.ScheduledRefreshLoginRequiredException()
        } finally {
            withContext(NonCancellable) { cancellationWatcher.cancelAndJoin() }
        }
    }
    if (!store.mergeIfGeneration(result, generation)) return@sync false
    if (store.generation == generation) scheduleHomeworkReminders(context, store.read())
    result.failed.isEmpty()
} ?: false

fun refreshHomework(context: Context) {
    WorkManager.getInstance(context).enqueueUniqueWork("chaoxing_manual", ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<HomeworkSyncWorker>().build())
}

/** 重试只涉及失败的任务类型；改选后的补拉只涉及刚加入的课程。 */
fun refreshSelectedHomework(context: Context, courses: Set<String>? = null, failedOnly: Boolean = false) {
    val data = Data.Builder().putBoolean("failedOnly", failedOnly)
    courses?.let { data.putStringArray("courses", it.toTypedArray()) }
    // 会话门卫负责排队，单独入队避免上一批失败时 WorkManager 一并取消补拉。
    WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<HomeworkSyncWorker>()
        .setInputData(data.build()).addTag(CHAOXING_SCOPE_SYNC_TAG).build())
}

/** 只清理学习通域名，不调用会影响教务登录的全局 Cookie 清理。 */
suspend fun disconnectChaoxing(context: Context) = sessionGate.withSession {
    withContext(Dispatchers.IO) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork("chaoxing_sync").result.get()
        manager.cancelUniqueWork("chaoxing_manual").result.get()
        manager.cancelAllWorkByTag(CHAOXING_SCOPE_SYNC_TAG).result.get()
        manager.cancelAllWorkByTag(REMINDER_TAG).result.get()
        homeworkAccesses.clear()
        HomeworkStore(context).clear()
        val cookieManager = android.webkit.CookieManager.getInstance()
        listOf("passport2.chaoxing.com", "i.mooc.chaoxing.com", "mooc1.chaoxing.com", "mooc1-api.chaoxing.com", "mooc2-ans.chaoxing.com", "groupweb.chaoxing.com").forEach { host ->
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

@Synchronized
fun scheduleHomeworkReminders(context: Context, rows: List<CachedHomework>) {
    val manager = WorkManager.getInstance(context)
    // 等待取消完成，避免异步取消误删刚安排的新提醒。
    manager.cancelAllWorkByTag(REMINDER_TAG).result.get()
    val now = System.currentTimeMillis()
    val store = HomeworkStore(context)
    rows.filter { store.allows(it.subjectKey()) && homeworkState(it.status) == HomeworkState.TODO && it.deadline != null }.forEach { row ->
        listOf(24L, 1L).forEach { hours ->
            val delay = row.deadline!! - TimeUnit.HOURS.toMillis(hours) - now
            if (delay > 0) {
                val identity = "${row.taskKey()}|$hours"
                val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
                val request = OneTimeWorkRequestBuilder<HomeworkReminderWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .setInputData(Data.Builder().putString("title", row.title).putString("course", row.courseName)
                        .putString("subjectKey", row.subjectKey())
                        .putInt("id", digest.take(8).toLong(16).toInt()).build()).addTag(REMINDER_TAG).build()
                manager.enqueueUniqueWork("chaoxing_reminder_$digest", ExistingWorkPolicy.REPLACE, request)
            }
        }
    }
}

class HomeworkReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = HomeworkStore(applicationContext)
        if (!store.connected || inputData.getString("subjectKey")?.let { !store.allows(it) } == true) return Result.success()
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
    check(store.allows(row.subjectKey())) { "该课程已移出同步范围" }
    withContext(Dispatchers.IO) { ChaoxingClient().detail(row) }
}

/** 与同步、详情串行解析，避免课程签名和账号会话在跳转准备时互相覆盖。 */
suspend fun resolveHomeworkAppEntry(row: CachedHomework, store: HomeworkStore, generation: Long): String = sessionGate.withSession {
    check(store.generation == generation)
    withContext(Dispatchers.IO) { ChaoxingClient().homeworkAppEntry(row) }.also {
        check(store.generation == generation)
    }
}
