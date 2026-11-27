package com.maggie.app.ui.screens.fullcalendar

import com.maggie.app.data.model.Period
import org.junit.Assert.assertEquals
import org.junit.Test

class VisiblePeriodsTest {

    private val garde = Period(id = "p1", name = "Vacances", agenda = "/api/agendas/GARDE")
    private val perso = Period(id = "p2", name = "Stage", agenda = "/api/agendas/PERSO")
    private val orphan = Period(id = "p3", name = "Sans agenda")
    private val all = listOf(garde, perso, orphan)

    @Test
    fun `unchecked agenda hides its periods`() {
        assertEquals(listOf(perso, orphan), visiblePeriods(all, setOf("PERSO"), true))
    }

    @Test
    fun `periods off hides all`() {
        assertEquals(emptyList<Period>(), visiblePeriods(all, setOf("GARDE", "PERSO"), false))
    }

    @Test
    fun `periods on shows only checked agendas`() {
        assertEquals(listOf(garde, orphan), visiblePeriods(all, setOf("GARDE"), true))
    }

    @Test
    fun `period without agenda follows the periods toggle`() {
        assertEquals(listOf(orphan), visiblePeriods(listOf(orphan), emptySet(), true))
        assertEquals(emptyList<Period>(), visiblePeriods(listOf(orphan), emptySet(), false))
    }
}
