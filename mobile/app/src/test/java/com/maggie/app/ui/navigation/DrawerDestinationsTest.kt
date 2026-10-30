package com.maggie.app.ui.navigation

import com.maggie.app.ui.components.DRAWER_DESTINATIONS
import com.maggie.app.ui.components.RAIL_DESTINATIONS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drawer and the rail, to the letter of the mock-up `charte/Tiroir` (MAG-620): the seven modules
 * in the owner's order, then Réglages. Maggie lives in the bottom bar, so there is no « Chat » entry.
 */
class DrawerDestinationsTest {
    @Test
    fun `the drawer lists exactly the seven modules, in the mock-up's order`() {
        assertEquals(
            listOf("Accueil", "Agenda", "Tâches", "Courses", "Cuisine", "Finance", "Santé"),
            DRAWER_DESTINATIONS.map { it.label },
        )
        assertEquals(
            listOf(
                Screen.Home.route,
                Screen.Calendar.route,
                Screen.Tasks.route,
                Screen.Grocery.route,
                Screen.Cookbook.route,
                Screen.FinanceDashboard.route,
                Screen.HealthSuivi.route,
            ),
            DRAWER_DESTINATIONS.map { it.route },
        )
    }

    @Test
    fun `the rail is the same seven then Réglages, last`() {
        assertEquals(DRAWER_DESTINATIONS.map { it.route } + "settings", RAIL_DESTINATIONS.map { it.route })
        assertEquals("Réglages", RAIL_DESTINATIONS.last().label)
    }

    @Test
    fun `Maggie is in the bottom bar, not in the navigation`() {
        assertTrue(RAIL_DESTINATIONS.none { it.route == Screen.Chat.route })
        assertFalse(RAIL_DESTINATIONS.any { it.label == "Chat" })
    }

    @Test
    fun `no navigation entry wears an old name`() {
        val labels = RAIL_DESTINATIONS.map { it.label }
        listOf("Calendrier", "Paramètres", "Chat", "Nutrition", "Loisirs").forEach {
            assertFalse("« $it » is not an entry", it in labels)
        }
    }
}
