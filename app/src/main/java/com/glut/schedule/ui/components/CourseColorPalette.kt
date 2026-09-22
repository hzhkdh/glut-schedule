package com.glut.schedule.ui.components

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glut.schedule.data.model.CourseColorMapper
import kotlinx.coroutines.launch

/**
 * 课程卡片调色的共用组件。
 *
 * 「设置 → 课程卡片颜色」与「长按卡片 → 卡片管理」两处用的是同一套能力（20 个预设色 +
 * 恢复自动配色 + 高级调色），所以抽到这里共用。此前它们以 private 形式内嵌在 MainActivity 里，
 * 组件外面复用不到；内部实现未做任何改动。
 */

/** 20 个预设色网格 + 「恢复自动配色」/「高级调色」。 */
@Composable
internal fun CourseColorPaletteSection(
    onSelect: (String) -> Unit,
    onAdvanced: () -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        CourseColorMapper.presetPalette.chunked(5).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                row.forEach { color ->
                    Surface(
                        modifier = Modifier.size(52.dp).clickable { onSelect(color) },
                        color = Color(AndroidColor.parseColor(color)),
                        shape = RoundedCornerShape(16.dp)
                    ) {}
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onRestore) {
                Text("恢复自动配色", color = Color(0xFF667085), fontSize = 15.sp)
            }
            TextButton(onClick = onAdvanced) {
                Text("高级调色", color = Color(0xFF3F7DF6), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdvancedColorSheet(
    initialColor: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val initialHsv = remember(initialColor) { hexToHsv(initialColor) }
    var hue by remember(initialColor) { mutableStateOf(initialHsv[0]) }
    var saturation by remember(initialColor) { mutableStateOf(initialHsv[1]) }
    var value by remember(initialColor) { mutableStateOf(initialHsv[2]) }
    var hexField by remember(initialColor) { mutableStateOf(TextFieldValue(initialColor, TextRange(0))) }

    fun updateFromHsv(nextHue: Float = hue, nextSaturation: Float = saturation, nextValue: Float = value) {
        hue = nextHue
        saturation = nextSaturation
        value = nextValue
        val nextHex = hsvToHex(hue, saturation, value)
        hexField = TextFieldValue(nextHex, TextRange(nextHex.length))
    }

    var planeSize by remember { mutableStateOf(IntSize.Zero) }
    var hueTrackSize by remember { mutableStateOf(IntSize.Zero) }
    val normalizedColor = CourseColorMapper.normalizeHexColor(hexField.text)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scrollState = rememberScrollState()
    val hexBringIntoViewRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val planeThumbSizePx = with(density) { 20.dp.toPx() }
    val hueThumbSizePx = with(density) { 22.dp.toPx() }

    fun updatePlane(position: Offset) {
        if (planeSize.width == 0 || planeSize.height == 0) return
        updateFromHsv(
            nextSaturation = (position.x / planeSize.width).coerceIn(0f, 1f),
            nextValue = (1f - position.y / planeSize.height).coerceIn(0f, 1f)
        )
    }

    fun updateHue(position: Offset) {
        if (hueTrackSize.width == 0) return
        updateFromHsv(nextHue = (position.x / hueTrackSize.width * 360f).coerceIn(0f, 360f))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFFFFFEFB),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .verticalScroll(scrollState)
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "高级调色",
                    color = Color(0xFF141821),
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text("拖动或输入十六进制", color = Color(0xFF667085), fontSize = 13.sp)
            }
            Surface(
                modifier = Modifier.fillMaxWidth().height(52.dp),
                color = Color(AndroidColor.parseColor(normalizedColor ?: "#3B82F6")),
                shape = RoundedCornerShape(16.dp)
            ) {}
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(248.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color.White, Color(AndroidColor.parseColor(hsvToHex(hue, 1f, 1f))))
                        ),
                        RoundedCornerShape(16.dp)
                    )
                    .onSizeChanged { planeSize = it }
                    .pointerInput(planeSize, hue) {
                        detectDragGestures(
                            onDragStart = ::updatePlane,
                            onDrag = { change, _ -> updatePlane(change.position) }
                        )
                    }
            ) {
                Box(
                    modifier = Modifier.matchParentSize().background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black)),
                        RoundedCornerShape(16.dp)
                    )
                )
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (saturation * maxOf(0f, planeSize.width - planeThumbSizePx)).toInt(),
                                ((1f - value) * maxOf(0f, planeSize.height - planeThumbSizePx)).toInt()
                            )
                        }
                        .size(20.dp)
                        .border(2.dp, Color.White, CircleShape)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "色相",
                    color = Color(0xFF344054),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text("拖动切换基础颜色", color = Color(0xFF98A2B3), fontSize = 13.sp)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color(0xFFF04438), Color(0xFFF79009), Color(0xFFFDE272),
                                Color(0xFF12B76A), Color(0xFF2E90FA), Color(0xFF9E77ED),
                                Color(0xFFEE46BC), Color(0xFFF04438)
                            )
                        ),
                        RoundedCornerShape(14.dp)
                    )
                    .onSizeChanged { hueTrackSize = it }
                    .pointerInput(hueTrackSize) {
                        detectDragGestures(
                            onDragStart = ::updateHue,
                            onDrag = { change, _ -> updateHue(change.position) }
                        )
                    }
            ) {
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (hue / 360f * maxOf(0f, hueTrackSize.width - hueThumbSizePx)).toInt(),
                                ((hueTrackSize.height - hueThumbSizePx) / 2f).toInt()
                            )
                        }
                        .size(22.dp)
                        .background(Color.White, CircleShape)
                        .border(2.dp, Color(0xFF667085), CircleShape)
                )
            }
            OutlinedTextField(
                value = hexField,
                onValueChange = { input ->
                    hexField = input
                    CourseColorMapper.normalizeHexColor(input.text)?.let { color ->
                        val hsv = hexToHsv(color)
                        hue = hsv[0]
                        saturation = hsv[1]
                        value = hsv[2]
                    }
                },
                label = { Text("HEX 颜色") },
                placeholder = { Text("#154173") },
                singleLine = true,
                isError = hexField.text.isNotBlank() && normalizedColor == null,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color(0xFF141821)),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color(0xFF141821),
                    unfocusedTextColor = Color(0xFF141821),
                    focusedBorderColor = Color(0xFF3F7DF6),
                    unfocusedBorderColor = Color(0xFF98A2B3),
                    cursorColor = Color(0xFF3F7DF6)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(hexBringIntoViewRequester)
                    .onFocusEvent { focusState ->
                        if (focusState.isFocused) scope.launch { hexBringIntoViewRequester.bringIntoView() }
                    }
            )
            Text("支持 #RRGGBB 或 RRGGBB；每组十六进制取值为 00 到 FF。", color = Color(0xFF667085), fontSize = 12.sp)
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(enabled = normalizedColor != null, onClick = { normalizedColor?.let(onConfirm) }) {
                    Text("完成")
                }
            }
        }
    }
}

private fun hexToHsv(hex: String): FloatArray {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(AndroidColor.parseColor(CourseColorMapper.normalizeHexColor(hex) ?: "#3B82F6"), hsv)
    return hsv
}

private fun hsvToHex(hue: Float, saturation: Float, value: Float): String {
    return "#%06X".format(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value)) and 0xFFFFFF)
}
