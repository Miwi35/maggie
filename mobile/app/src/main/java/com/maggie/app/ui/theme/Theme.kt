package com.maggie.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MaggiePurple = Color(0xFF9055FD)
private val MaggiePurpleLight = Color(0xFFE8DEFF)
private val MaggiePurpleDark = Color(0xFF6200EE)

private val MaggieColorScheme = lightColorScheme(
    primary = MaggiePurple,
    onPrimary = Color.White,
    primaryContainer = MaggiePurpleLight,
    onPrimaryContainer = Color(0xFF21005D),
    secondary = MaggiePurple,
    onSecondary = Color.White,
    secondaryContainer = MaggiePurpleLight,
    onSecondaryContainer = Color(0xFF21005D),
)

@Composable
fun MaggieTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaggieColorScheme,
        content = content,
    )
}
