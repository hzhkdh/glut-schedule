package com.glut.schedule.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext

@Composable
fun StarryScheduleBackground(
    modifier: Modifier = Modifier,
    customBackgroundUri: String = ""
) {
    val context = LocalContext.current
    var customBitmap by remember(customBackgroundUri) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(customBackgroundUri) {
        customBitmap = if (shouldUseCustomBackground(customBackgroundUri)) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(customBackgroundUri))?.use { input ->
                    BitmapFactory.decodeStream(input)?.asImageBitmap()
                }
            }.getOrNull()
        } else {
            null
        }
    }
    customBitmap?.let { bitmap ->
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Canvas(modifier = modifier.fillMaxSize()) {
            drawRect(Color(0x99000000))
        }
        return
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF050816),
                    Color(0xFF102A43),
                    Color(0xFF0F766E),
                    Color(0xFF2A1427)
                ),
                start = Offset.Zero,
                end = Offset(size.width, size.height)
            )
        )
        drawCircle(Color(0x33FDE68A), radius = size.minDimension * 0.34f, center = Offset(size.width * 0.18f, size.height * 0.22f))
        drawCircle(Color(0x3322D3EE), radius = size.minDimension * 0.42f, center = Offset(size.width * 0.78f, size.height * 0.72f))
        drawCircle(Color(0x22F472B6), radius = size.minDimension * 0.24f, center = Offset(size.width * 0.62f, size.height * 0.18f))
        val stars = listOf(
            Offset(0.16f, 0.12f), Offset(0.34f, 0.18f), Offset(0.52f, 0.10f),
            Offset(0.82f, 0.20f), Offset(0.74f, 0.42f), Offset(0.28f, 0.66f),
            Offset(0.48f, 0.78f), Offset(0.88f, 0.86f)
        )
        stars.forEachIndexed { index, star ->
            drawCircle(
                color = Color.White.copy(alpha = if (index % 2 == 0) 0.36f else 0.22f),
                radius = 2.2f + index % 3,
                center = Offset(star.x * size.width, star.y * size.height)
            )
        }
        drawRect(Color(0x66000000))
    }
}

fun shouldUseCustomBackground(uri: String): Boolean {
    return uri.isNotBlank()
}
