package com.maggie.app.ui.screens.shared

import com.maggie.app.data.model.ExpandedEvent
import com.maggie.app.ui.screens.dashboard.dashboardEventTime
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** MAG-382: the detail and the dashboard show an all-day event's dates as they are, the last one included. */
class AllDayLabelsTest {

    private fun allDay(first: LocalDate, last: LocalDate) =
        ExpandedEvent(id = "e", summary = "e", allDay = true, startDate = first, endDate = last)

    @Test
    fun `the detail shows a one-day event on its date`() {
        assertEquals("Jeudi 1 janvier 2037", eventDateLabel(allDay(LocalDate.of(2037, 1, 1), LocalDate.of(2037, 1, 1))))
    }

    @Test
    fun `the detail shows a three-day event from its first to its last day`() {
        assertEquals(
            "Du lundi 26 janvier 2037 au mercredi 28 janvier 2037",
            eventDateLabel(allDay(LocalDate.of(2037, 1, 26), LocalDate.of(2037, 1, 28))),
        )
    }

    @Test
    fun `the detail shows a timed event's day and hours in its zone`() {
        val dinner = ExpandedEvent(id = "d", summary = "Dîner", startAt = "2026-10-07T17:00:00Z", endAt = "2026-10-07T22:00:00Z")

        assertEquals("Mercredi 7 octobre 2026, 19:00 - 00:00", eventDateLabel(dinner))
    }

    @Test
    fun `the dashboard shows an all-day event's date, or Journée on its own day`() {
        val birthday = allDay(LocalDate.of(2037, 1, 1), LocalDate.of(2037, 1, 1))

        assertEquals("1 janv.", dashboardEventTime(birthday, showDate = true))
        assertEquals("Journée", dashboardEventTime(birthday, showDate = false))
    }
}
