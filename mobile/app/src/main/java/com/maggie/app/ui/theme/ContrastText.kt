package com.maggie.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

// Contrast of 3 against white is luminance 1.05 / 3 - 0.05; above it white text is too faint.
private const val MAX_LUMINANCE_FOR_WHITE_TEXT = 0.3f

/** Black or white, whichever reads better on [background] (same rule as the admin's `getEventTextColor`). */
fun readableTextOn(background: Color): Color {
    return if (background.luminance() > MAX_LUMINANCE_FOR_WHITE_TEXT) Color.Black else Color.White
}
