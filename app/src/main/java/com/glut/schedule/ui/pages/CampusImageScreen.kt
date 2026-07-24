package com.glut.schedule.ui.pages

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glut.schedule.R
import com.glut.schedule.service.campus.CampusImageType

private val CampusPageBackground = Color(0xFFF6F4EF)
private val CampusMessageBackground = Color(0xFFFFFEFB)
private val CampusText = Color(0xFF141821)

private val CampusImageTabs = listOf(
    CampusImageType.CAMPUS_MAP to "校园地图",
    CampusImageType.ACADEMIC_CALENDAR to "教学日历",
    CampusImageType.CLASS_TIME to "上课时间",
    CampusImageType.SHUTTLE_BUS to "校车路线"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusImageScreen(
    viewModel: CampusImageViewModel,
    onImageGestureActive: (Boolean) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val onSelectType = viewModel::selectType
    val bitmap = remember(state.selectedType, state.document?.fetchedAt) {
        state.document?.bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }
    val painter = if (state.selectedType != CampusImageType.CAMPUS_MAP) {
        remember(bitmap) { bitmap?.asImageBitmap()?.let(::BitmapPainter) }
    } else null
    var scale by remember(state.selectedType, state.document?.fetchedAt) { mutableFloatStateOf(1f) }
    var offset by remember(state.selectedType, state.document?.fetchedAt) { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        offset = if (scale == 1f) Offset.Zero else offset + panChange
    }

    LaunchedEffect(scale) {
        onImageGestureActive(scale > 1.01f)
    }
    DisposableEffect(Unit) {
        onDispose { onImageGestureActive(false) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CampusPageBackground)
    ) {
        PrimaryTabRow(
            selectedTabIndex = CampusImageTabs.indexOfFirst { it.first == state.selectedType }
                .coerceAtLeast(0),
            containerColor = CampusPageBackground,
            contentColor = CampusText
        ) {
            CampusImageTabs.forEach { (type, label) ->
                Tab(
                    selected = type == state.selectedType,
                    onClick = { onSelectType(type) },
                    text = { Text(label) }
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .background(CampusPageBackground)
                .clipToBounds()
                .transformable(transformState),
            contentAlignment = Alignment.Center
        ) {
            if (state.selectedType == CampusImageType.CAMPUS_MAP) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                ) {
                    Image(
                        painter = painterResource(R.drawable.yanshan_campus_map),
                        contentDescription = "雁山校区地图",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )
                    Image(
                        painter = painterResource(R.drawable.pingfeng_campus_map),
                        contentDescription = "屏风校区地图",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )
                }
            } else if (painter != null) {
                Image(
                    painter = painter,
                    contentDescription = CampusImageTabs.first { it.first == state.selectedType }.second,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                )
            } else if (!state.isLoading) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text(
                        text = state.message.ifBlank { "校园信息暂时无法加载" },
                        color = CampusText
                    )
                    TextButton(onClick = viewModel::refreshCurrent) { Text("重试") }
                }
            }

            if (state.isLoading) {
                CircularProgressIndicator()
            }
            if (painter != null && state.message.isNotBlank()) {
                Text(
                    text = state.message,
                    color = CampusText,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .background(CampusMessageBackground.copy(alpha = 0.94f))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}
