package com.maggie.app.util

import com.maggie.app.data.model.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class EventExpanderRangeTest {
    private val rangeStart = Instant.parse("2026-10-01T00:00:00Z")
    private val rangeEnd = Instant.parse("2026-11-01T00:00:00Z")

    private val allDaySeries = Event(
        id = "a1",
        summary = "Vacances scolaires",
        allDay = true,
        startAt = "2026-10-05T00:00:00Z",
        endAt = "2026-10-06T00:00:00Z",
        rrule = "FREQ=WEEKLY;UNTIL=20361231T120000Z",
    )

    @Test
    fun `an all-day series with an absolute UNTIL shows every occurrence of the month`() {
        val result = EventExpander.expandForRange(listOf(allDaySeries), rangeStart, rangeEnd, emptyMap())

        assertEquals(
            listOf("2026-10-05", "2026-10-12", "2026-10-19", "2026-10-26"),
            result.map { it.startAt.substring(0, 10) },
        )
        assertTrue(result.none { it.recurrenceUnreadable })
    }

    @Test
    fun `an unreadable rule shows the event once at its start, flagged`() {
        val broken = allDaySeries.copy(id = "b1", rrule = "FREQ=SOMETIMES")

        val result = EventExpander.expandForRange(listOf(broken), rangeStart, rangeEnd, emptyMap())

        assertEquals(1, result.size)
        assertEquals("b1", result[0].id)
        assertEquals("2026-10-05T00:00:00Z", result[0].startAt)
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
}
