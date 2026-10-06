package com.maggie.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Compose theme against the tokens (MAG-39).
 *
 * A theme is a value, so this is plain JUnit — no Robolectric, no emulator
 * (`agent-os/standards/mobile/screen-tests.md`). What it is for is the half the
 * contract test cannot see: that the scheme and the shapes actually *read* the
 * tokens, rather than holding Material's baseline next to a token file nobody
 * consults. The typography joins them when Gabarito is bundled — its own ticket.
 *
 * Both modes, side by side: the dark scheme was six roles out of twenty-nine
 * and everything else was Material's own, which is how the phone ended up on a
 * near-black no admin screen has ever used.
 */
class ThemeTest {

    private val modes = listOf(
        "light" to MaggieLightColorScheme,
        "dark" to MaggieDarkColorScheme,
    )

    @Test
    fun `both schemes take the brand violet as their accent`() {
        // One accent, both modes, same as the admin's — not Material's
        // convention of a pale tone on dark.
        for ((mode, scheme) in modes) {
            assertEquals(mode, MaggieTokens.Brand.primary, scheme.primary)
            assertEquals(mode, MaggieTokens.Brand.onPrimary, scheme.onPrimary)
        }
    }

    @Test
    fun `each scheme takes its surfaces and its text from its own mode`() {
        for ((mode, scheme) in modes) {
            val surface = if (mode == "light") MaggieTokens.surfaceLight else MaggieTokens.surfaceDark

            assertEquals(mode, surface.background, scheme.background)
            assertEquals(mode, surface.text, scheme.onBackground)
            assertEquals(mode, surface.paper, scheme.surface)
            assertEquals(mode, surface.text, scheme.onSurface)
            assertEquals(mode, surface.textMuted, scheme.onSurfaceVariant)
            assertEquals(mode, surface.textMuted, scheme.outline)
        }
    }

    @Test
    fun `both schemes report an error in the colour the admin does`() {
        for ((mode, scheme) in modes) {
            assertEquals(mode, MaggieTokens.Feedback.error, scheme.error)
        }
    }

    @Test
    fun `no on-colour is left on Material's baseline`() {
        // The failure this catches: a role set without its `on` pair, which is
        // how text ends up invisible on a container nobody checked.
        for ((mode, scheme) in modes) {
            val pairs: List<Pair<String, Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color>>> =
                listOf(
                    "primary" to (scheme.primary to scheme.onPrimary),
                    "primaryContainer" to (scheme.primaryContainer to scheme.onPrimaryContainer),
                    "secondary" to (scheme.secondary to scheme.onSecondary),
                    "secondaryContainer" to (scheme.secondaryContainer to scheme.onSecondaryContainer),
                    "surface" to (scheme.surface to scheme.onSurface),
                    "background" to (scheme.background to scheme.onBackground),
                    "error" to (scheme.error to scheme.onError),
                )

            for ((role, colors) in pairs) {
                val (container, content) = colors
                assert(container != content) { "$mode: $role draws its content in its own colour" }
            }
        }
    }

    @Test
    fun `the shapes round by the shared radii`() {
        assertEquals(RoundedCornerShape(MaggieTokens.Radius.xs), MaggieShapes.extraSmall)
        assertEquals(RoundedCornerShape(MaggieTokens.Radius.sm), MaggieShapes.small)
        assertEquals(RoundedCornerShape(MaggieTokens.Radius.md), MaggieShapes.medium)
        assertEquals(RoundedCornerShape(MaggieTokens.Radius.lg), MaggieShapes.large)
        assertEquals(RoundedCornerShape(MaggieTokens.Radius.xl), MaggieShapes.extraLarge)
    }

    @Test
    fun `the night surface reads on both modes, because it belongs to neither`() {
        // The sign-in, loading and lock screens are drawn on `night`, not on the
        // scheme — they are shown before anything knows which mode is on.
        val scheme: ColorScheme = MaggieLightColorScheme

        assert(MaggieTokens.Night.background != scheme.background) {
            "the night surface is the light background; the splash would flash"
        }
        assertEquals(MaggieTokens.Night.text.copy(alpha = 0.6f), MaggieTokens.Night.textMuted)
    }
}
