package com.maggie.app.ui.theme

import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertEquals
import org.junit.Test

/** The charter's attention and overspend states reach the screens through Material's roles (MAG-535). */
class StateRolesTest {

    @Test
    fun `attention is the mode's warning`() {
        assertEquals(MaggieTokens.feedbackDark.warning, MaggieDarkColorScheme.tertiary)
        assertEquals(MaggieTokens.feedbackLight.warning, MaggieLightColorScheme.tertiary)
        assertEquals(MaggieTokens.feedbackDark.warning, MaggieDarkColorScheme.onTertiaryContainer)
    }

    @Test
    fun `overspend is the mode's error, its container a tint of it`() {
        assertEquals(MaggieTokens.feedbackDark.error, MaggieDarkColorScheme.error)
        assertEquals(
            MaggieTokens.feedbackDark.error.copy(alpha = STATE_CONTAINER_ALPHA).compositeOver(MaggieDarkColorScheme.surface),
            MaggieDarkColorScheme.errorContainer,
        )
        assertEquals(MaggieTokens.feedbackDark.error, MaggieDarkColorScheme.onErrorContainer)
    }
}
