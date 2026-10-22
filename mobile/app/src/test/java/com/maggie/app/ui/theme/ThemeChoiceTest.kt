package com.maggie.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A fresh install wears the dark Veilleuse the mock-ups are drawn in (MAG-535). */
class ThemeChoiceTest {

    @Test
    fun `an install that never chose is dark, whatever the system is`() {
        assertEquals("dark", THEME_DEFAULT)
        assertTrue(usesDarkTheme(THEME_DEFAULT, systemIsDark = false))
        assertTrue(usesDarkTheme(null, systemIsDark = false))
        assertTrue(usesDarkTheme("something-new", systemIsDark = false))
    }

    @Test
    fun `light stays one click away, light whatever the system is`() {
        assertFalse(usesDarkTheme("light", systemIsDark = true))
        assertFalse(usesDarkTheme("light", systemIsDark = false))
    }

    @Test
    fun `system follows the phone`() {
        assertTrue(usesDarkTheme("system", systemIsDark = true))
        assertFalse(usesDarkTheme("system", systemIsDark = false))
    }

    @Test
    fun `dark is dark`() {
        assertTrue(usesDarkTheme("dark", systemIsDark = false))
    }
}
