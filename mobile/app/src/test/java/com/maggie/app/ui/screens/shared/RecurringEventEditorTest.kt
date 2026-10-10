package com.maggie.app.ui.screens.shared

import com.maggie.app.data.api.EventCreateRequest
import com.maggie.app.data.model.Event
import com.maggie.app.data.model.EventReminder
import com.maggie.app.data.model.EventReminders
import com.maggie.app.data.model.ExpandedEvent
import com.maggie.app.data.repository.EventRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class RecurringEventEditorTest {
    private lateinit var repository: EventRepository
    private lateinit var editor: RecurringEventEditor

    private val stubEvent = Event(id = "x", summary = "x", startAt = "2026-01-01T00:00:00Z", endAt = "2026-01-01T01:00:00Z")

    private fun at(vararg minutes: Int) =
        EventReminders(useDefault = false, overrides = minutes.map { EventReminder(minutes = it) })

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

    /**
     * The reminders on the form follow the occurrence the owner is splitting off (MAG-121).
     *
     * `THIS` creates a brand-new event, so anything the form holds that the
     * request does not name is lost — which is how a reminder set on screen could
     * end up on no event at all.
     */
    @Test
    fun `this occurrence keeps the reminders the form holds`() = runTest {
        val data = buildJsonObject {
            editedData().forEach { (key, value) -> put(key, value) }
            put("reminders", Json.encodeToJsonElement(EventReminders.serializer(), at(60)))
        }

        editor.edit(occurrence, RecurrenceAction.THIS, data)

        val request = slot<EventCreateRequest>()
        coVerify(exactly = 1) { repository.createEvent(capture(request)) }
        assertEquals(at(60), request.captured.reminders)
    }

    /** A form left without a reminder sends null, and the exception gets none. */
    @Test
    fun `this occurrence drops the reminders when the form has none`() = runTest {
        val data = buildJsonObject {
            editedData().forEach { (key, value) -> put(key, value) }
            put("reminders", JsonNull)
        }

        editor.edit(occurrence.copy(reminders = at(30)), RecurrenceAction.THIS, data)

        val request = slot<EventCreateRequest>()
        coVerify(exactly = 1) { repository.createEvent(capture(request)) }
        assertNull(request.captured.reminders)
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

    // MAG-382: the 2038 occurrence of a yearly birthday on 1 January, as dates.
    private val birthdayOccurrence = ExpandedEvent(
        id = "bd__2038-01-01",
        summary = "Anniversaire",
        allDay = true,
        startDate = LocalDate.of(2038, 1, 1),
        endDate = LocalDate.of(2038, 1, 1),
        isVirtualOccurrence = true,
        masterEventId = "bd",
        masterRrule = "FREQ=YEARLY",
        masterStartAt = "2037-01-01T00:00:00+00:00",
        originalStartAt = "2038-01-01T00:00:00+00:00",
    )

    // The form moves it to the 3rd and makes it two days long.
    private fun movedToThe3rd(): JsonObject = EventFormState(
        allDay = true,
        start = LocalDate.of(2038, 1, 3).atStartOfDay(),
        end = LocalDate.of(2038, 1, 4).atStartOfDay(),
    ).patch(ZoneId.of("Europe/Paris"))

    @Test
    fun `this all-day occurrence creates an exception on dates, keyed by its date`() = runTest {
        editor.edit(birthdayOccurrence, RecurrenceAction.THIS, movedToThe3rd())

        val request = slot<EventCreateRequest>()
        coVerify(exactly = 1) { repository.createEvent(capture(request)) }
        assertEquals("/api/events/bd", request.captured.recurringEvent)
        assertEquals("2038-01-01T00:00:00+00:00", request.captured.originalStartAt)
        assertEquals(true, request.captured.allDay)
        assertEquals("2038-01-03", request.captured.startDate)
        assertEquals("2038-01-04", request.captured.endDate)
        assertNull(request.captured.startAt)
        assertNull(request.captured.endAt)
    }

    @Test
    fun `this and following all-day occurrences ends the series the day before`() = runTest {
        editor.edit(birthdayOccurrence, RecurrenceAction.THIS_AND_FOLLOWING, movedToThe3rd())

        val patch = slot<JsonObject>()
        val request = slot<EventCreateRequest>()
        coVerifyOrder {
            repository.updateEvent("bd", capture(patch))
            repository.createEvent(capture(request))
        }
        assertEquals(JsonPrimitive("FREQ=YEARLY;UNTIL=20371231T235959Z"), patch.captured["rrule"])
        assertEquals("2038-01-03", request.captured.startDate)
        assertEquals("2038-01-04", request.captured.endDate)
        assertNull(request.captured.startAt)
    }

    @Test
    fun `all all-day occurrences move the master by whole days`() = runTest {
        editor.edit(birthdayOccurrence, RecurrenceAction.ALL, movedToThe3rd())

        val patch = slot<JsonObject>()
        coVerify(exactly = 1) { repository.updateEvent("bd", capture(patch)) }
        // +2 days on the master (2037-01-01), two days long
        assertEquals(JsonPrimitive("2037-01-03"), patch.captured["startDate"])
        assertEquals(JsonPrimitive("2037-01-04"), patch.captured["endDate"])
        assertEquals(JsonNull, patch.captured["startAt"])
        assertEquals(JsonNull, patch.captured["endAt"])
    }

    @Test
    fun `cancelling one all-day occurrence sends its dates and its date key`() {
        val request = cancelledOccurrence(birthdayOccurrence, "bd")

        assertEquals("cancelled", request.status)
        assertEquals("/api/events/bd", request.recurringEvent)
        assertEquals("2038-01-01T00:00:00+00:00", request.originalStartAt)
        assertEquals("2038-01-01", request.startDate)
        assertEquals("2038-01-01", request.endDate)
        assertNull(request.startAt)
        assertNull(request.endAt)
    }

    @Test
    fun `cancelling one timed occurrence sends its instant`() {
        val request = cancelledOccurrence(occurrence, "master1")

        assertEquals("2026-10-12T10:00:00Z", request.originalStartAt)
        assertEquals("2026-10-12T10:00:00Z", request.startAt)
        assertNull(request.startDate)
    }
}
