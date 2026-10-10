package com.maggie.app.util

import com.maggie.app.data.model.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class EventExpanderRangeTest {
    private val rangeStart = Instant.parse("2026-10-01T00:00:00Z")
    private val rangeEnd = Instant.parse("2026-11-01T00:00:00Z")

    private val allDaySeries = Event(
        id = "a1",
        summary = "Vacances scolaires",
        allDay = true,
        startDate = "2026-10-05",
        endDate = "2026-10-05",
        rrule = "FREQ=WEEKLY;UNTIL=20361231T120000Z",
    )

    @Test
    fun `an all-day series with an absolute UNTIL shows every occurrence of the month`() {
        val result = EventExpander.expandForRange(listOf(allDaySeries), rangeStart, rangeEnd, emptyMap())

        assertEquals(
            listOf("2026-10-05", "2026-10-12", "2026-10-19", "2026-10-26"),
            result.map { it.startDate.toString() },
        )
        assertTrue(result.none { it.recurrenceUnreadable })
    }

    @Test
    fun `an unreadable rule shows the event once at its start, flagged`() {
        val broken = allDaySeries.copy(id = "b1", rrule = "FREQ=SOMETIMES")

        val result = EventExpander.expandForRange(listOf(broken), rangeStart, rangeEnd, emptyMap())

        assertEquals(1, result.size)
        assertEquals("b1", result[0].id)
        assertEquals(LocalDate.of(2026, 10, 5), result[0].startDate)
        assertTrue(result[0].recurrenceUnreadable)
        assertFalse(result[0].isVirtualOccurrence)
    }

    @Test
    fun `an unreadable rule outside the range shows nothing`() {
        val broken = allDaySeries.copy(id = "b1", rrule = "FREQ=SOMETIMES")

        val result = EventExpander.expandForRange(
            listOf(broken),
            Instant.parse("2026-12-01T00:00:00Z"),
            Instant.parse("2027-01-01T00:00:00Z"),
            emptyMap(),
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `one unreadable event does not hide the others`() {
        val broken = allDaySeries.copy(id = "b1", rrule = "FREQ=SOMETIMES")
        val plain = Event(
            id = "p1",
            summary = "Dentiste",
            startAt = "2026-10-07T08:00:00Z",
            endAt = "2026-10-07T09:00:00Z",
        )

        val result = EventExpander.expandForRange(listOf(broken, plain, allDaySeries), rangeStart, rangeEnd, emptyMap())

        assertEquals(1 + 1 + 4, result.size)
        assertTrue(result.any { it.id == "p1" })
    }

    // MAG-382: Google's birthday, a yearly series of one-day events, as dates.
    private val paris = ZoneId.of("Europe/Paris")
    private val birthday = Event(
        id = "bd",
        summary = "Anniversaire",
        allDay = true,
        startDate = "2037-01-01",
        endDate = "2037-01-01",
        rrule = "FREQ=YEARLY",
    )

    private fun parisDays(first: LocalDate, last: LocalDate) =
        first.atStartOfDay(paris).toInstant() to last.plusDays(1).atStartOfDay(paris).toInstant()

    @Test
    fun `a yearly all-day series gives one occurrence on its date, with no instant`() {
        val (start, end) = parisDays(LocalDate.of(2038, 1, 1), LocalDate.of(2038, 1, 31))

        val result = EventExpander.expandForRange(listOf(birthday), start, end, emptyMap())

        val occurrence = result.single()
        assertEquals("bd__2038-01-01", occurrence.id)
        assertEquals(LocalDate.of(2038, 1, 1), occurrence.startDate)
        assertEquals(LocalDate.of(2038, 1, 1), occurrence.endDate)
        assertNull(occurrence.startAt)
        assertNull(occurrence.endAt)
        assertEquals("2038-01-01T00:00:00+00:00", occurrence.originalStartAt)
        assertEquals("2037-01-01T00:00:00+00:00", occurrence.masterStartAt)
    }

    @Test
    fun `the day after a one-day all-day event holds nothing of it`() {
        // Paris's 2 January starts at 23:00 UTC on the 1st: an instant-based test would catch the birthday.
        val (start, end) = parisDays(LocalDate.of(2038, 1, 2), LocalDate.of(2038, 1, 2))

        assertTrue(EventExpander.expandForRange(listOf(birthday), start, end, emptyMap()).isEmpty())
        val (oneOffStart, oneOffEnd) = parisDays(LocalDate.of(2037, 1, 2), LocalDate.of(2037, 1, 2))
        assertTrue(EventExpander.expandForRange(listOf(birthday.copy(rrule = null)), oneOffStart, oneOffEnd, emptyMap()).isEmpty())
    }

    @Test
    fun `a one-off all-day event overlaps the range by its dates`() {
        val stay = Event(id = "s", summary = "Séjour", allDay = true, startDate = "2037-01-26", endDate = "2037-01-28")

        for ((day, expected) in listOf(25 to 0, 26 to 1, 28 to 1, 29 to 0)) {
            val (start, end) = parisDays(LocalDate.of(2037, 1, day), LocalDate.of(2037, 1, day))
            assertEquals("on the $day", expected, EventExpander.expandForRange(listOf(stay), start, end, emptyMap()).size)
        }
    }

    @Test
    fun `a multi-day all-day occurrence that began before the range is kept, both dates moved alike`() {
        val weekly = Event(id = "w", summary = "Stage", allDay = true, startDate = "2037-01-03", endDate = "2037-01-05", rrule = "FREQ=WEEKLY")
        // The 10th–12th occurrence began before the 11th.
        val (start, end) = parisDays(LocalDate.of(2037, 1, 11), LocalDate.of(2037, 1, 11))

        val occurrence = EventExpander.expandForRange(listOf(weekly), start, end, emptyMap()).single()

        assertEquals(LocalDate.of(2037, 1, 10), occurrence.startDate)
        assertEquals(LocalDate.of(2037, 1, 12), occurrence.endDate)
    }

    @Test
    fun `an exception keyed by the occurrence's date replaces or cancels it`() {
        val moved = Event(
            id = "x",
            summary = "Anniversaire (fêté le 3)",
            allDay = true,
            startDate = "2038-01-03",
            endDate = "2038-01-03",
            recurringEvent = "/api/events/bd",
            originalStartAt = "2038-01-01T00:00:00+00:00",
        )
        val cancelled = moved.copy(id = "c", originalStartAt = "2039-01-01T00:00:00+00:00", status = "cancelled")
        val (start, end) = parisDays(LocalDate.of(2038, 1, 1), LocalDate.of(2039, 12, 31))

        val result = EventExpander.expandForRange(listOf(birthday, moved, cancelled), start, end, emptyMap())

        assertEquals(listOf("x"), result.map { it.id })
        assertEquals(LocalDate.of(2038, 1, 3), result[0].startDate)
        assertEquals("bd", result[0].masterEventId)
    }
}
