package com.maggie.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MaggiePurple = Color(0xFF9055FD)
private val MaggiePurpleLight = Color(0xFFE8DEFF)
private val MaggiePurpleDark = Color(0xFF6200EE)

private val MaggieLightColorScheme = lightColorScheme(
    primary = MaggiePurple,
    onPrimary = Color.White,
    primaryContainer = MaggiePurpleLight,
    onPrimaryContainer = Color(0xFF21005D),
    secondary = MaggiePurple,
    onSecondary = Color.White,
    secondaryContainer = MaggiePurpleLight,
    onSecondaryContainer = Color(0xFF21005D),
)

private val MaggieDarkColorScheme = darkColorScheme(
    primary = MaggiePurpleLight,
    onPrimary = Color(0xFF21005D),
    primaryContainer = MaggiePurpleDark,
    onPrimaryContainer = MaggiePurpleLight,
    secondary = MaggiePurpleLight,
    onSecondary = Color(0xFF21005D),
    secondaryContainer = MaggiePurpleDark,
    onSecondaryContainer = MaggiePurpleLight,
)

@Composable
fun MaggieTheme(
    themePreference: String = "system",
    content: @Composable () -> Unit,
) {
    val colorScheme = when (themePreference) {
        "dark" -> MaggieDarkColorScheme
        "light" -> MaggieLightColorScheme
        else -> if (isSystemInDarkTheme()) MaggieDarkColorScheme else MaggieLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
