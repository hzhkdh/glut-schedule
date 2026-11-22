package com.glut.schedule.ui.requirements

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun HomeworkPdfPreview(
    name: String, file: File, onDismiss: () -> Unit, onOpenSource: () -> Unit
) {
    val pageCount by produceState<Result<Int>?>(null, file) {
        value = readPdf { file.withPdfRenderer { it.pageCount.also { count -> check(count > 0) } } }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    pageCount?.getOrNull()?.let { Text("共 $it 页", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall) }
                        ?: Spacer(Modifier.weight(1f))
                    TextButton(onClick = onOpenSource) { Text("在学习通查看") }
                }
                HorizontalDivider()
                val pages = pageCount
                when {
                    pages == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    pages.isFailure -> Text("此 PDF 无法在应用内预览。", Modifier.padding(16.dp))
                    else -> LazyColumn(
                        Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) { items(pages.getOrThrow()) { index -> HomeworkPdfPage(file, index) } }
                }
            }
        }
    }
}

@Composable
private fun HomeworkPdfPage(file: File, index: Int) {
    val page by produceState<Result<Bitmap>?>(null, file, index) {
        // 按可见页在 IO 线程渲染，限制位图尺寸，避免长文档一次占用大量内存。
        value = readPdf {
            file.withPdfRenderer { renderer ->
                renderer.openPage(index).use { page ->
                    val scale = minOf(1120f / page.width, 1600f / page.height)
                    val width = (page.width * scale).toInt().coerceAtLeast(1)
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(Color.WHITE)
                        page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }
    val bitmap = page?.getOrNull()
    when {
        bitmap != null -> Image(bitmap.asImageBitmap(), "PDF 第 ${index + 1} 页",
            Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height))
        page == null -> Box(Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp))
        }
        else -> Text("第 ${index + 1} 页无法预览", Modifier.padding(16.dp))
    }
}

private suspend fun <T> readPdf(block: () -> T): Result<T> = try {
    Result.success(withContext(Dispatchers.IO) { block() })
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}

private inline fun <T> File.withPdfRenderer(block: (PdfRenderer) -> T): T {
    val descriptor = ParcelFileDescriptor.open(this, ParcelFileDescriptor.MODE_READ_ONLY)
    val renderer = try {
        PdfRenderer(descriptor)
    } catch (error: Exception) {
        descriptor.close()
        throw error
    }
    // PdfRenderer 构造成功后负责关闭文件描述符。
    return renderer.use(block)
}
