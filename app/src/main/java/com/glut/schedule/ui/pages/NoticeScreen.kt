package com.glut.schedule.ui.pages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glut.schedule.data.model.NoticeAttachment
import com.glut.schedule.data.model.NoticeInfo
import com.glut.schedule.ui.components.MarkdownContent
import com.glut.schedule.ui.components.MarkdownPolicy

@Composable
fun NoticeScreen(
    notices: List<NoticeInfo>,
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current
    var selectedNotice by remember { mutableStateOf<NoticeInfo?>(null) }

    selectedNotice?.let { notice ->
        BackHandler { selectedNotice = null }
        NoticeDetail(
            notice = notice,
            onBack = { selectedNotice = null },
            onOpenUrl = { url -> if (MarkdownPolicy.isSafeHttpUrl(url)) uriHandler.openUri(url) },
            modifier = modifier
        )
        return
    }

    if (notices.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xFFF6F4EF)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("暂无通知", color = Color(0xFF667085), fontSize = 15.sp)
        }
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF6F4EF))
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(notices, key = { it.id }) { notice ->
            NoticeItem(
                notice = notice,
                onClick = { selectedNotice = notice }
            )
        }
    }
}

@Composable
private fun NoticeItem(
    notice: NoticeInfo,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = Color(0xFFFFFEFB),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = notice.title,
                    color = Color(0xFF141821),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                NoticeLevelBadge(level = notice.level)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = notice.publishedAt.toString(),
                color = Color(0xFF9CA3AF),
                fontSize = 12.sp
            )
            if (notice.content.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = MarkdownPolicy.toPlainText(notice.content),
                    color = Color(0xFF4A5568),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (notice.attachments.isNotEmpty() || notice.url.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (notice.attachments.isEmpty()) "查看详情" else "查看详情 · ${notice.attachments.size} 个附件",
                        color = Color(0xFF3F7DF6),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = Color(0xFF9CA3AF)
                    )
                }
            }
        }
    }
}

@Composable
private fun NoticeDetail(
    notice: NoticeInfo,
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize().background(Color(0xFFF6F4EF))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回公告列表")
            }
            Text("公告详情", color = Color(0xFF141821), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Surface(color = Color(0xFFFFFEFB), shape = RoundedCornerShape(14.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                notice.title,
                                color = Color(0xFF141821),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            NoticeLevelBadge(notice.level)
                        }
                        Text(notice.publishedAt.toString(), color = Color(0xFF9CA3AF), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                        if (notice.content.isNotBlank()) {
                            MarkdownContent(
                                markdown = notice.content,
                                modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
                            )
                        }
                    }
                }
            }
            if (notice.attachments.isNotEmpty()) {
                item {
                    Surface(color = Color(0xFFFFFEFB), shape = RoundedCornerShape(14.dp)) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text("附件", fontSize = 13.sp, color = Color(0xFF667085), fontWeight = FontWeight.Medium)
                            notice.attachments.forEach { attachment ->
                                AttachmentRow(attachment = attachment, onOpen = { onOpenUrl(attachment.url) })
                            }
                        }
                    }
                }
            }
            if (notice.url.isNotBlank() && MarkdownPolicy.isSafeHttpUrl(notice.url)) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { onOpenUrl(notice.url) },
                        color = Color(0xFFEAF1FF),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("打开公告原文", color = Color(0xFF3F7DF6), modifier = Modifier.padding(16.dp), fontWeight = FontWeight.Medium)
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun AttachmentRow(
    attachment: NoticeAttachment,
    onOpen: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.AttachFile,
            contentDescription = null,
            tint = Color(0xFF667085),
            modifier = Modifier.size(18.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = attachment.name,
                color = Color(0xFF2D3748),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val typeLabel = attachment.type.ifBlank { "附件" }
            Text(
                text = typeLabel.uppercase(),
                color = Color(0xFF9CA3AF),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = Color(0xFF9CA3AF)
        )
    }
}

@Composable
private fun NoticeLevelBadge(level: String) {
    val colors = noticeLevelColors(level)
    Text(
        text = noticeLevelLabel(level),
        color = colors.content,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(colors.container)
            .padding(horizontal = 9.dp, vertical = 4.dp)
    )
}

private data class NoticeLevelColors(
    val container: Color,
    val content: Color
)

private fun noticeLevelColors(level: String): NoticeLevelColors {
    return when (level.lowercase()) {
        "important" -> NoticeLevelColors(Color(0xFFFEE2E2), Color(0xFFDC2626))
        "warning" -> NoticeLevelColors(Color(0xFFFFF3D8), Color(0xFFD97706))
        "update" -> NoticeLevelColors(Color(0xFFEAF1FF), Color(0xFF3F7DF6))
        else -> NoticeLevelColors(Color(0xFFEEF2F7), Color(0xFF667085))
    }
}

internal fun noticeLevelLabel(level: String): String {
    return when (level.lowercase()) {
        "important" -> "重要"
        "warning" -> "提醒"
        "update" -> "更新"
        else -> "通知"
    }
}
