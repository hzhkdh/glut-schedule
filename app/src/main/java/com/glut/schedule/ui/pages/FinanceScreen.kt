package com.glut.schedule.ui.pages

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.util.Base64
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glut.schedule.data.model.FinanceField
import com.glut.schedule.data.model.FinanceGroup
import com.glut.schedule.data.model.FinanceItem
import com.glut.schedule.data.model.FinanceModule
import com.glut.schedule.data.model.FinanceOverview
import com.glut.schedule.data.model.FinancePayload
import com.glut.schedule.data.model.FinanceTableSection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val FinancePrimary = Color(0xFF244F46)
private val FinancePrimaryLight = Color(0xFF397267)
private val FinanceAmount = Color(0xFFBD573E)
private val FinancePageBg = Color(0xFFF5F1E9)
private val FinanceCard = Color(0xFFFFFEFB)
private val FinanceMuted = Color(0xFF737B78)
private val FinanceBorder = Color(0xFFE7E1D7)
private const val FINANCE_HOME_URL = "https://cwjf.glut.edu.cn/home/login"
private const val FINANCE_RESET_URL = "https://cwjf.glut.edu.cn/home/mmcz"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinanceScreen(
    viewModel: FinanceViewModel,
    modifier: Modifier = Modifier,
    onTableGestureActive: (Boolean) -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    val uriHandler = LocalUriHandler.current

    Column(modifier.fillMaxSize().background(FinancePageBg)) {
        if (state.isRefreshing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = FinancePrimary, trackColor = FinancePrimary.copy(alpha = .12f))
        }
        if (state.campusUnsupported) {
            UnsupportedFinance(modifier = Modifier.weight(1f))
        } else {
            FinanceTabs(state.group, viewModel::selectGroup)
            if (state.message.isNotBlank()) {
                Text(state.message, color = FinanceMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
            when (state.group) {
                FinanceGroup.OVERVIEW -> OverviewContent(
                    state.activePayload as? FinancePayload.Overview,
                    state.activeSavedAt,
                    viewModel::showLogin,
                    Modifier.weight(1f)
                )
                FinanceGroup.PAYMENT, FinanceGroup.RECORDS -> ModuleContent(
                    state = state,
                    onSelect = viewModel::selectModule,
                    onSelectItem = viewModel::selectItem,
                    onLoadMore = viewModel::loadMore,
                    onOpenWebsite = { uriHandler.openUri(FINANCE_HOME_URL) },
                    modifier = Modifier.weight(1f)
                )
                FinanceGroup.CREDIT -> CreditContent(
                    state.activePayload as? FinancePayload.Tables,
                    state.activeSavedAt,
                    onTableGestureActive,
                    Modifier.weight(1f)
                )
            }
        }
    }

    if (state.login.visible) {
        SecureFinanceWindow()
        FinanceLoginDialog(
            state = state,
            onUsername = viewModel::updateUsername,
            onPassword = viewModel::updatePassword,
            onCaptcha = viewModel::updateCaptcha,
            onTogglePassword = viewModel::togglePasswordVisibility,
            onRefreshCaptcha = viewModel::refreshCaptcha,
            onResetPassword = { uriHandler.openUri(FINANCE_RESET_URL) },
            onDismiss = viewModel::dismissLogin,
            onLogin = viewModel::login
        )
    }

    state.selectedItem?.let { item ->
        ModalBottomSheet(onDismissRequest = { viewModel.selectItem(null) }, containerColor = FinanceCard) {
            DetailSheet(item, state.ticketImage) { receipt -> viewModel.loadTicketImage(item, receipt) }
        }
    }
}

@Composable
private fun FinanceTabs(selected: FinanceGroup, onSelect: (FinanceGroup) -> Unit) {
    Row(Modifier.fillMaxWidth().background(FinanceCard).padding(horizontal = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        FinanceGroup.entries.forEach { group ->
            Column(
                Modifier.clickable { onSelect(group) }.padding(horizontal = 10.dp, vertical = 13.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(group.label, color = if (selected == group) FinancePrimary else Color(0xFF303432), fontWeight = if (selected == group) FontWeight.Bold else FontWeight.Normal)
                Spacer(Modifier.height(7.dp))
                Box(Modifier.height(3.dp).width(32.dp).background(if (selected == group) FinancePrimary else Color.Transparent))
            }
        }
    }
}

@Composable
private fun OverviewContent(payload: FinancePayload.Overview?, savedAt: Long, onLogin: () -> Unit, modifier: Modifier) {
    val overview = payload?.value
    if (overview == null) return EmptyFinance("登录后查看财务概览", "财务账号、密码和登录态与教务及体测完全独立。", onLogin, modifier)
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SummaryCard(overview) }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("待缴项目", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text(cacheTime(savedAt), color = FinanceMuted, fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }
        }
        if (overview.pendingItems.isEmpty()) item { EmptyCard("暂无待缴项目") }
        else items(overview.pendingItems, key = { it.id }) { PendingCard(it) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("必修费用", overview.summary.requiredFee, Modifier.weight(1f))
                MetricCard("选修费用", overview.summary.electiveFee, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SummaryCard(value: FinanceOverview) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(FinancePrimary, FinancePrimaryLight))).padding(22.dp)
    ) {
        Text("当前欠费", color = Color.White.copy(alpha = .75f), fontSize = 14.sp)
        Text("¥ ${money(value.summary.outstandingTotal)}", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 3.dp, bottom = 20.dp))
        Row(Modifier.fillMaxWidth()) {
            SummaryMetric("应收合计", value.summary.receivableTotal, Modifier.weight(1f))
            SummaryMetric("已缴合计", value.summary.paidTotal, Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            SummaryMetric("缓缴金额", value.summary.deferredAmount, Modifier.weight(1f))
            Column(Modifier.weight(1f)) {
                Text("待缴项目", color = Color.White.copy(alpha = .7f), fontSize = 12.sp)
                Text("${value.pendingItems.size} 项", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

@Composable private fun SummaryMetric(label: String, value: String, modifier: Modifier) = Column(modifier) {
    Text(label, color = Color.White.copy(alpha = .7f), fontSize = 12.sp)
    Text("¥ ${money(value)}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
}

@Composable
private fun PendingCard(item: FinanceItem) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = FinanceCard, border = androidx.compose.foundation.BorderStroke(1.dp, FinanceBorder)) {
        Column(Modifier.padding(18.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(item.name.ifBlank { "缴费项目" }, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("¥${money(item.outstanding.ifBlank { item.amount })}", color = FinanceAmount, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            if (item.secondary.isNotBlank()) Text(item.secondary, color = FinanceMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp))
            if (item.details.isNotEmpty()) FieldGrid(item.details, Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun ModuleContent(
    state: FinanceUiState,
    onSelect: (FinanceModule) -> Unit,
    onSelectItem: (FinanceItem?) -> Unit,
    onLoadMore: () -> Unit,
    onOpenWebsite: () -> Unit,
    modifier: Modifier
) {
    val modules = FinanceModule.entries.filter { it.group == state.group }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            modules.forEach { module ->
                Surface(
                    modifier = Modifier.clickable { onSelect(module) }, shape = RoundedCornerShape(18.dp),
                    color = if (state.module == module) FinancePrimary else FinanceCard
                ) { Text(module.label, color = if (state.module == module) Color.White else FinanceMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp)) }
            }
        }
        if (state.group == FinanceGroup.PAYMENT) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFFE5ECE8)).padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("自助缴费", fontWeight = FontWeight.Bold); Text("查询只读，支付请前往学校官网", color = FinanceMuted, fontSize = 11.sp) }
                TextButton(onClick = onOpenWebsite) { Text("打开官网", color = FinancePrimary) }
            }
        }
        val itemsPayload = state.activePayload as? FinancePayload.Items
        val values = itemsPayload?.values.orEmpty()
        if (values.isEmpty()) {
            EmptyFinance("暂无${state.module.label}", "点击右上角刷新查询。", null, Modifier.weight(1f))
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Text(cacheTime(state.activeSavedAt), color = FinanceMuted, fontSize = 11.sp) }
                items(values, key = { "${state.module.key}-${it.id}" }) { item ->
                    Surface(Modifier.fillMaxWidth().clickable { onSelectItem(item) }, shape = RoundedCornerShape(17.dp), color = FinanceCard, border = androidx.compose.foundation.BorderStroke(1.dp, FinanceBorder)) {
                        Column(Modifier.padding(16.dp)) {
                            Row { Text(item.name.ifBlank { state.module.label }, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); if (item.amount.isNotBlank()) Text("¥${money(item.amount)}", color = FinanceAmount, fontWeight = FontWeight.Bold) }
                            val secondary = listOf(item.secondary, item.status).filter(String::isNotBlank).joinToString(" · ")
                            if (secondary.isNotBlank()) Text(secondary, color = FinanceMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                            if (state.module == FinanceModule.FEE_PROJECTS && item.details.isNotEmpty()) FieldGrid(item.details, Modifier.padding(top = 10.dp))
                        }
                    }
                }
                if (itemsPayload?.hasMore == true) item { Button(onClick = onLoadMore, colors = ButtonDefaults.buttonColors(containerColor = FinancePrimary), modifier = Modifier.fillMaxWidth()) { Text("加载更多") } }
            }
        }
    }
}

@Composable
private fun CreditContent(payload: FinancePayload.Tables?, savedAt: Long, onGesture: (Boolean) -> Unit, modifier: Modifier) {
    val sections = payload?.sections.orEmpty()
    if (sections.isEmpty()) return EmptyFinance("暂无学分结算数据", "点击右上角刷新查询。", null, modifier)
    Column(modifier.padding(14.dp)) {
        Row(Modifier.fillMaxWidth()) { Text("学分结算", fontWeight = FontWeight.Bold, fontSize = 18.sp); Text(cacheTime(savedAt), color = FinanceMuted, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f)) }
        val horizontal = rememberScrollState()
        val vertical = rememberScrollState()
        Box(
            Modifier.fillMaxSize().padding(top = 12.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, FinanceBorder, RoundedCornerShape(16.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        onGesture(true)
                        do { val event = awaitPointerEvent(PointerEventPass.Initial) } while (event.changes.any { it.pressed })
                        onGesture(false)
                    }
                }.horizontalScroll(horizontal).verticalScroll(vertical)
        ) {
            Column(Modifier.requiredWidth(tableWidth(sections))) { sections.forEach { CreditSection(it) } }
        }
        DisposableEffect(Unit) { onDispose { onGesture(false) } }
    }
}

@Composable
private fun CreditSection(section: FinanceTableSection) {
    Text(section.title, fontWeight = FontWeight.Bold, color = FinancePrimary, modifier = Modifier.fillMaxWidth().background(Color(0xFFE5ECE8)).padding(13.dp))
    TableRow(section.columns, header = true)
    section.rows.forEach { TableRow(it, header = false) }
}

@Composable private fun TableRow(values: List<String>, header: Boolean) = Row(Modifier.background(if (header) Color(0xFFF0EEE8) else FinanceCard)) {
    values.forEach { value -> Box(Modifier.width(120.dp).border(.5.dp, FinanceBorder).padding(10.dp)) { Text(value.ifBlank { "—" }, fontSize = 11.sp, fontWeight = if (header) FontWeight.Bold else FontWeight.Normal) } }
}

@Composable
private fun DetailSheet(item: FinanceItem, ticketImage: String, onTicket: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp)) {
        Text(item.name, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        if (item.secondary.isNotBlank()) Text(item.secondary, color = FinanceMuted, modifier = Modifier.padding(top = 5.dp, bottom = 14.dp))
        item.details.forEach { field -> Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) { Text(field.label, color = FinanceMuted, modifier = Modifier.weight(1f)); Text(field.value, color = if (field.highlight) FinanceAmount else Color(0xFF202523), fontWeight = FontWeight.Medium) } }
        if (item.canPreview && ticketImage.isBlank()) Button(onClick = { onTicket(item.receiptNumbers.first()) }, colors = ButtonDefaults.buttonColors(containerColor = FinancePrimary), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("查看电子票据") }
        dataBitmap(ticketImage)?.let { bitmap -> androidx.compose.foundation.Image(bitmap.asImageBitmap(), null, Modifier.fillMaxWidth().padding(top = 12.dp)) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FinanceLoginDialog(
    state: FinanceUiState,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onCaptcha: (String) -> Unit,
    onTogglePassword: () -> Unit,
    onRefreshCaptcha: () -> Unit,
    onResetPassword: () -> Unit,
    onDismiss: () -> Unit,
    onLogin: () -> Unit
) {
    val login = state.login
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("登录财务平台", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(login.username, onUsername, label = { Text("学号") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    login.password, onPassword, label = { Text("财务平台密码") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (login.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = onTogglePassword) { Icon(if (login.passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, null) } }
                )
                Text("忘记密码？在浏览器中重置", color = FinancePrimary, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onResetPassword))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(login.captcha, onCaptcha, label = { Text("验证码") }, singleLine = true, modifier = Modifier.weight(1f))
                    dataBitmap(login.captchaImage)?.let { bitmap -> androidx.compose.foundation.Image(bitmap.asImageBitmap(), "刷新验证码", Modifier.size(108.dp, 52.dp).clickable(onClick = onRefreshCaptcha)) }
                }
                if (login.error.isNotBlank()) Text(login.error, color = FinanceAmount, fontSize = 12.sp)
                Text("账号和密码使用系统密钥加密，仅保存在本机。", color = FinanceMuted, fontSize = 10.sp)
            }
        },
        confirmButton = { TextButton(onClick = onLogin, enabled = !state.isRefreshing) { Text("登录并查询", color = FinancePrimary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        containerColor = FinanceCard
    )
}

@Composable private fun EmptyFinance(title: String, description: String, action: (() -> Unit)?, modifier: Modifier) = Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(28.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp); Text(description, color = FinanceMuted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 7.dp))
        if (action != null) Button(onClick = action, colors = ButtonDefaults.buttonColors(containerColor = FinancePrimary), modifier = Modifier.padding(top = 16.dp)) { Text("登录并查询") }
    }
}

