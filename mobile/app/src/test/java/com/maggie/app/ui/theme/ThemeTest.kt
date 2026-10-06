package com.maggie.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * WCAG's non-text minimum — the floor for an icon, a border or a glyph that
 * carries meaning without being prose (SC 1.4.11), and not 4.5, the body-text
 * figure. Four of the pairs below sit between the two, and they were **measured,
 * not missed**: they are radiant's own values, which this ticket exists to name
 * rather than to redraw.
 *
 *  - `primary` / `onPrimary`, both modes — white on the brand violet `#9055FD`:
 *    **4.28**;
 *  - `error` / `onError`, both modes — white on `#DB488B`: **3.95**;
 *  - light `secondary` / `onSecondary` — white on `#A270FF`: **3.33**;
 *  - light `surfaceVariant` / `onSurfaceVariant` — `#89868D` on `#F0F1F6`:
 *    **3.18**.
 *
 * Every other pair is between 5.9 and 19.0. Raising one of the four means
 * changing the web in the same breath, so it belongs to MAG-90's audit, not here.
 */
private const val MIN_CONTRAST = 3.0f

/**
 * WCAG 2.1's ratio, `(L1 + 0.05) / (L2 + 0.05)` over relative luminance — the
 * same measure `ContrastText.kt` thresholds to pick black or white, read off
 * Compose's own `Color.luminance()`.
 */
private fun contrastRatio(a: Color, b: Color): Float {
    val first = a.luminance()
    val second = b.luminance()

    return (max(first, second) + 0.05f) / (min(first, second) + 0.05f)
}

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
    fun `every on-colour reads on the surface it is drawn on`() {
        // The failure this catches: a role set without its `on` pair, which is how
        // text ends up invisible on a container nobody looked at. `container !=
        // content` would not catch it — `#000001` on black is a different colour.
        for ((mode, scheme) in modes) {
            val pairs = listOf(
                "primary" to (scheme.primary to scheme.onPrimary),
                "primaryContainer" to (scheme.primaryContainer to scheme.onPrimaryContainer),
                "secondary" to (scheme.secondary to scheme.onSecondary),
                "secondaryContainer" to (scheme.secondaryContainer to scheme.onSecondaryContainer),
                "surface" to (scheme.surface to scheme.onSurface),
                "surfaceVariant" to (scheme.surfaceVariant to scheme.onSurfaceVariant),
                "background" to (scheme.background to scheme.onBackground),
                "error" to (scheme.error to scheme.onError),
            )

            for ((role, colors) in pairs) {
                val (container, content) = colors
                val ratio = contrastRatio(container, content)
                assert(ratio >= MIN_CONTRAST) {
                    "$mode: $role draws its content at a contrast of $ratio, under $MIN_CONTRAST"
                }
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
        for ((mode, scheme) in modes) {
            assert(MaggieTokens.Night.background != scheme.background) {
                "the night surface is the $mode background; the splash would flash"
            }
        }
    }
}
