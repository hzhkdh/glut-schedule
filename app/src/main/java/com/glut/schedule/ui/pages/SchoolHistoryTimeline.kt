package com.glut.schedule.ui.pages

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.glut.schedule.service.campus.SchoolHistoryEvent
import com.glut.schedule.service.campus.SchoolHistoryPeriod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val TimelineLine = Color(0xFFABC0D3)
private val TimelineGutter = 26.dp

internal fun shouldStackHistoryEvent(eventWidthDp: Float, dateWidthDp: Float, fontScale: Float): Boolean =
    fontScale >= 1.2f || eventWidthDp - dateWidthDp < 160f

@Composable
internal fun historyDateWidth(periods: List<SchoolHistoryPeriod>): Dp {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
    // 根据本次资料中最长的日期留出宽度，使正文对齐，日期保持在一行。
    val width = remember(periods, measurer, style) {
        periods.maxOfOrNull { period ->
            period.events.maxOfOrNull { event ->
                measurer.measure(event.date, style, softWrap = false, maxLines = 1).size.width
            } ?: 0
        } ?: 0
    }
    return with(density) { width.toDp() }
}

internal fun LazyListScope.schoolHistoryTimeline(periods: List<SchoolHistoryPeriod>, dateWidth: Dp) {
    periods.forEachIndexed { index, period ->
        val first = index == 0
        val last = index == periods.lastIndex
        item(key = "history-period:${period.id}") {
            HistoryPeriodNode(period, first, last && period.events.isEmpty())
        }
        period.events.forEachIndexed { ordinal, event ->
            item(key = "history-event:${period.id}:$ordinal") {
                HistoryEventNode(event, dateWidth, last && ordinal == period.events.lastIndex)
            }
        }
    }
}

@Composable
private fun HistoryPeriodNode(period: SchoolHistoryPeriod, first: Boolean, last: Boolean) {
    val top = if (first) 4.dp else 20.dp
    Column(Modifier.fillMaxWidth().timelineNode(top + 15.dp, isPeriod = true, first = first, last = last)
        .padding(start = TimelineGutter, top = top, bottom = 10.dp)) {
        Text(period.range, color = SchoolOverviewNavy, style = MaterialTheme.typography.headlineSmall,
            fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
        Text(period.title, color = OverviewText, style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp).semantics { heading() })
    }
}

@Composable
private fun HistoryEventNode(event: SchoolHistoryEvent, dateWidth: Dp, last: Boolean) {
    val density = LocalDensity.current
    val markerY = 4.dp + with(density) { 23.sp.toDp() } / 2
    BoxWithConstraints(Modifier.fillMaxWidth().timelineNode(markerY, isPeriod = false, first = false, last = last)
        .padding(start = TimelineGutter, top = 4.dp, bottom = 11.dp)) {
        val dateText: @Composable (Modifier) -> Unit = { modifier ->
            Text(event.date, modifier = modifier, color = SchoolOverviewNavy,
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                lineHeight = 23.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
        val bodyText: @Composable (Modifier) -> Unit = { modifier ->
            Text(event.text, modifier = modifier, color = OverviewText,
                style = MaterialTheme.typography.bodyMedium, lineHeight = 23.sp)
        }
        when {
            event.date.isBlank() -> bodyText(Modifier)
            shouldStackHistoryEvent(maxWidth.value, dateWidth.value, density.fontScale) ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    dateText(Modifier.fillMaxWidth())
                    bodyText(Modifier)
                }
            else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                dateText(Modifier.width(dateWidth))
                bodyText(Modifier.weight(1f))
            }
        }
    }
}

private fun Modifier.timelineNode(markerY: Dp, isPeriod: Boolean, first: Boolean, last: Boolean) = drawBehind {
    val x = 8.dp.toPx()
    val y = markerY.toPx().coerceAtMost(size.height)
    // 每行只绘制自身高度内的线段，滚动复用时仍连续，最后一个事件在圆点中心结束。
    drawLine(TimelineLine, Offset(x, if (first) y else 0f), Offset(x, if (last) y else size.height), 2.dp.toPx())
    if (isPeriod) drawCircle(TimelineLine, radius = 8.dp.toPx(), center = Offset(x, y))
    drawCircle(SchoolOverviewNavy, radius = (if (isPeriod) 5.dp else 3.5.dp).toPx(), center = Offset(x, y))
}

