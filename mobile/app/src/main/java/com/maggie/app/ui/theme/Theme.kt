package com.maggie.app.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DefaultColorScheme = lightColorScheme()

@Composable
fun MaggieTheme(content: @Composable () -> Unit) {
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicLightColorScheme(LocalContext.current)
    } else {
        DefaultColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
