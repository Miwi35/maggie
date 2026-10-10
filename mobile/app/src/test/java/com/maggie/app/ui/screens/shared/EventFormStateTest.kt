package com.maggie.app.ui.screens.shared

import com.maggie.app.data.model.ExpandedEvent
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class EventFormStateTest {
    private val paris = ZoneId.of("Europe/Paris")

    // The evening of the owner's example: 19:00 to midnight, the next day's 00:00.
    private val evening = EventFormState(
        allDay = false,
        start = LocalDateTime.of(2026, 10, 7, 19, 0),
        end = LocalDateTime.of(2026, 10, 8, 0, 0),
    )

    @Test
    fun `a new event starts at 09h and ends at 10h on the day it was opened from`() {
        val form = EventFormState.forNewEvent(LocalDate.of(2026, 10, 7))

        assertEquals(LocalDateTime.of(2026, 10, 7, 9, 0), form.start)
        assertEquals(LocalDateTime.of(2026, 10, 7, 10, 0), form.end)
        assertFalse(form.allDay)
    }

    @Test
    fun `moving the start date keeps the duration`() {
        val moved = evening.withStartDate(LocalDate.of(2026, 10, 8))

        assertEquals(LocalDateTime.of(2026, 10, 8, 19, 0), moved.start)
        assertEquals(LocalDateTime.of(2026, 10, 9, 0, 0), moved.end)
    }

    @Test
    fun `moving the start time keeps the duration`() {
        val moved = evening.withStartTime(LocalTime.of(20, 30))

        assertEquals(LocalDateTime.of(2026, 10, 7, 20, 30), moved.start)
        assertEquals(LocalDateTime.of(2026, 10, 8, 1, 30), moved.end)
    }

    @Test
    fun `moving the end changes the duration and leaves the start`() {
        val moved = evening.withEndDate(LocalDate.of(2026, 10, 9)).withEndTime(LocalTime.of(2, 0))

        assertEquals(evening.start, moved.start)
        assertEquals(LocalDateTime.of(2026, 10, 9, 2, 0), moved.end)
    }

    @Test
    fun `an end before the start is flagged, an end on the start is not for an all-day event`() {
        assertTrue(evening.withEndDate(LocalDate.of(2026, 10, 6)).endsBeforeStart)
        assertFalse(evening.endsBeforeStart)

        val allDay = evening.withAllDay(true).withEndDate(LocalDate.of(2026, 10, 7)).withEndTime(LocalTime.of(8, 0))
        assertFalse(allDay.endsBeforeStart)
    }

    @Test
    fun `switching an evening event to all-day keeps it on one day`() {
        val form = evening.withAllDay(true)

        assertEquals(LocalDate.of(2026, 10, 7), form.startDate)
        assertEquals(LocalDate.of(2026, 10, 7), form.endDate)
        assertFalse(form.endsBeforeStart)
    }

    @Test
    fun `switching a one-day all-day event back to timed never leaves it empty`() {
        val form = evening.withAllDay(true).withAllDay(false)

        assertEquals(LocalDateTime.of(2026, 10, 7, 9, 0), form.start)
        assertEquals(LocalDateTime.of(2026, 10, 7, 10, 0), form.end)
    }

    @Test
    fun `a timed event is sent as its start and end in the zone of the user`() {
        // Paris is UTC+2 on 7 October.
        assertEquals("2026-10-07T17:00:00Z", evening.startAt(paris))
        assertEquals("2026-10-07T22:00:00Z", evening.endAt(paris))
    }

    @Test
    fun `an all-day event is sent as its dates, the last one included, and no instant`() {
        val form = evening.withAllDay(true).withEndDate(LocalDate.of(2026, 10, 9))

        assertEquals("2026-10-07", form.allDayStartDate)
        assertEquals("2026-10-09", form.allDayEndDate)
        assertNull(form.startAt(paris))
        assertNull(form.endAt(paris))
    }

    @Test
    fun `a one-day all-day event is sent with the same start and end date`() {
        val form = evening.withAllDay(true)

        assertEquals("2026-10-07", form.allDayStartDate)
        assertEquals("2026-10-07", form.allDayEndDate)
    }

    @Test
    fun `a timed event is sent with no dates`() {
        assertNull(evening.allDayStartDate)
        assertNull(evening.allDayEndDate)
    }

    @Test
    fun `a patch to all-day clears the instants, a patch to timed clears the dates`() {
        val toAllDay = evening.withAllDay(true).patch(paris)
        assertEquals(JsonPrimitive(true), toAllDay["allDay"])
        assertEquals(JsonPrimitive("2026-10-07"), toAllDay["startDate"])
        assertEquals(JsonPrimitive("2026-10-07"), toAllDay["endDate"])
        assertEquals(JsonNull, toAllDay["startAt"])
        assertEquals(JsonNull, toAllDay["endAt"])

        val toTimed = evening.patch(paris)
        assertEquals(JsonPrimitive(false), toTimed["allDay"])
        assertEquals(JsonPrimitive("2026-10-07T17:00:00Z"), toTimed["startAt"])
        assertEquals(JsonPrimitive("2026-10-07T22:00:00Z"), toTimed["endAt"])
        assertEquals(JsonNull, toTimed["startDate"])
        assertEquals(JsonNull, toTimed["endDate"])
    }

    @Test
    fun `an all-day event opens on its dates, whatever the zone`() {
        val form = EventFormState.fromEvent(
            ExpandedEvent(
                id = "e1",
                summary = "Anniversaire",
                allDay = true,
                startDate = LocalDate.of(2037, 1, 1),
                endDate = LocalDate.of(2037, 1, 1),
                timeZone = "Europe/Paris",
            ),
        )

        assertTrue(form.allDay)
        assertEquals(LocalDate.of(2037, 1, 1), form.startDate)
        assertEquals(LocalDate.of(2037, 1, 1), form.endDate)
        assertEquals("2037-01-01", form.allDayEndDate)
    }

    @Test
    fun `a three-day all-day event opens on its last day`() {
        val form = EventFormState.fromEvent(
            ExpandedEvent(
                id = "e1",
                summary = "Séjour",
                allDay = true,
                startDate = LocalDate.of(2037, 1, 26),
                endDate = LocalDate.of(2037, 1, 28),
            ),
        )

        assertEquals(LocalDate.of(2037, 1, 26), form.startDate)
        assertEquals(LocalDate.of(2037, 1, 28), form.endDate)
    }

    @Test
    fun `an all-day event opened then switched to timed gets an hour on its first day`() {
        val form = EventFormState.fromEvent(
            ExpandedEvent(id = "e1", summary = "Anniversaire", allDay = true, startDate = LocalDate.of(2037, 1, 1), endDate = LocalDate.of(2037, 1, 1)),
        ).withAllDay(false)

        assertEquals(LocalDateTime.of(2037, 1, 1, 9, 0), form.start)
        assertEquals(LocalDateTime.of(2037, 1, 1, 10, 0), form.end)
    }

    @Test
    fun `a timed event opens in its own zone`() {
        val form = EventFormState.fromEvent(
            ExpandedEvent(
                id = "e1",
                summary = "Dîner",
                startAt = "2026-10-07T17:00:00Z",
                endAt = "2026-10-07T22:00:00Z",
                timeZone = "Europe/Paris",
            ),
        )

        assertEquals(evening.start, form.start)
        assertEquals(evening.end, form.end)
    }
}
