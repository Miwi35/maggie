package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** The Agenda card's day (MAG-562): which rows are done, which is the next one, what a phone keeps. */
class AgendaHomeTest {

    private val now: Instant = Instant.parse("2026-10-14T12:12:00Z") // 14:12 in Paris

    private fun timed(id: String, start: String, end: String) = AgendaHomeEvent(id = id, summary = id, startAt = start, endAt = end)

    private val done = timed("done", "2026-10-14T07:00:00Z", "2026-10-14T08:00:00Z")
    private val running = timed("running", "2026-10-14T11:30:00Z", "2026-10-14T12:30:00Z")
    private val next = timed("next", "2026-10-14T13:00:00Z", "2026-10-14T14:00:00Z")
    private val later = timed("later", "2026-10-14T16:00:00Z", "2026-10-14T17:00:00Z")
    private val stay = AgendaHomeEvent(
        id = "stay",
        summary = "Séjour",
        allDay = true,
        startDate = LocalDate.of(2026, 10, 13),
        endDate = LocalDate.of(2026, 10, 16),
    )

    private fun day(vararg events: AgendaHomeEvent) = AgendaHomeState(date = "2026-10-14", events = events.toList())

    private fun AgendaTimeline.ids(rows: (AgendaTimeline) -> List<AgendaRow>) = rows(this).map { it.event.id to it.kind }

    @Test
    fun `an ended event is above the line, one in progress and the others below, the first to start being the next`() {
        val timeline = day(stay, done, running, next, later).timeline(now, compact = false)

        assertTrue(timeline.hasNowLine)
        assertEquals(listOf("stay"), timeline.allDay.map { it.event.id })
        assertEquals(listOf("done" to AgendaRowKind.PAST), timeline.ids { it.past })
        assertEquals(
            listOf("running" to AgendaRowKind.CURRENT, "next" to AgendaRowKind.NEXT, "later" to AgendaRowKind.LATER),
            timeline.ids { it.following },
        )
        assertEquals(0, timeline.hidden)
    }

    @Test
    fun `an event that ends exactly now is done`() {
        val edge = timed("edge", "2026-10-14T11:12:00Z", "2026-10-14T12:12:00Z")

        assertEquals(listOf("edge"), day(edge).timeline(now, compact = false).past.map { it.event.id })
    }

    @Test
    fun `an event begun the day before and over by now is above the line`() {
        val evening = timed("evening", "2026-10-13T21:00:00Z", "2026-10-13T23:00:00Z")

        assertEquals(listOf("evening"), day(evening, next).timeline(now, compact = false).past.map { it.event.id })
    }

    @Test
    fun `tomorrow has no line, no done and no next, and a phone does not cut it`() {
        val many = (1..9).map { timed("e$it", "2026-10-15T0$it:00:00Z", "2026-10-15T0$it:30:00Z") }
        val tomorrow = AgendaHomeState(date = "2026-10-15", events = listOf(stay) + many)

        val timeline = tomorrow.timeline(null, compact = true)

        assertFalse(timeline.hasNowLine)
        assertTrue(timeline.past.isEmpty())
        assertEquals(9, timeline.following.size)
        assertTrue(timeline.following.all { it.kind == AgendaRowKind.PLAIN })
        assertEquals(0, timeline.hidden)
    }

    @Test
    fun `a phone keeps the two last done events and the four first others, and counts the rest`() {
        val past = (1..4).map { timed("p$it", "2026-10-14T0$it:00:00Z", "2026-10-14T0$it:30:00Z") }
        val coming = (1..6).map { timed("c$it", "2026-10-14T1${it + 2}:00:00Z", "2026-10-14T1${it + 2}:30:00Z") }

        val timeline = day(stay, *past.toTypedArray(), *coming.toTypedArray()).timeline(now, compact = true)

        assertEquals(listOf("p3", "p4"), timeline.past.map { it.event.id })
        assertEquals(listOf("c1", "c2", "c3", "c4"), timeline.following.map { it.event.id })
        assertEquals(listOf("stay"), timeline.allDay.map { it.event.id })
        assertEquals(4, timeline.hidden)
        assertEquals(AgendaRowKind.NEXT, timeline.following.first().kind)
    }

