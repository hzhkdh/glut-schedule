package com.glut.schedule.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.service.academic.*
import com.glut.schedule.ui.navigation.drawerSurname
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 只读资料页：全部详细资料仅驻留页面内存，切换身份或离开页面即销毁。 */
@Composable
fun PersonalCenterScreen(campus: CampusType, store: AcademicSessionStore, refresh: Int, onLoading: (Boolean) -> Unit) {
    val session by store.profileSession.collectAsStateWithLifecycle(initialValue = AcademicProfileSession())
    val name = session.name
    val number = session.studentNo
    val base = session.base
    val cookie = session.cookie
    val owner = "$campus|$number|$base"
    var values by remember(owner) { mutableStateOf<Map<String,String>>(emptyMap()) }
    var revealed by remember(owner) { mutableStateOf(setOf<String>()) }
    var expanded by remember(owner) { mutableStateOf(false) }
    var message by remember(owner) { mutableStateOf("") }
    var updated by remember(owner) { mutableStateOf("") }
    var loading by remember(owner) { mutableStateOf(false) }
    // 各条会话流首次发出值后再读取一次快照；不因 Cookie 轮换循环刷新。
    LaunchedEffect(owner, name, refresh, cookie.isNotBlank()) {
        revealed = emptySet()
        val expectedHost = when(campus) { CampusType.GUILIN -> "jw.glut.edu.cn"; CampusType.NANNING -> "jw.glutnn.cn"; CampusType.GRADUATE -> "gsems.glut.edu.cn" }
        val matches = runCatching { java.net.URI(base).host == expectedHost }.getOrDefault(false)
        if (campus == CampusType.GRADUATE) {
            // 仅使用已导入的身份；未验证研究生个人资料协议，不猜测字段。
            values = if(matches) mapOf("name" to name, "studentNo" to number) else emptyMap()
            message = "研究生详细资料暂未开放，当前仅展示已导入的基础身份"
            return@LaunchedEffect
        }
        if (!matches || cookie.isBlank()) { message = "请先在导入页面登录对应教务系统"; return@LaunchedEffect }
        val requestCookie = cookie
        val expectedNumber = number
        loading = true; onLoading(true); message = ""
        try {
            val endpoint = if(campus == CampusType.GUILIN) "https://jw.glut.edu.cn/academic/showPersonalInfo.do"
                else "http://jw.glutnn.cn/academic/student/studentinfo/studentInfoModifyIndex.do?frombase=0&wantTag=0&moduleId=2060"
            val result = ApiProbeService().probeUrl(requestCookie, endpoint) ?: error("network")
            ensureActive()
            require(result.httpCode == 200) { "个人资料暂时无法获取，请重新登录后重试" }
            val parsed = PersonalCenterParser.parse(result.body)
            require(expectedNumber.isBlank() || parsed["studentNo"] == expectedNumber) { "教务身份已变化，请重新登录" }
            // 再次校验拥有者，阻止导入页在请求期间换号后的旧结果写回。
            val latest = store.profileSession.first()
            if(latest.studentNo != expectedNumber || latest.base != base || latest.cookie != requestCookie) return@LaunchedEffect
            ensureActive()
            values = parsed
            updated = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date())
            store.saveProfileCookieIfCurrent(requestCookie, expectedNumber, base, result.updatedCookie)
        } catch(e: CancellationException) { throw e
        } catch(_: Exception) { message = "个人资料暂时无法获取，请重新登录或稍后重试" }
        finally { loading = false; onLoading(false) }
    }
    DisposableEffect(Unit) { onDispose { onLoading(false) } }
    Column(Modifier.fillMaxSize().background(Color(0xFFF6F4EF)).verticalScroll(rememberScrollState()).padding(16.dp)) {
        if(loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(54.dp).background(Color(0xFFE3ECFD), CircleShape), contentAlignment = Alignment.Center) {
                Text(drawerSurname(values["name"].orEmpty()), color = Color(0xFF2459B8), style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(values["name"].orEmpty().ifBlank { "个人中心" }, style = MaterialTheme.typography.titleLarge)
                Text(when(campus) { CampusType.GUILIN -> "桂林本科"; CampusType.NANNING -> "南宁本科"; CampusType.GRADUATE -> "研究生" }, color = Color(0xFF2459B8))
                if(updated.isNotBlank()) Text("更新于 $updated", style = MaterialTheme.typography.bodySmall, color = Color(0xFF667085))
            }
        }
        if(message.isNotBlank()) Text(message, color = Color(0xFF925311), modifier = Modifier.padding(vertical = 12.dp))
        listOf("academic" to "学籍资料", "contact" to "联系与证件", "more" to "更多资料").forEach { (group, title) ->
            val fields = PersonalCenterParser.fields.filter { it.group == group && (group == "academic" || values.containsKey(it.key)) }
            if(fields.isNotEmpty()) {
                if(group == "more") TextButton(onClick = { expanded = !expanded }) { Text(if(expanded) "更多资料 · 收起" else "更多资料 · 展开") }
                else Text(title, color = Color(0xFF3F7DF6), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 12.dp))
                if(group != "more" || expanded) Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFFFFFFFC)) {
                    Column(Modifier.padding(horizontal = 12.dp)) {
                        fields.forEachIndexed { index, field ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(field.label, color = Color(0xFF667085), modifier = Modifier.widthIn(min = 76.dp, max = 96.dp), style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.width(12.dp))
                                val value = values[field.key].orEmpty()
                                Text(if(value.isBlank()) "未提供" else if(field.sensitive && field.key !in revealed) PersonalCenterParser.mask(value, field.key) else value,
                                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                if(field.sensitive && value.isNotBlank()) TextButton(onClick = { revealed = if(field.key in revealed) revealed-field.key else revealed+field.key }, contentPadding = PaddingValues(4.dp)) {
                                    Text(if(field.key in revealed) "隐藏" else "显示", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            if(index < fields.lastIndex) HorizontalDivider(color = Color(0xFFE4E2DD))
                        }
                    }
                }
            }
        }
        Text("资料来自教务系统，仅供查看", color = Color(0xFF667085), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 24.dp))
    }
}
