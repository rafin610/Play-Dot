package com.streambox.mobile.ui.theme

import androidx.compose.ui.graphics.Color

// Play Dot palette — sampled from the supplied logo PNGs.
// Backgrounds stay obsidian/neutral so the violet-blue brand is the accent,
// not the whole interface.
val AppBackground = Color(0xFF06070B)
val AppSurface = Color(0xFF0E1117)
val AppSurfaceHigh = Color(0xFF161A22)
val AppSurfaceHigher = Color(0xFF202530)
val AppOutline = Color(0xFF2C3340)

val AppTextPrimary = Color(0xFFF4F6FB)
val AppTextSecondary = Color(0xFFA2ABBB)
val AppTextMuted = Color(0xFF66707E)

// Sampled directly from the gradient in the supplied symbol artwork.
val AppAccentViolet = Color(0xFF6954FB)
val AppAccentBlue = Color(0xFF0D7EFA)
val AppAccentMid = Color(0xFF4363F9)
val AppAccent = AppAccentViolet
val AppAccentDeep = Color(0xFF4B36C9)

/** Quiet violet wash for selected navigation surfaces. */
val AppAccentSurface = Color(0xFF241E3B)

/** Primary brand action (play, next episode). Named for intent, not hue. */
val AppBrandAction = AppAccentViolet

/** Rating gold is a functional accent, deliberately not part of the brand gradient. */
val AppRating = Color(0xFFFFC857)
val AppError = Color(0xFFFF6B6B)
val AppSuccess = Color(0xFF67D1A5)
val AppGradientScrimTop = AppBackground

/** Restrained brand gradient for hero accents and selected states. */
val BrandGradient = androidx.compose.ui.graphics.Brush.linearGradient(
    colors = listOf(AppAccentViolet, AppAccentMid, AppAccentBlue),
)
