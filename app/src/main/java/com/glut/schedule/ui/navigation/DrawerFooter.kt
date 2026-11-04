package com.glut.schedule.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glut.schedule.data.settings.CampusType

/** 仅描述缓存身份，不对登录有效性作承诺；预留未来个人中心回调。 */
data class DrawerIdentity(val source: String, val name: String, val onOpen: (() -> Unit)? = null)

/** 常见复姓保留完整；缺失身份不把占位文案当成姓氏。 */
fun drawerSurname(name: String): String {
    val value = name.trim().takeUnless { it == "暂无身份信息" }.orEmpty()
    val compound = listOf("欧阳", "司马", "上官", "诸葛", "东方", "皇甫", "尉迟", "公孙", "慕容", "司徒", "司空", "夏侯")
    return compound.firstOrNull { value.startsWith(it) } ?: value.take(1).ifBlank { "?" }
}
fun drawerIdentity(campus: CampusType, sessionBase: String, name: String): DrawerIdentity {
    val host = runCatching { java.net.URI(sessionBase).host }.getOrNull()
    val matches = when(campus) {
        CampusType.GUILIN -> host == "jw.glut.edu.cn"
        CampusType.NANNING -> host == "jw.glutnn.cn"
        CampusType.GRADUATE -> host == "gsems.glut.edu.cn"
    }
    return DrawerIdentity(when(campus) {
        CampusType.GUILIN -> "桂林本科"; CampusType.NANNING -> "南宁本科"; CampusType.GRADUATE -> "研究生"
    }, if(matches) name.trim().ifBlank { "暂无身份信息" } else "暂无身份信息")
}

/** 独立于中部滚动容器，始终可以找到设置和帮助。 */
@Composable
fun DrawerFooter(identity: DrawerIdentity, selected: DrawerItem, onSelect: (DrawerItem) -> Unit) {
    HorizontalDivider(color = Color(0xFFE4E2DD))
    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        // 身份在左侧，只显示姓；设置、帮助固定在右侧。
        Box(Modifier.size(44.dp).background(Color(0xFFE3ECFD), CircleShape), contentAlignment = Alignment.Center) {
            Text(drawerSurname(identity.name), color = Color(0xFF2459B8))
        }
        Spacer(Modifier.weight(1f))
        listOf(DrawerItem.Settings to "设置", DrawerItem.FAQ to "帮助").forEach { (item, label) ->
            TextButton(onClick = { onSelect(item) }, modifier = Modifier.width(48.dp).heightIn(min = 56.dp),
                contentPadding = PaddingValues(2.dp), colors = ButtonDefaults.textButtonColors(
                    contentColor = if(selected == item) Color(0xFF2459B8) else Color(0xFF667085),
                    containerColor = if(selected == item) Color(0xFFE3ECFD) else Color.Transparent)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(item.icon, null, Modifier.size(22.dp))
                    Text(label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
