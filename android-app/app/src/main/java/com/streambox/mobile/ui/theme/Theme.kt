package com.streambox.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Play Dot is intentionally dark-only: an obsidian canvas with restrained
 * violet/blue brand accents sampled from the supplied logo.
 */
private val PlayDotColors = darkColorScheme(
    primary = AppAccent,
    onPrimary = Color.White,
    primaryContainer = AppAccentDeep,
    onPrimaryContainer = AppTextPrimary,
    secondary = AppAccentBlue,
    onSecondary = Color.White,
    secondaryContainer = AppAccentSurface,
    onSecondaryContainer = AppTextPrimary,
    tertiary = AppAccentMid,
    onTertiary = Color.White,
    background = AppBackground,
    onBackground = AppTextPrimary,
    surface = AppSurface,
    onSurface = AppTextPrimary,
    surfaceVariant = AppSurfaceHigh,
    onSurfaceVariant = AppTextSecondary,
    surfaceTint = AppAccent,
    outline = AppOutline,
    outlineVariant = AppSurfaceHigher,
    error = AppError,
    onError = AppBackground,
    errorContainer = Color(0xFF3B1E22),
    onErrorContainer = Color(0xFFFFD9DC),
    scrim = Color.Black,
)

@Composable
fun StreamBoxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PlayDotColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
