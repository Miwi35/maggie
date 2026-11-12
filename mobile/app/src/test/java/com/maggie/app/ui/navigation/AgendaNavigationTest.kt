package com.maggie.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgendaNavigationTest {

    @Test
    fun `Agenda has two sections, Jour then Périodes, each the page of its own route`() {
        assertEquals(listOf("day", "periods"), AgendaSection.entries.map { it.key })
        assertEquals(listOf("Jour", "Périodes"), AgendaSection.entries.map { it.label })
        assertEquals(Screen.Calendar.route, AgendaSection.DAY.route)
        assertEquals(Screen.PeriodList.route, AgendaSection.PERIODS.route)
    }

    @Test
    fun `byKey finds a section by the key of its breadcrumb entry`() {
        assertEquals(AgendaSection.PERIODS, AgendaSection.byKey("periods"))
        assertNull(AgendaSection.byKey("month"))
        assertNull(AgendaSection.byKey(null))
    }

    @Test
    fun `with the calendar underneath a section pops back to it, so the sections are siblings`() {
        assertEquals(
            AgendaSectionAction(route = Screen.PeriodList.route, popToCalendar = true),
            agendaSectionAction(AgendaSection.PERIODS, calendarInBackStack = true),
        )
        assertEquals(
            AgendaSectionAction(route = Screen.PeriodList.route, popToCalendar = false),
            agendaSectionAction(AgendaSection.PERIODS, calendarInBackStack = false),
        )
    }

    @Test
    fun `the form of a period folds into the list's pane on a tablet`() {
        assertEquals(Screen.PeriodList.route, foldsDetailRouteIntoPane(Screen.PeriodForm.route, showsDetailPane = true))
        assertNull(foldsDetailRouteIntoPane(Screen.PeriodForm.route, showsDetailPane = false))
    }
}
