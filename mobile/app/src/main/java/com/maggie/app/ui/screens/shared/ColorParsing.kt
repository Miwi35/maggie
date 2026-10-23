package com.maggie.app.ui.screens.shared

import androidx.compose.ui.graphics.Color

/** An agenda's `#RRGGBB` colour, or `null` when the server sent something that is not one. */
internal fun parseColor(hex: String): Color? {
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (_: Exception) {
        null
    }
}
