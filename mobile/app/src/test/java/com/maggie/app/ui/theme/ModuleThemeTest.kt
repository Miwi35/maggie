package com.maggie.app.ui.theme

import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ModuleThemeTest {

    @Test
    fun `finance takes the mint on the dark Veilleuse`() {
        val scheme = MaggieDarkColorScheme.inModule(ModuleHue.FINANCE)

        assertEquals(MaggieTokens.Module.comptes.second, scheme.primary)
        assertEquals(MaggieTokens.Module.comptes.second, scheme.secondary)
        assertEquals(MaggieTokens.Brand.onPrimary, scheme.onPrimary)
    }

    @Test
    fun `tasks take the orchid and the light mode its deeper shade`() {
        assertEquals(MaggieTokens.Module.taches.second, MaggieDarkColorScheme.inModule(ModuleHue.TASKS).primary)
        val light = MaggieLightColorScheme.inModule(ModuleHue.TASKS)

        assertEquals(MaggieTokens.Module.taches.first, light.primary)
        assertEquals(MaggieTokens.Brand.onPrimaryLight, light.onPrimary)
    }

    @Test
    fun `a selected chip is the hue over the surface`() {
        val scheme = MaggieDarkColorScheme.inModule(ModuleHue.FINANCE)

        assertEquals(
            MaggieTokens.Module.comptes.second.copy(alpha = 0.22f).compositeOver(scheme.surface),
            scheme.secondaryContainer,
        )
    }

    @Test
    fun `surfaces and errors are left alone`() {
        val scheme = MaggieDarkColorScheme.inModule(ModuleHue.FINANCE)

        assertEquals(MaggieDarkColorScheme.surface, scheme.surface)
        assertEquals(MaggieDarkColorScheme.background, scheme.background)
        assertEquals(MaggieDarkColorScheme.error, scheme.error)
        assertNotEquals(MaggieDarkColorScheme.primary, scheme.primary)
    }
}
