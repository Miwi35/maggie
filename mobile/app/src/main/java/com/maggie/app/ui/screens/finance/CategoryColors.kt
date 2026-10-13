package com.maggie.app.ui.screens.finance

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The colours a category can take: a palette to tap, and the hex ⇄ hue/saturation/value maths behind « Autre couleur ». */
internal object CategoryColors {
    val PALETTE = listOf(
        "#F44336", "#E91E63", "#9C27B0", "#673AB7", "#3F51B5", "#2196F3",
        "#03A9F4", "#00BCD4", "#009688", "#4CAF50", "#8BC34A", "#CDDC39",
        "#FFC107", "#FF9800", "#FF5722", "#795548", "#9E9E9E", "#607D8B",
    )

    /** `#RRGGBB` in upper case from `RRGGBB`, `#rgb` and the like; null when it is not a colour. */
    fun normalize(raw: String): String? {
        val digits = raw.trim().removePrefix("#").uppercase()
        val full = when (digits.length) {
            3 -> digits.map { "$it$it" }.joinToString("")
            6 -> digits
            else -> return null
        }
        return if (full.all { it in '0'..'9' || it in 'A'..'F' }) "#$full" else null
    }

    /** Hue (0–360), saturation and value (0–1) of a colour, or null when it is not one. */
    fun toHsv(hex: String): FloatArray? {
        val code = normalize(hex) ?: return null
        val r = code.substring(1, 3).toInt(16) / 255f
        val g = code.substring(3, 5).toInt(16) / 255f
        val b = code.substring(5, 7).toInt(16) / 255f
        val maxC = max(r, max(g, b))
        val delta = maxC - min(r, min(g, b))
        val hue = when {
            delta == 0f -> 0f
            maxC == r -> 60f * (((g - b) / delta) % 6f)
            maxC == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }.let { if (it < 0f) it + 360f else it }
        return floatArrayOf(hue, if (maxC == 0f) 0f else delta / maxC, maxC)
    }

    fun fromHsv(hue: Float, saturation: Float, value: Float): String {
        val h = ((hue % 360f) + 360f) % 360f
        val c = value * saturation
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = value - c
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun channel(f: Float) = ((f + m) * 255f).roundToInt().coerceIn(0, 255)
        return "#%02X%02X%02X".format(channel(r), channel(g), channel(b))
    }
}
