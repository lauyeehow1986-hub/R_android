package com.rmobile.console.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The R project's own brand blue: https://www.r-project.org
private val RBlue = Color(0xFF276DC3)
private val RBlueDark = Color(0xFF1A4A85)

private val LightColors = lightColorScheme(
    primary = RBlue,
    secondary = RBlueDark,
)

private val DarkColors = darkColorScheme(
    primary = RBlue,
    secondary = RBlueDark,
)

@Composable
fun RConsoleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
