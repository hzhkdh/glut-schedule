package com.glut.schedule.ui.pages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glut.schedule.data.model.NormalizedCropRect
import com.glut.schedule.ui.components.BackgroundCropTransform
import com.glut.schedule.ui.components.ScheduleBackgroundStore
import com.glut.schedule.ui.components.calculateCropTransform
import com.glut.schedule.ui.components.clampCropTransform
import com.glut.schedule.ui.components.cropRectFromTransform

/**
 * 应用内背景裁剪器。裁剪框保持目标屏幕比例，图片手势只改变缩放和平移，确认后再反算原图坐标。
 */
@Composable
fun BackgroundCropScreen(
    uri: String,
    initialCrop: NormalizedCropRect?,
    targetWidth: Int,
    targetHeight: Int,
    backgroundStore: ScheduleBackgroundStore,
    isSaving: Boolean,
    onCancel: () -> Unit,
    onConfirm: (NormalizedCropRect) -> Unit
) {
    BackHandler(enabled = !isSaving, onBack = onCancel)
    var preview by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    var loadFailed by remember(uri) { mutableStateOf(false) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var transform by remember { mutableStateOf(BackgroundCropTransform(1f, 0f, 0f)) }
    var initializedForSize by remember(uri) { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(uri) {
        preview = backgroundStore.loadPreview(uri)
        loadFailed = preview == null
    }

    LaunchedEffect(preview, viewportSize, initialCrop) {
        val image = preview ?: return@LaunchedEffect
        if (viewportSize == IntSize.Zero || initializedForSize == viewportSize) return@LaunchedEffect
        transform = calculateCropTransform(
            crop = initialCrop,
            imageWidth = image.width.toFloat(),
            imageHeight = image.height.toFloat(),
            viewportWidth = viewportSize.width.toFloat(),
            viewportHeight = viewportSize.height.toFloat()
        )
        initializedForSize = viewportSize
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF111318))
            .pointerInput(Unit) {
                // 裁剪页是独占模态层；在 Final 阶段吞掉子控件未处理的事件，禁止点击透传到底层页面。
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        event.changes.forEach { change ->
                            if (!change.isConsumed) change.consume()
                        }
                    }
                }
            }
            .windowInsetsPadding(WindowInsets.statusBars)
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel, enabled = !isSaving) { Text("取消") }
            Text(
                text = "裁剪背景",
                color = Color.White,
                fontSize = 20.sp,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                enabled = preview != null && !isSaving,
                onClick = {
                    val image = preview ?: return@TextButton
                    onConfirm(
                        cropRectFromTransform(
                            imageWidth = image.width.toFloat(),
                            imageHeight = image.height.toFloat(),
                            viewportWidth = viewportSize.width.toFloat(),
                            viewportHeight = viewportSize.height.toFloat(),
                            zoom = transform.zoom,
                            offsetX = transform.offsetX,
                            offsetY = transform.offsetY
                        )
                    )
                }
            ) { Text(if (isSaving) "处理中" else "使用") }
        }

        Box(modifier = Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
            val safeTargetWidth = targetWidth.coerceAtLeast(1)
            val safeTargetHeight = targetHeight.coerceAtLeast(1)
            androidx.compose.foundation.layout.BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                val targetRatio = safeTargetWidth.toFloat() / safeTargetHeight
                val frameHeight = minOf(maxHeight, maxWidth / targetRatio)
                val frameWidth = frameHeight * targetRatio
                Box(
                    modifier = Modifier
                        .size(frameWidth, frameHeight)
                        .background(Color.Black)
                        .border(2.dp, Color.White)
                        .onSizeChanged { viewportSize = it }
                        .pointerInput(preview, viewportSize) {
                            val image = preview ?: return@pointerInput
                            detectTransformGestures { _, pan, gestureZoom, _ ->
                                transform = clampCropTransform(
                                    imageWidth = image.width.toFloat(),
                                    imageHeight = image.height.toFloat(),
                                    viewportWidth = viewportSize.width.toFloat(),
                                    viewportHeight = viewportSize.height.toFloat(),
                                    zoom = transform.zoom * gestureZoom,
                                    offsetX = transform.offsetX + pan.x,
                                    offsetY = transform.offsetY + pan.y
                                )
                            }
                        }
                ) {
                    val image = preview
                    if (image != null && viewportSize != IntSize.Zero) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val baseScale = maxOf(
                                size.width / image.width,
                                size.height / image.height
                            )
                            val scale = baseScale * transform.zoom
                            val drawnWidth = image.width * scale
                            val drawnHeight = image.height * scale
                            drawImage(
                                image = image,
                                dstOffset = IntOffset(
                                    ((size.width - drawnWidth) / 2f + transform.offsetX).toInt(),
                                    ((size.height - drawnHeight) / 2f + transform.offsetY).toInt()
                                ),
                                dstSize = IntSize(drawnWidth.toInt(), drawnHeight.toInt())
                            )
                        }
                    }
                }
            }

            when {
                loadFailed -> Text("无法读取这张图片，请重新选择", color = Color.White)
                preview == null || isSaving -> CircularProgressIndicator(color = Color.White)
            }
        }
        Text(
            text = "拖动调整位置，双指缩放图片",
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 14.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}
