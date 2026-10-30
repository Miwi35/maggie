package com.maggie.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HealthNavigationTest {

    @Test
    fun `Santé has three sections, Suivi, Sport, then Configuration last, and lists no placeholder`() {
        assertEquals(listOf("suivi", "sport", "configuration"), HealthSection.entries.map { it.key })
        assertEquals(listOf("Suivi", "Sport", "Configuration"), HealthSection.entries.map { it.label })
        assertEquals(Screen.HealthConfiguration.route, HealthSection.CONFIGURATION.route)
        assertEquals("health/configuration", Screen.HealthConfiguration.route)
        assertEquals(Screen.HealthSuivi.route, HealthSection.SUIVI.route)
        assertEquals("health/suivi", Screen.HealthSuivi.route)
        assertEquals(Screen.HealthSport.route, HealthSection.SPORT.route)
        assertEquals("health/sport", Screen.HealthSport.route)
    }

    @Test
    fun `Suivi is the module's home (MAG-706)`() {
        assertEquals(HealthSection.SUIVI, HealthSection.HOME)
    }

    @Test
    fun `the sections are siblings over Suivi`() {
        assertEquals(HealthSectionAction("health/sport", popToHome = true), healthSectionAction(HealthSection.SPORT, homeInBackStack = true))
        assertEquals(HealthSectionAction("health/suivi", popToHome = false), healthSectionAction(HealthSection.SUIVI, homeInBackStack = false))
        assertEquals(
            HealthSectionAction("health/configuration", popToHome = true),
            healthSectionAction(HealthSection.CONFIGURATION, homeInBackStack = true),
        )
    }

    @Test
    fun `pageOf names the section a route wears the breadcrumb of`() {
        assertEquals(HealthSection.SUIVI, HealthSection.pageOf(Screen.HealthSuivi.route))
        assertEquals(HealthSection.SPORT, HealthSection.pageOf(Screen.HealthSport.route))
        assertEquals(HealthSection.CONFIGURATION, HealthSection.pageOf(Screen.HealthConfiguration.route))
    }

    @Test
    fun `the form and routes outside the module wear no breadcrumb`() {
        assertNull(HealthSection.pageOf(Screen.HealthSportForm.route))
        assertNull(HealthSection.pageOf(Screen.Calendar.route))
        assertNull(HealthSection.pageOf(null))
    }

    @Test
    fun `byKey finds a section by the key of its breadcrumb entry`() {
        assertEquals(HealthSection.SPORT, HealthSection.byKey("sport"))
        assertEquals(HealthSection.SUIVI, HealthSection.byKey("suivi"))
        assertEquals(HealthSection.CONFIGURATION, HealthSection.byKey("configuration"))
        assertNull(HealthSection.byKey("sleep"))
    }

    @Test
    fun `the form route takes the session's id or none`() {
        assertEquals("health/sport/form", Screen.HealthSportForm.of(null))
        assertEquals("health/sport/form?id=01ARZ", Screen.HealthSportForm.of("01ARZ"))
    }
}
