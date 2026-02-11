package com.maggie.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DefaultColorScheme = lightColorScheme()

@Composable
fun MaggieTheme(content: @Composable () -> Unit) {
    val colorScheme = try {
        dynamicLightColorScheme(LocalContext.current)
    } catch (_: Exception) {
        DefaultColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
