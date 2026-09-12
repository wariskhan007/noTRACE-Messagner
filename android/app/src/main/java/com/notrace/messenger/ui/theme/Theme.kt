package com.notrace.messenger.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = BrandIndigo,
    secondary = AccentViolet,
    background = BackgroundLight,
    surface = BackgroundLight,
)

private val DarkColors = darkColorScheme(
    primary = BrandIndigo,
    secondary = AccentViolet,
    background = BackgroundDark,
    surface = BackgroundDark,
)

@Composable
fun NoTraceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(), // "Dark mode: full support, default follows system setting"
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        val activity = view.context as? Activity
        activity?.window?.let { window ->
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = NoTraceTypography,
        content = content,
    )
}
