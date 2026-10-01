package com.maggie.app.ui.screens.shared

import com.maggie.app.data.api.EventCreateRequest
import com.maggie.app.data.model.ExpandedEvent
import com.maggie.app.data.repository.EventRepository
import com.maggie.app.util.RruleUtils
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/**
 * Applies the output of the event edit form to a recurring series according to the
 * scope chosen by the user (same three choices as the web calendar).
 */
class RecurringEventEditor(private val eventRepository: EventRepository) {

    /** [action] is null for a one-off event or an occurrence that already is its own event. */
    suspend fun edit(event: ExpandedEvent, action: RecurrenceAction?, data: JsonObject) {
        val masterId = event.masterEventId
        if (action == null || masterId == null || !event.isVirtualOccurrence) {
            eventRepository.updateEvent(event.id, data)
            return
        }

        val occurrenceStart = event.originalStartAt ?: event.startAt
        when (action) {
            RecurrenceAction.THIS -> {
                eventRepository.createEvent(
                    request(event, data).copy(
                        recurringEvent = "/api/events/$masterId",
                        originalStartAt = occurrenceStart,
                        status = "confirmed",
                    ),
                )
            }
            RecurrenceAction.THIS_AND_FOLLOWING -> {
                val rrule = event.masterRrule ?: return
                val truncated = RruleUtils.addUntilToRrule(rrule, Instant.parse(occurrenceStart))
                eventRepository.updateEvent(masterId, buildJsonObject { put("rrule", JsonPrimitive(truncated)) })
                eventRepository.createEvent(request(event, data).copy(rrule = rrule))
            }
            RecurrenceAction.ALL -> {
                eventRepository.updateEvent(masterId, shiftedMasterPatch(event, occurrenceStart, data))
            }
        }
    }

    private fun request(event: ExpandedEvent, data: JsonObject) = EventCreateRequest(
        summary = data.string("summary") ?: event.summary,
        startAt = data.string("startAt") ?: event.startAt,
        endAt = data.string("endAt") ?: event.endAt,
        allDay = data["allDay"]?.jsonPrimitive?.booleanOrNull ?: event.allDay,
        description = data.string("description"),
        location = data.string("location"),
        timeZone = event.timeZone,
        agenda = data.string("agenda") ?: event.agendaIri,
    )

    // Every occurrence moves with the edited one: shift the master by the same delta
    private fun shiftedMasterPatch(event: ExpandedEvent, occurrenceStart: String, data: JsonObject): JsonObject {
        val newStart = data.string("startAt")?.let(Instant::parse)
        val newEnd = data.string("endAt")?.let(Instant::parse)
        val masterStart = (event.masterStartAt ?: occurrenceStart).let(Instant::parse)
        val shifted = if (newStart != null && newEnd != null) {
            val shift = newStart.toEpochMilli() - Instant.parse(occurrenceStart).toEpochMilli()
            val start = Instant.ofEpochMilli(masterStart.toEpochMilli() + shift)
            start to Instant.ofEpochMilli(start.toEpochMilli() + newEnd.toEpochMilli() - newStart.toEpochMilli())
        } else {
            null
        }

        return buildJsonObject {
            for ((key, value) in data) {
                when {
                    key == "startAt" && shifted != null -> put(key, JsonPrimitive(shifted.first.toString()))
                    key == "endAt" && shifted != null -> put(key, JsonPrimitive(shifted.second.toString()))
                    else -> put(key, value)
                }
            }
        }
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
}
