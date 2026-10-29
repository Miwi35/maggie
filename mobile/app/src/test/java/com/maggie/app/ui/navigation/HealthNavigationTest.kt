package com.maggie.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HealthNavigationTest {

    @Test
    fun `Santé has one section, Sport, and lists no placeholder`() {
        assertEquals(listOf("sport"), HealthSection.entries.map { it.key })
        assertEquals(listOf("Sport"), HealthSection.entries.map { it.label })
        assertEquals(Screen.HealthSport.route, HealthSection.SPORT.route)
        assertEquals("health/sport", Screen.HealthSport.route)
    }

    @Test
    fun `pageOf names the section a route wears the breadcrumb of`() {
        assertEquals(HealthSection.SPORT, HealthSection.pageOf(Screen.HealthSport.route))
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
        assertNull(HealthSection.byKey("sleep"))
    }

    @Test
    fun `the form route takes the session's id or none`() {
        assertEquals("health/sport/form", Screen.HealthSportForm.of(null))
        assertEquals("health/sport/form?id=01ARZ", Screen.HealthSportForm.of("01ARZ"))
    }
}
