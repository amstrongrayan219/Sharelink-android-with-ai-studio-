package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = ShareLinkPrimary,
    secondary = ShareLinkSecondary,
    tertiary = ShareLinkTertiary,
    background = ShareLinkBackground,
    surface = ShareLinkSurface,
    onBackground = ShareLinkOnBackground,
    onSurface = ShareLinkOnSurface,
    primaryContainer = ShareLinkSurface,
    onPrimaryContainer = Color.White,
    surfaceVariant = ShareLinkSurfaceVariant,
    onSurfaceVariant = ShareLinkOnSurfaceVariant,
    error = ShareLinkError
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit,
) {
    // Elegant system dark mode designed specifically for local Wi-Fi secure sharing
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
