package com.maggie.app.ui.screens.fullcalendar

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.theme.MaggieTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

private fun contrastRatio(a: Color, b: Color): Float {
    val first = a.luminance()
    val second = b.luminance()

    return (max(first, second) + 0.05f) / (min(first, second) + 0.05f)
}

class PeriodChipHatchingTest {
    private val yellow = Color(0xFFF4CF6E)

    private fun lightestBackground(surface: MaggieTokens.Surface): Color {
        val h = periodChipHatching(yellow)
        return h.stripe.compositeOver(h.fill.compositeOver(surface.background))
    }

    @Test
    fun `until stays readable on a yellow chip in the dark`() {
        val surface = MaggieTokens.surfaceDark
        assertTrue(contrastRatio(surface.textMuted, lightestBackground(surface)) >= 4.5f)
    }

    @Test
    fun `until stays readable on a yellow chip in the light`() {
        val surface = MaggieTokens.surfaceLight
        assertTrue(contrastRatio(surface.textMuted, lightestBackground(surface)) >= 4.5f)
    }

    @Test
    fun `the chip follows the mockup`() {
        val h = periodChipHatching(yellow)
        assertEquals(0.10f, h.stripe.alpha, 0.01f)
        assertEquals(0f, h.fill.alpha, 0f)
        assertEquals(6.dp, h.band)
        assertEquals(12.dp, h.step)
    }
}
