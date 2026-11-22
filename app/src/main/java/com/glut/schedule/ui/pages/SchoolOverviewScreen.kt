package com.glut.schedule.ui.pages

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glut.schedule.service.campus.SchoolHistoryPeriod
import com.glut.schedule.service.campus.SchoolLeader
import com.glut.schedule.service.campus.SchoolOverviewDocument
import com.glut.schedule.service.campus.SchoolOverviewTopic
import com.glut.schedule.service.campus.SchoolSpirit
import com.glut.schedule.ui.navigation.SchoolOverviewDestination
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val SchoolOverviewBackground = Color(0xFFFBF8F1)
internal val SchoolOverviewNavy = Color(0xFF153D65)
private val OverviewText = Color(0xFF232B33)
private val OverviewSecondary = Color(0xFF566370)
private val OverviewSurface = Color(0xFFFFFDF8)
private val OverviewHighlight = Color(0xFFF0EBDE)
private val OverviewDivider = Color(0xFFDED7CB)
private val OverviewSoftBlue = Color(0xFFE2EBF3)
private val OverviewWarning = Color(0xFF854C20)
private val FirstYear = Regex("\\d{4}")

private val documentDestinations = SchoolOverviewDestination.entries.filter { it.topic != null }

@Composable
internal fun SchoolOverviewScreen(
    destination: SchoolOverviewDestination,
    onDestinationSelected: (SchoolOverviewDestination) -> Unit,
    onSchoolDocumentsSelected: () -> Unit,
    content: @Composable () -> Unit
) {
    val showsDocuments = destination.topic != null
    Column(Modifier.fillMaxSize().background(SchoolOverviewBackground)) {
        if (showsDocuments) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(14.dp)).background(OverviewHighlight)
                    .padding(4.dp).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                documentDestinations.forEach { tab ->
                    val selected = tab == destination
                    Box(
                        Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (selected) Color.White else Color.Transparent)
                            .selectable(selected, role = Role.Tab, onClick = { onDestinationSelected(tab) })
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(tab.label, color = if (selected) SchoolOverviewNavy else OverviewSecondary,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center)
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) { content() }
        HorizontalDivider(color = OverviewDivider)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(OverviewSurface)
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OverviewGroupTab("学校资料", showsDocuments, Modifier.weight(1f), onSchoolDocumentsSelected)
            OverviewGroupTab("校园信息", !showsDocuments, Modifier.weight(1f)) {
                onDestinationSelected(SchoolOverviewDestination.CAMPUS_INFO)
            }
        }
    }
}

@Composable
private fun OverviewGroupTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) SchoolOverviewNavy else OverviewSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier
                .background(if (selected) OverviewSoftBlue else Color.Transparent, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 7.dp),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
internal fun SchoolOverviewTopicContent(
    topic: SchoolOverviewTopic,
    document: SchoolOverviewDocument?,
    loadState: SchoolOverviewLoadState,
    onRetry: () -> Unit,
    loadImage: suspend (String) -> ByteArray?
) {
    if (document == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            if (loadState == SchoolOverviewLoadState.FAILED) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${topic.title}暂时无法加载", color = OverviewText, textAlign = TextAlign.Center)
                    TextButton(onClick = onRetry) { Text("重试", color = SchoolOverviewNavy) }
                }
            } else {
                CircularProgressIndicator(color = SchoolOverviewNavy)
            }
        }
        return
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 使用资料 ID 保存展开项和 LazyColumn 锚点，更新正文时保留阅读位置。
    var expandedLeaders by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
        if (loadState == SchoolOverviewLoadState.FAILED) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("刷新失败，正在显示上次保存的资料",
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                    color = OverviewWarning, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRetry) { Text("重试", color = SchoolOverviewNavy) }
            }
        }
        if (topic == SchoolOverviewTopic.HISTORY) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(document.periods, key = { _, period -> period.id }) { index, period ->
                    val selected = listState.firstVisibleItemIndex.coerceAtMost(document.periods.lastIndex) == index
                    val year = FirstYear.find(period.range)?.value ?: period.range
                    OverviewGroupTab(year, selected, Modifier) {
                        scope.launch { listState.animateScrollToItem(index) }
                    }
                }
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (topic) {
                SchoolOverviewTopic.LEADERS -> itemsIndexed(document.people, key = { _, person -> person.id }) { index, person ->
                    LeaderEntry(
                        person = person,
                        featured = index < 2,
                        expanded = person.id in expandedLeaders,
                        revision = document.fetchedAt,
                        loadImage = loadImage,
                        onToggle = {
                            expandedLeaders = ArrayList(expandedLeaders).apply {
                                if (!remove(person.id)) add(person.id)
                            }
                        }
                    )
                }
                SchoolOverviewTopic.HISTORY -> itemsIndexed(document.periods, key = { _, period -> period.id }) { _, period ->
                    HistoryPeriod(period)
                }
                SchoolOverviewTopic.SPIRIT -> document.spirit?.let { spirit ->
                    item(key = "spirit") { SpiritEntry(spirit, document.fetchedAt, loadImage) }
                    item(key = "motto") { MottoEntry(spirit) }
                }
            }
            item(key = "school-overview-sources") { OverviewSources(document) }
        }
    }
}