@Composable private fun UnsupportedFinance(modifier: Modifier) = EmptyFinance("南宁校区暂未开放", "当前财务查询仅支持桂林校区。入口已保留，后续接入时无需迁移页面。", null, modifier)
@Composable private fun EmptyCard(text: String) = Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = FinanceCard) { Text(text, color = FinanceMuted, textAlign = TextAlign.Center, modifier = Modifier.padding(28.dp)) }
@Composable private fun MetricCard(label: String, value: String, modifier: Modifier) = Surface(modifier, shape = RoundedCornerShape(16.dp), color = FinanceCard) { Column(Modifier.padding(15.dp)) { Text(label, color = FinanceMuted, fontSize = 12.sp); Text("¥ ${money(value)}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp)) } }
@Composable private fun FieldGrid(fields: List<FinanceField>, modifier: Modifier) = Column(modifier) { fields.chunked(2).forEach { row -> Row(Modifier.fillMaxWidth()) { row.forEach { field -> Column(Modifier.weight(1f).padding(vertical = 4.dp)) { Text(field.label, color = FinanceMuted, fontSize = 10.sp); Text("¥${money(field.value)}", color = if (field.highlight) FinanceAmount else Color(0xFF303432), fontSize = 12.sp, fontWeight = FontWeight.Medium) } }; if (row.size == 1) Spacer(Modifier.weight(1f)) } } }

@Composable private fun SecureFinanceWindow() { val context = LocalContext.current; DisposableEffect(context) { val activity = context.findActivity(); activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE); onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) } } }
private tailrec fun Context.findActivity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.findActivity(); else -> null }
private fun money(value: String): String = value.trim().ifBlank { "0.00" }
private fun cacheTime(value: Long): String = if (value <= 0) "暂无缓存" else "${SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(value))} 更新"
private fun tableWidth(sections: List<FinanceTableSection>) = ((sections.maxOfOrNull { it.columns.size } ?: 1) * 120).dp
private fun dataBitmap(value: String) = runCatching { val encoded = value.substringAfter("base64,", ""); if (encoded.isBlank()) null else Base64.decode(encoded, Base64.DEFAULT).let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()