    @Test
    fun `a tablet shows the whole day`() {
        val past = (1..4).map { timed("p$it", "2026-10-14T0$it:00:00Z", "2026-10-14T0$it:30:00Z") }
        val coming = (1..6).map { timed("c$it", "2026-10-14T1${it + 2}:00:00Z", "2026-10-14T1${it + 2}:30:00Z") }

        val timeline = day(*past.toTypedArray(), *coming.toTypedArray()).timeline(now, compact = false)

        assertEquals(4, timeline.past.size)
        assertEquals(6, timeline.following.size)
        assertEquals(0, timeline.hidden)
    }

    @Test
    fun `a day with nothing is empty`() {
        assertTrue(day().timeline(now, compact = true).isEmpty)
    }

    @Test
    fun `an occurrence of the events resource becomes a row, with its agenda and the place without its address`() {
        val event = Event(
            id = "1",
            summary = "Dentiste",
            location = "Cabinet Martin, 15 rue de la Paix",
            startAt = "2026-10-14T15:00:00+02:00",
            endAt = "2026-10-14T16:00:00+02:00",
            reminders = EventReminders(overrides = listOf(EventReminder(minutes = 30))),
            agenda = "/api/agendas/ag1",
        )

        val row = AgendaHomeEvent.from(event, Agenda(id = "ag1", name = "Perso", color = "#336699"))

        assertEquals("Cabinet Martin", row.locationShort)
        assertEquals("Cabinet Martin, 15 rue de la Paix", row.location)
        assertEquals(30, row.reminders?.overrides?.single()?.minutes)
        assertEquals(Instant.parse("2026-10-14T13:00:00Z"), row.start)
        assertEquals(Instant.parse("2026-10-14T14:00:00Z"), row.end)
        assertEquals(listOf("ag1", "Perso", "#336699"), listOf(row.agendaId, row.agendaName, row.agendaColor))
    }

    @Test
    fun `a whole-day occurrence keeps its dates, the end excluded, and an unknown agenda has no name`() {
        val row = AgendaHomeEvent.from(
            Event(id = "2", summary = "Séjour", allDay = true, startDate = "2026-10-13", endDate = "2026-10-16", agenda = "/api/agendas/gone"),
            agenda = null,
        )

        assertEquals(LocalDate.of(2026, 10, 13), row.startDate)
        assertEquals(LocalDate.of(2026, 10, 16), row.endDate)
        assertNull(row.start)
        assertEquals("gone", row.agendaId)
        assertNull(row.agendaName)
        assertNull(row.agendaColor)
    }

    @Test
    fun `the place is what comes before the first comma, null when blank`() {
        assertEquals("Lisbon", AgendaHomeEvent.shortLocation("Lisbon"))
        assertEquals("Cabinet", AgendaHomeEvent.shortLocation("  Cabinet , rue X, Paris"))
        assertEquals(", rue X", AgendaHomeEvent.shortLocation(", rue X"))
        assertNull(AgendaHomeEvent.shortLocation("   "))
        assertNull(AgendaHomeEvent.shortLocation(null))
    }

    @Test
    fun `the day is ordered whole-day first, then by start, then by title`() {
        val zed = AgendaHomeEvent(id = "z", summary = "Zumba", allDay = true, startDate = LocalDate.of(2026, 10, 14), endDate = LocalDate.of(2026, 10, 15))
        val abe = zed.copy(id = "a", summary = "Anniversaire")
        val late = timed("late", "2026-10-14T16:00:00Z", "2026-10-14T17:00:00Z")
        val early = timed("early", "2026-10-14T07:00:00Z", "2026-10-14T08:00:00Z")
        val tie = early.copy(id = "tie", summary = "A égalité")

        val ordered = listOf(late, zed, early, tie, abe).sortedWith(AgendaHomeEvent.ORDER)

        assertEquals(listOf("a", "z", "tie", "early", "late"), ordered.map { it.id })
    }
}
