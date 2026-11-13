package com.a0soft.gphone.camonitor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = CyanPrimary,
    onPrimary = CyanOnPrimary,
    primaryContainer = CyanPrimaryContainer,
    onPrimaryContainer = CyanOnPrimaryContainer,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    onSurfaceVariant = DarkOnSurfaceVariant,
    error = StatusError,
    onError = Color.White,
    errorContainer = StatusErrorContainer,
    background = DarkSurface,
    onBackground = DarkOnSurface
)

@Composable
fun CamMonitorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
