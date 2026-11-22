package com.glut.schedule.ui.requirements

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.glut.schedule.service.chaoxing.CachedHomework
import com.glut.schedule.service.chaoxing.HomeworkDetailException
import com.glut.schedule.service.chaoxing.HomeworkDocument
import com.glut.schedule.service.chaoxing.HomeworkStore
import com.glut.schedule.service.chaoxing.fetchHomeworkDocument
import com.glut.schedule.service.chaoxing.openChaoxingHomeworkList
import com.glut.schedule.service.chaoxing.openChaoxingHomeworkSource
import com.glut.schedule.service.chaoxing.subjectKey
import com.glut.schedule.service.chaoxing.taskKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun HomeworkDocumentActions(row: CachedHomework, store: HomeworkStore, documents: List<HomeworkDocument>) {
    val context = LocalContext.current
    val generation = store.generation
    var requested by remember(row.taskKey(), generation) { mutableStateOf<HomeworkDocument?>(null) }
    var preview by remember(row.taskKey(), generation) { mutableStateOf<Pair<String, File>?>(null) }
    var openSource by remember(row.taskKey(), generation) { mutableStateOf(false) }
    var sourceFailed by remember(row.taskKey(), generation) { mutableStateOf(false) }
    var error by remember(row.taskKey(), generation) { mutableStateOf<String?>(null) }

    fun currentSession() = store.connected && store.generation == generation && store.allows(row.subjectKey())
    val document = requested
    LaunchedEffect(document, generation) {
        if (document == null) return@LaunchedEffect
        try {
            val file = fetchHomeworkDocument(context, row, store, generation, document)
            // 下载期间退出或变更课程范围时，避免把上一会话的文件交给查看器。
            if (!currentSession()) {
                withContext(NonCancellable + Dispatchers.IO) { file.parentFile?.deleteRecursively() }
                return@LaunchedEffect
            }
            preview = document.name to file
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: HomeworkDetailException) {
            error = failure.message
        } catch (_: Exception) {
            error = "暂时无法打开附件，请重试或在学习通查看。"
        } finally {
            if (requested == document) requested = null
        }
    }
    LaunchedEffect(openSource, generation) {
        if (!openSource) return@LaunchedEffect
        try {
            error = openChaoxingHomeworkSource(context, row, store, generation)
            sourceFailed = error != null
        } finally {
            openSource = false
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Text("附件", style = MaterialTheme.typography.titleSmall)
        documents.forEach { item ->
            TextButton(
                onClick = {
                    error = null
                    sourceFailed = false
                    if (item.downloadAllowed) requested = item else openSource = true
                },
                enabled = requested == null && !openSource && currentSession()
            ) {
                Text(if (item.downloadAllowed) item.name else "${item.name}（在学习通查看）")
            }
        }
        if (document != null || openSource) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            TextButton(enabled = requested == null && !openSource && currentSession(), onClick = {
                if (sourceFailed) error = openChaoxingHomeworkList(context) else openSource = true
            }) { Text(if (sourceFailed) "打开学习通作业列表" else "在学习通查看") }
        }
    }
    preview?.let { (name, file) ->
        LaunchedEffect(file) {
            // 关闭预览或离开详情后，释放已经下载的本地 PDF。
            try { awaitCancellation() } finally {
                withContext(NonCancellable + Dispatchers.IO) { file.parentFile?.deleteRecursively() }
            }
        }
        HomeworkPdfPreview(
            name, file,
            onDismiss = { preview = null },
            onOpenSource = {
                preview = null
                if (currentSession()) openSource = true
            }
        )
    }
}
