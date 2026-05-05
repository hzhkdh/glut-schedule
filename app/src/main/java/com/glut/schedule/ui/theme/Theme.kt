package com.glut.schedule.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7DD3FC),
    secondary = Color(0xFFC4B5FD),
    background = Color(0xFF07111F),
    surface = Color(0xFF101827),
    onPrimary = Color(0xFF06121F),
    onSecondary = Color(0xFF111827),
    onBackground = Color.White,
    onSurface = Color.White
)

@Composable
fun GlutScheduleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
