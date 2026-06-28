package com.example.proxyllm

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AppColors = lightColorScheme(
    primary = Color(0xFF5E5CE6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E5FF),
    onPrimaryContainer = Color(0xFF201E57),
    secondary = Color(0xFF6D6A84),
    onSecondary = Color.White,
    background = Color(0xFFF7F7FC),
    onBackground = Color(0xFF1E1F27),
    surface = Color.White,
    onSurface = Color(0xFF1E1F27),
    surfaceVariant = Color(0xFFF0EEF8),
    onSurfaceVariant = Color(0xFF5B5F6B),
    outline = Color(0xFFDAD7E8),
    error = Color(0xFFE25A67),
    onError = Color.White
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColors,
        content = content
    )
}
