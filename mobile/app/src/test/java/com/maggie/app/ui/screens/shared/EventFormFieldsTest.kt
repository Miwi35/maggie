package com.maggie.app.ui.screens.shared

import com.maggie.app.data.model.Agenda
import com.maggie.app.data.model.ExpandedEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class EventFormFieldsTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val agendas = listOf(
        Agenda(id = "01FAMILLE", name = "Famille"),
        Agenda(id = "01PERSO", name = "Perso", isDefault = true),
    )

    @Test
    fun `a new event is not saveable without a title, and takes the default agenda`() {
        val blank = EventFormFields.forNewEvent(LocalDate.of(2026, 10, 7), agendas)

        assertFalse(blank.canSave)
        assertEquals("/api/agendas/01PERSO", blank.agendaIri)
        assertTrue(blank.copy(summary = "Dentiste").canSave)
    }

    @Test
    fun `an end before the start cannot be saved`() {
        val form = EventFormFields.forNewEvent(LocalDate.of(2026, 10, 7), agendas).copy(summary = "Dentiste")
        val inverted = form.copy(dates = form.dates.copy(end = form.dates.start.minusHours(1)))

        assertFalse(inverted.canSave)
    }

    @Test
    fun `a create request keeps the location and the description, a blank one goes null`() {
        val form = EventFormFields.forNewEvent(LocalDate.of(2026, 10, 7), agendas)
            .copy(summary = "Dentiste", location = "Rennes", description = "Détartrage")

        val request = form.toCreateRequest(paris)
        assertEquals("Rennes", request.location)
        assertEquals("Détartrage", request.description)
        assertNull(form.copy(location = "", description = " ").toCreateRequest(paris).location)
        assertNull(form.copy(location = "", description = " ").toCreateRequest(paris).description)
    }

    @Test
    fun `an edit patch carries the rule only when a one-off event was made to repeat`() {
        val event = ExpandedEvent(
            id = "e1",
            summary = "Dentiste",
            startAt = "2026-10-07T07:00:00Z",
            endAt = "2026-10-07T08:00:00Z",
            agendaIri = "/api/agendas/01FAMILLE",
        )
        val form = EventFormFields.fromEvent(event)

        assertFalse(form.toPatch(paris).containsKey("rrule"))
        assertEquals("\"/api/agendas/01FAMILLE\"", form.toPatch(paris)["agenda"].toString())
        assertEquals("\"FREQ=WEEKLY\"", form.copy(rrule = "FREQ=WEEKLY").toPatch(paris)["rrule"].toString())
    }
}
