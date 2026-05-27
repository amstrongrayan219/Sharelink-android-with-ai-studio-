package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = SatDarkPrimary,
    secondary = SatDarkSecondary,
    tertiary = SatDarkTertiary,
    background = SatDarkBackground,
    surface = SatDarkSurface,
    onBackground = SatDarkOnSurface,
    onSurface = SatDarkOnSurface,
    primaryContainer = SatDarkSurface,
    onPrimaryContainer = Color.White,
    surfaceVariant = Color(0xFF1E2638),
    onSurfaceVariant = Color(0xFF94A3B8)
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit,
) {
    // Force Dark Mode to ensure a professional aerospace radar alignment aesthetic
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
