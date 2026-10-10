package com.maggie.app.data.model

import java.time.LocalDate
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** The open event is saved as JSON when the phone is turned (MAG-263): an all-day one keeps its days (MAG-382). */
class ExpandedEventSerializationTest {

    // NavGraph's SelectionJson.
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `an all-day event comes back with the same days`() {
        val event = ExpandedEvent(
            id = "e1",
            summary = "Anniversaire",
            allDay = true,
            startDate = LocalDate.of(2037, 1, 1),
            endDate = LocalDate.of(2037, 1, 2),
        )

        val saved = json.encodeToString(ExpandedEvent.serializer(), event)

        assert(saved.contains("\"startDate\":\"2037-01-01\"")) { saved }
        assertEquals(event, json.decodeFromString(ExpandedEvent.serializer(), saved))
    }

    @Test
    fun `a timed event comes back without days`() {
        val event = ExpandedEvent(id = "e2", summary = "Dentiste", startAt = "2037-01-01T09:00:00Z", endAt = "2037-01-01T10:00:00Z")

        assertEquals(event, json.decodeFromString(ExpandedEvent.serializer(), json.encodeToString(ExpandedEvent.serializer(), event)))
    }
}
