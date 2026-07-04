package com.glut.schedule.ui.pages

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glut.schedule.data.model.NoticeInfo

@Composable
fun NoticeScreen(
    notices: List<NoticeInfo>,
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current

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
                onOpenUrl = {
                    if (notice.url.isNotBlank()) {
                        uriHandler.openUri(notice.url)
                    }
                }
            )
        }
    }
}

@Composable
private fun NoticeItem(
    notice: NoticeInfo,
    onOpenUrl: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
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
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = notice.level,
                    color = levelColor(notice.level),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
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
                    text = notice.content,
                    color = Color(0xFF4A5568),
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            }
            if (notice.url.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = Color(0xFFEDE8DE))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenUrl)
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "查看详情",
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

private fun levelColor(level: String): Color {
    return when (level.lowercase()) {
        "warning" -> Color(0xFFD97706)
        "update" -> Color(0xFF3F7DF6)
        "important" -> Color(0xFFDC2626)
        else -> Color(0xFF667085)
    }
}
