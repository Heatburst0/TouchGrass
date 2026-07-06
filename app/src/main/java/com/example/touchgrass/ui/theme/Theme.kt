package com.example.touchgrass.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Dark-only by design: this app is a night-mode product, and dynamic color
// would fight the green/amber/red progress semantics.
private val TouchGrassColorScheme = darkColorScheme(
    primary = GrassGreen,
    onPrimary = Ink,
    secondary = AmberWarn,
    onSecondary = Ink,
    tertiary = DangerRed,
    background = Ink,
    onBackground = TextPrimary,
    surface = InkElevated,
    onSurface = TextPrimary,
    surfaceVariant = InkBorder,
    onSurfaceVariant = TextSecondary,
    error = DangerRed
)

@Composable
fun TouchGrassTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TouchGrassColorScheme,
        typography = Typography,
        content = content
    )
}
