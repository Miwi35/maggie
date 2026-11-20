package com.maggie.app.ui.navigation

import com.maggie.app.ui.screens.fullcalendar.CalendarViewType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgendaNavigationTest {

    @Test
    fun `sections read Aujourd'hui, Semaine, Mois, Périodes`() {
        assertEquals(listOf("day", "week", "month", "periods"), AgendaSection.entries.map { it.key })
        assertEquals(listOf("Aujourd'hui", "Semaine", "Mois", "Périodes"), AgendaSection.entries.map { it.label })
        assertEquals(Screen.PeriodList.route, AgendaSection.PERIODS.route)
    }

    @Test
    fun `Semaine and Mois open the calendar on that view`() {
        assertEquals(Screen.Calendar.route, AgendaSection.WEEK.route)
        assertEquals(Screen.Calendar.route, AgendaSection.MONTH.route)
        assertEquals(CalendarViewType.WEEK, AgendaSection.WEEK.view)
        assertEquals(CalendarViewType.MONTH, AgendaSection.MONTH.view)
        assertEquals(AgendaSection.WEEK, AgendaSection.of(CalendarViewType.WEEK))
        assertEquals(AgendaSection.DAY, AgendaSection.of(CalendarViewType.DAY))
    }

    @Test
    fun `byKey finds a section by the key of its breadcrumb entry`() {
        assertEquals(AgendaSection.PERIODS, AgendaSection.byKey("periods"))
        assertNull(AgendaSection.byKey("unknown"))
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