@Composable
private fun LeaderEntry(
    person: SchoolLeader,
    featured: Boolean,
    expanded: Boolean,
    revision: Long,
    loadImage: suspend (String) -> ByteArray?,
    onToggle: () -> Unit
) {
    val canExpand = person.duties.isNotBlank()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = if (featured) OverviewHighlight else OverviewSurface
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().then(
                    if (canExpand) Modifier.clickable(role = Role.Button, onClick = onToggle)
                        .semantics { stateDescription = if (expanded) "已展开分工" else "已收起分工" }
                    else Modifier
                ).padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OverviewPhoto(
                    person.photoUrl, revision, loadImage,
                    modifier = Modifier.width(if (featured) 74.dp else 54.dp).aspectRatio(0.76f)
                        .clip(RoundedCornerShape(9.dp)).background(OverviewSoftBlue),
                    contentScale = ContentScale.Crop,
                    maxPixels = 500_000,
                    description = null
                ) {
                    Icon(Icons.Outlined.PersonOutline, null, tint = SchoolOverviewNavy, modifier = Modifier.size(26.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        person.name, color = SchoolOverviewNavy,
                        style = if (featured) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(person.role, color = OverviewSecondary, style = MaterialTheme.typography.bodyMedium)
                }
                if (canExpand) {
                    Icon(
                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        tint = SchoolOverviewNavy
                    )
                }
            }
            if (expanded && canExpand) {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                    HorizontalDivider(color = OverviewDivider)
                    Text(
                        person.duties,
                        modifier = Modifier.padding(top = 10.dp),
                        color = OverviewText,
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 23.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryPeriod(period: SchoolHistoryPeriod) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp)) {
        Text(period.range, color = SchoolOverviewNavy, style = MaterialTheme.typography.headlineSmall,
            fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
        Text(
            period.title,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp).semantics { heading() },
            color = OverviewText,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        period.events.forEach { event ->
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                if (event.date.isNotBlank()) Text(event.date, modifier = Modifier.weight(0.28f),
                    color = SchoolOverviewNavy, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(event.text, modifier = Modifier.weight(1f), color = OverviewText,
                    style = MaterialTheme.typography.bodyMedium, lineHeight = 23.sp)
            }
        }
        HorizontalDivider(color = OverviewDivider)
    }
}

@Composable
private fun SpiritEntry(spirit: SchoolSpirit, revision: Long, loadImage: suspend (String) -> ByteArray?) {
    Surface(color = SchoolOverviewNavy, contentColor = Color.White, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth()) {
            if (spirit.photoUrl.isNotBlank()) {
                OverviewPhoto(
                    spirit.photoUrl, revision, loadImage,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1.9f),
                    contentScale = ContentScale.Crop,
                    maxPixels = 2_000_000,
                    description = "桂工精神石刻"
                )
            }
            Text(
                spirit.phrases.chunked(2).joinToString("\n") { it.joinToString("　") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge,
                lineHeight = 34.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
    Text(
        spirit.description, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
        color = OverviewText, style = MaterialTheme.typography.bodyLarge, lineHeight = 26.sp
    )
}

@Composable
private fun MottoEntry(spirit: SchoolSpirit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            HorizontalDivider(color = OverviewDivider, modifier = Modifier.padding(bottom = 6.dp))
            Text("校训", color = OverviewSecondary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { heading() })
            Text(spirit.motto, color = SchoolOverviewNavy, style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
            Text(spirit.mottoDescription, color = OverviewText, style = MaterialTheme.typography.bodyMedium, lineHeight = 23.sp)
    }
}

@Composable
private fun OverviewPhoto(
    url: String,
    revision: Long,
    loadImage: suspend (String) -> ByteArray?,
    modifier: Modifier,
    contentScale: ContentScale,
    maxPixels: Int,
    description: String?,
    placeholder: @Composable () -> Unit = {}
) {
    key(url) {
        val bitmap by produceState<Bitmap?>(null, revision) {
            // 重用校园图片降采样逻辑。刷新同一图片时保留已解码的画面，避免闪空。
            if (url.isNotBlank()) {
                val bytes = loadImage(url)
                val decoded = withContext(Dispatchers.Default) { bytes?.let { decodeCampusImage(it, maxPixels) } }
                if (decoded != null) value = decoded
            }
        }
        Box(modifier, contentAlignment = Alignment.Center) {
            val image = bitmap
            if (image == null) {
                placeholder()
            } else {
                Image(
                    bitmap = remember(image) { image.asImageBitmap() },
                    contentDescription = description,
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun OverviewSources(document: SchoolOverviewDocument) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val updated = remember(document.fetchedAt) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.SIMPLIFIED_CHINESE)
            .format(Date(document.fetchedAt))
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        HorizontalDivider(color = OverviewDivider)
        Text("资料来源 · $updated", color = OverviewSecondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 14.dp))
        for (source in document.sources) {
            TextButton(onClick = {
                runCatching { uriHandler.openUri(source.url) }.onFailure {
                    Toast.makeText(context, "暂时无法打开资料来源", Toast.LENGTH_SHORT).show()
                }
            }) {
                Text(source.label, color = SchoolOverviewNavy, modifier = Modifier.padding(end = 6.dp))
                Icon(Icons.Outlined.OpenInNew, null, tint = SchoolOverviewNavy, modifier = Modifier.size(14.dp))
            }
        }
    }
}