private data class HistoryDiagramState(val bitmap: Bitmap? = null, val failed: Boolean = false)

@Composable
internal fun SchoolHistoryDiagram(url: String, revision: Long, loadImage: suspend (String) -> ByteArray?) {
    key(url) {
        var attempt by remember { mutableIntStateOf(0) }
        var previewOpen by rememberSaveable { mutableStateOf(false) }
        val diagram by produceState(HistoryDiagramState(), revision, attempt) {
            // 与条目图片共用磁盘缓存和后台解码，缩略图与预览共用同一张位图。
            val bytes = loadImage(url)
            val bitmap = withContext(Dispatchers.Default) { bytes?.let(::decodeCampusImageForDiagram) }
            if (bitmap != null || value.bitmap == null) value = HistoryDiagramState(bitmap, bitmap == null)
        }
        val bitmap = diagram.bitmap
        val shape = RoundedCornerShape(16.dp)
        Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("沿革图", color = OverviewText, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            if (bitmap != null) {
                Column(Modifier.fillMaxWidth().clip(shape).background(OverviewSurface)
                    .clickable(role = Role.Button) { previewOpen = true }) {
                    Image(remember(bitmap) { bitmap.asImageBitmap() }, contentDescription = null,
                        modifier = Modifier.fillMaxWidth().aspectRatio(2.6f).padding(horizontal = 8.dp, vertical = 6.dp),
                        contentScale = ContentScale.Fit)
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.OpenInFull, null, tint = SchoolOverviewNavy, modifier = Modifier.size(18.dp))
                        Text("查看沿革图", color = SchoolOverviewNavy, style = MaterialTheme.typography.labelLarge)
                    }
                }
                if (previewOpen) HistoryDiagramPreview(bitmap) { previewOpen = false }
            } else {
                Box(Modifier.fillMaxWidth().heightIn(min = 80.dp).clip(shape).background(OverviewSurface),
                    contentAlignment = Alignment.Center) {
                    if (diagram.failed) {
                        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("沿革图暂时无法加载", color = OverviewSecondary,
                                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                            TextButton(onClick = { attempt++ }) { Text("重试", color = SchoolOverviewNavy) }
                        }
                    } else {
                        CircularProgressIndicator(color = SchoolOverviewNavy, modifier = Modifier.size(28.dp))
                    }
                }
            }
        }
    }
}

private fun decodeCampusImageForDiagram(bytes: ByteArray) = decodeCampusImage(bytes)

@Composable
private fun HistoryDiagramPreview(bitmap: Bitmap, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Column(Modifier.fillMaxSize().background(Color(0xFF141821)).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("沿革图", color = Color.White, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() })
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "关闭沿革图", tint = Color.White) }
            }
            Image(
                bitmap = remember(bitmap) { bitmap.asImageBitmap() },
                contentDescription = "学校历史沿革图",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().clipToBounds().pointerInput(bitmap) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val oldScale = scale
                        scale = (oldScale * zoom).coerceIn(1f, 5f)
                        val origin = Offset(size.width / 2f, size.height / 2f)
                        val focal = centroid - origin
                        val moved = focal + (offset - focal) * (scale / oldScale) + pan
                        // 按实际显示尺寸限制拖动，让图像边缘始终留在预览内。
                        val fit = minOf(size.width.toFloat() / bitmap.width, size.height.toFloat() / bitmap.height)
                        val dx = ((bitmap.width * fit * scale - size.width) / 2f).coerceAtLeast(0f)
                        val dy = ((bitmap.height * fit * scale - size.height) / 2f).coerceAtLeast(0f)
                        offset = Offset(moved.x.coerceIn(-dx, dx), moved.y.coerceIn(-dy, dy))
                    }
                }.graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
            )
        }
    }
}
