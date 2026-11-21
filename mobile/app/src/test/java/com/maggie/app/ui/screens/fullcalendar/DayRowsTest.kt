package com.maggie.app.ui.screens.fullcalendar

import com.maggie.app.data.model.ExpandedEvent
import com.maggie.app.data.model.Trip
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DayRowsTest {

    private val now = Instant.parse("2026-06-15T10:00:00Z")

    private fun event(id: String, start: String, end: String) =
        ExpandedEvent(id = id, summary = id, startAt = start, endAt = end)

    private fun trip(event: String, departAt: String) =
        Trip(id = "trip-$event", event = "/api/events/$event", departAt = departAt)

    private fun ids(rows: List<DayRow>) = rows.map {
        when (it) {
            is DayRow.Event -> it.event.id
            is DayRow.Trip -> it.trip.id
        }
    }

    @Test
    fun `trips sit among events in departure order, before their event on a tie`() {
        val early = event("early", "2026-06-15T07:00:00Z", "2026-06-15T08:00:00Z")
        val short = event("short", "2026-06-15T16:25:00Z", "2026-06-15T16:30:00Z")
        val tie = event("tie", "2026-06-15T12:00:00Z", "2026-06-15T13:00:00Z")

        val rows = dayRows(
            listOf(short, tie, early),
            listOf(trip("short", "2026-06-15T17:39:00+02:00"), trip("tie", "2026-06-15T14:00:00+02:00")),
            now,
        )

        assertEquals(listOf("early", "trip-tie", "tie", "trip-short", "short"), ids(rows))
    }

    @Test
    fun `a trip on a series sits before the occurrence of the day`() {
        val occurrence = ExpandedEvent(
            id = "series_2026-06-15", summary = "series", startAt = "2026-06-15T12:00:00Z",
            endAt = "2026-06-15T13:00:00Z", masterEventId = "series",
        )

        val rows = dayRows(listOf(occurrence), listOf(trip("series", "2026-06-15T13:00:00+02:00")), now)

        assertEquals(listOf("trip-series", "series_2026-06-15"), ids(rows))
        assertEquals(occurrence, (rows[0] as DayRow.Trip).event)
    }

    @Test
    fun `only the first trip still to leave is next`() {
        val a = event("a", "2026-06-15T12:00:00Z", "2026-06-15T13:00:00Z")
        val b = event("b", "2026-06-15T15:00:00Z", "2026-06-15T16:00:00Z")

        val rows = dayRows(listOf(a, b), listOf(trip("a", "2026-06-15T13:00:00+02:00"), trip("b", "2026-06-15T16:00:00+02:00")), now)

        assertEquals(listOf(true, false), rows.filterIsInstance<DayRow.Trip>().map { it.next })
    }

    @Test
    fun `past events and trips are marked past`() {
        val done = event("done", "2026-06-15T07:00:00Z", "2026-06-15T08:00:00Z")
        val later = event("later", "2026-06-15T12:00:00Z", "2026-06-15T13:00:00Z")

        val rows = dayRows(listOf(done, later), listOf(trip("done", "2026-06-15T08:30:00+02:00"), trip("later", "2026-06-15T13:00:00+02:00")), now)

        assertEquals(listOf(true, true, false, false), rows.map { if (it is DayRow.Event) it.past else (it as DayRow.Trip).past })
    }

    @Test
    fun `the first event not started is highlighted`() {
        val done = event("done", "2026-06-15T07:00:00Z", "2026-06-15T08:00:00Z")
        val a = event("a", "2026-06-15T12:00:00Z", "2026-06-15T13:00:00Z")
        val b = event("b", "2026-06-15T15:00:00Z", "2026-06-15T16:00:00Z")

        val highlighted = dayRows(listOf(b, a, done), emptyList(), now).filterIsInstance<DayRow.Event>().associate { it.event.id to it.highlighted }

        assertTrue(highlighted.getValue("a"))
        assertFalse(highlighted.getValue("b"))
        assertFalse(highlighted.getValue("done"))
    }
}
