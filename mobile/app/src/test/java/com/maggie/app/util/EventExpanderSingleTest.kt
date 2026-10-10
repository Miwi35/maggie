package com.maggie.app.util

import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class EventExpanderSingleTest {
    private val agenda = Agenda(id = "ag1", name = "Famille", color = "#112233")
    private val agendas = mapOf("ag1" to agenda)

    private val standalone = Event(
        id = "e1",
        summary = "Dentiste",
        startAt = "2026-10-05T08:00:00Z",
        endAt = "2026-10-05T09:00:00Z",
        agenda = "/api/agendas/ag1",
    )
    private val master = standalone.copy(id = "m1", summary = "Yoga", rrule = "FREQ=WEEKLY")
    private val exception = Event(
        id = "x1",
        summary = "Yoga (déplacé)",
        startAt = "2026-10-12T10:00:00Z",
        endAt = "2026-10-12T11:00:00Z",
        recurringEvent = "/api/events/m1",
        originalStartAt = "2026-10-12T08:00:00Z",
    )

    @Test
    fun `a standalone event keeps its own data and its agenda`() {
        val result = EventExpander.single(standalone, null, agendas)

        assertEquals("e1", result.id)
        assertEquals("Dentiste", result.summary)
        assertEquals("#112233", result.agendaColor)
        assertEquals("Famille", result.agendaName)
        assertNull(result.masterEventId)
        assertNull(result.masterRrule)
        assertFalse(result.isVirtualOccurrence)
    }

    @Test
    fun `a master is shown as itself, with its rule`() {
        val result = EventExpander.single(master, null, agendas)

        assertEquals("m1", result.id)
        assertEquals("m1", result.masterEventId)
        assertEquals("FREQ=WEEKLY", result.masterRrule)
        assertEquals(master.startAt, result.masterStartAt)
        assertNull(result.originalStartAt)
        assertFalse(result.isVirtualOccurrence)
    }

    @Test
    fun `an exception instance carries its master's rule and the slot it replaces`() {
        val result = EventExpander.single(exception, master, agendas)

        assertEquals("x1", result.id)
        assertEquals("m1", result.masterEventId)
        assertEquals("FREQ=WEEKLY", result.masterRrule)
        assertEquals(master.startAt, result.masterStartAt)
        assertEquals("2026-10-12T08:00:00Z", result.originalStartAt)
        assertEquals("/api/agendas/ag1", result.agendaIri)
        assertEquals("Famille", result.agendaName)
    }

    @Test
    fun `an all-day master is shown on its dates, its start named by the key of its date`() {
        val birthday = Event(id = "b1", summary = "Anniversaire", allDay = true, startDate = "2037-01-01", endDate = "2037-01-02", rrule = "FREQ=YEARLY")

        val result = EventExpander.single(birthday, null, agendas)

        assertEquals(java.time.LocalDate.of(2037, 1, 1), result.startDate)
        assertEquals(java.time.LocalDate.of(2037, 1, 2), result.endDate)
        assertNull(result.startAt)
        assertNull(result.endAt)
        assertEquals("2037-01-01T00:00:00+00:00", result.masterStartAt)
    }

    @Test
    fun `an exception instance whose master is unknown is shown standalone`() {
        val result = EventExpander.single(exception, null, agendas)

        assertEquals("x1", result.id)
        assertNull(result.masterEventId)
        assertNull(result.masterRrule)
        assertNull(result.originalStartAt)
    }

    @Test
    fun `an unknown agenda leaves colour and name empty`() {
        val result = EventExpander.single(standalone, null, emptyMap())

        assertEquals("/api/agendas/ag1", result.agendaIri)
        assertNull(result.agendaColor)
        assertNull(result.agendaName)
    }
}
