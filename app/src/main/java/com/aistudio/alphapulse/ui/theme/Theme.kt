package com.aistudio.alphapulse.ui.theme

/**
 * The app theme: colour scheme plus light/dark handling.
 *
 * Every screen is wrapped in this so styling never drifts between screens.
 *
 * Spec Ref: §39 User Interface
 */

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = BrandAccent,
    onPrimary = TextPrimary,
    primaryContainer = BrandCard,
    onPrimaryContainer = TextPrimary,
    secondary = BrandNeural,
    onSecondary = TextPrimary,
    background = BrandDark,
    onBackground = TextPrimary,
    surface = BrandSurface,
    onSurface = TextPrimary,
    surfaceVariant = BrandCard,
    onSurfaceVariant = TextSecondary,
    outline = BrandBorder
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Keep high-contrast institutional dark palette
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = BrandDark.toArgb()
            window.navigationBarColor = BrandDark.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
