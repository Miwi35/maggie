package com.maggie.app.ui.screens.shared

import com.maggie.app.data.api.EventCreateRequest
import com.maggie.app.data.model.Event
import com.maggie.app.data.model.ExpandedEvent
import com.maggie.app.data.repository.EventRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class RecurringEventEditorTest {
    private lateinit var repository: EventRepository
    private lateinit var editor: RecurringEventEditor

    private val stubEvent = Event(id = "x", summary = "x", startAt = "2026-01-01T00:00:00Z", endAt = "2026-01-01T01:00:00Z")

    // Weekly series started Monday 2026-10-05 10:00-11:00 UTC; the user edits the 2026-10-12 occurrence
    private val occurrence = ExpandedEvent(
        id = "master1__2026-10-12",
        summary = "Sport",
        startAt = "2026-10-12T10:00:00Z",
        endAt = "2026-10-12T11:00:00Z",
        isVirtualOccurrence = true,
        masterEventId = "master1",
        masterRrule = "FREQ=WEEKLY;BYDAY=MO",
        masterStartAt = "2026-10-05T10:00:00Z",
        originalStartAt = "2026-10-12T10:00:00Z",
        agendaIri = "/api/agendas/a1",
    )

    // The edit form moves the occurrence to 14:00-15:30 and renames it
    private fun editedData(): JsonObject = buildJsonObject {
        put("summary", "Sport (salle)")
        put("startAt", "2026-10-12T14:00:00Z")
        put("endAt", "2026-10-12T15:30:00Z")
        put("allDay", false)
        put("description", JsonPrimitive("Cardio"))
        put("location", JsonPrimitive("Salle"))
        put("agenda", "/api/agendas/a1")
    }

    @Before
    fun setup() {
        repository = mockk()
        coEvery { repository.createEvent(any()) } returns Result.success(stubEvent)
        coEvery { repository.updateEvent(any(), any()) } returns Result.success(stubEvent)
        editor = RecurringEventEditor(repository)
    }

    @Test
    fun `this occurrence creates a confirmed exception and leaves the series alone`() = runTest {
        editor.edit(occurrence, RecurrenceAction.THIS, editedData())

        val request = slot<EventCreateRequest>()
        coVerify(exactly = 1) { repository.createEvent(capture(request)) }
        assertEquals("/api/events/master1", request.captured.recurringEvent)
        assertEquals("2026-10-12T10:00:00Z", request.captured.originalStartAt)
        assertEquals("confirmed", request.captured.status)
        assertEquals("Sport (salle)", request.captured.summary)
        assertEquals("2026-10-12T14:00:00Z", request.captured.startAt)
        assertEquals("2026-10-12T15:30:00Z", request.captured.endAt)
        assertEquals("Salle", request.captured.location)
        assertEquals("Cardio", request.captured.description)
        assertNull(request.captured.rrule)
        coVerify(exactly = 0) { repository.updateEvent(any(), any()) }
    }

    @Test
    fun `this and following ends the series before the occurrence and starts a new one`() = runTest {
        editor.edit(occurrence, RecurrenceAction.THIS_AND_FOLLOWING, editedData())

        val patch = slot<JsonObject>()
        val request = slot<EventCreateRequest>()
        coVerifyOrder {
            repository.updateEvent("master1", capture(patch))
            repository.createEvent(capture(request))
        }
        assertEquals(
            JsonPrimitive("FREQ=WEEKLY;BYDAY=MO;UNTIL=20261011T235959Z"),
            patch.captured["rrule"],
        )
        assertEquals("FREQ=WEEKLY;BYDAY=MO", request.captured.rrule)
        assertEquals("2026-10-12T14:00:00Z", request.captured.startAt)
        assertEquals("2026-10-12T15:30:00Z", request.captured.endAt)
        assertEquals("Sport (salle)", request.captured.summary)
        assertNull(request.captured.recurringEvent)
    }

    @Test
    fun `all occurrences moves the master by the same shift and keeps the series`() = runTest {
        editor.edit(occurrence, RecurrenceAction.ALL, editedData())

        val patch = slot<JsonObject>()
        coVerify(exactly = 1) { repository.updateEvent("master1", capture(patch)) }
        // +4h shift applied to the master (2026-10-05 10:00), new duration 1h30
        assertEquals(JsonPrimitive("2026-10-05T14:00:00Z"), patch.captured["startAt"])
        assertEquals(JsonPrimitive("2026-10-05T15:30:00Z"), patch.captured["endAt"])
        assertEquals(JsonPrimitive("Sport (salle)"), patch.captured["summary"])
        assertEquals(JsonPrimitive("Salle"), patch.captured["location"])
        coVerify(exactly = 0) { repository.createEvent(any()) }
    }

    @Test
    fun `an already modified occurrence updates its own event`() = runTest {
        val exception = occurrence.copy(id = "exc1", isVirtualOccurrence = false)

        editor.edit(exception, null, editedData())

        coVerify(exactly = 1) { repository.updateEvent("exc1", any()) }
        coVerify(exactly = 0) { repository.createEvent(any()) }
    }

    @Test
    fun `a one-off event updates itself`() = runTest {
        val single = ExpandedEvent(
            id = "single1",
            summary = "Dentiste",
            startAt = "2026-10-12T10:00:00Z",
            endAt = "2026-10-12T11:00:00Z",
        )

        editor.edit(single, null, editedData())

        coVerify(exactly = 1) { repository.updateEvent("single1", any()) }
    }
}
