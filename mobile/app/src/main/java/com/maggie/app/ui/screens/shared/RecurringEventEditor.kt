package com.maggie.app.ui.screens.shared

import com.maggie.app.data.api.EventCreateRequest
import com.maggie.app.data.model.EventReminders
import com.maggie.app.data.model.ExpandedEvent
import com.maggie.app.data.model.occurrenceDate
import com.maggie.app.data.model.occurrenceKey
import com.maggie.app.data.repository.EventRepository
import com.maggie.app.util.RruleUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

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

        val occurrenceStart = occurrenceStartKey(event)
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

    private fun request(event: ExpandedEvent, data: JsonObject): EventCreateRequest {
        val allDay = data["allDay"]?.jsonPrimitive?.booleanOrNull ?: event.allDay
        return EventCreateRequest(
            summary = data.string("summary") ?: event.summary,
            // An all-day event has dates and no instant, a timed one the reverse (MAG-382).
            startAt = if (allDay) null else data.string("startAt") ?: event.startAt,
            endAt = if (allDay) null else data.string("endAt") ?: event.endAt,
            startDate = if (allDay) data.string("startDate") ?: event.startDate?.toString() else null,
            endDate = if (allDay) data.string("endDate") ?: event.endDate?.toString() else null,
            allDay = allDay,
            description = data.string("description"),
            location = data.string("location"),
            timeZone = event.timeZone,
            agenda = data.string("agenda") ?: event.agendaIri,
            // The exception the owner is about to create keeps the reminders he left
            // on the form, which may be none — the series' are not inherited.
            reminders = data.reminders(event.reminders),
        )
    }

    // Every occurrence moves with the edited one: shift the master by the same delta
    private fun shiftedMasterPatch(event: ExpandedEvent, occurrenceStart: String, data: JsonObject): JsonObject {
        val masterKey = event.masterStartAt ?: occurrenceStart
        val newStartDate = data.string("startDate")?.let(LocalDate::parse)
        val newEndDate = data.string("endDate")?.let(LocalDate::parse)
        // All-day: whole days, from the occurrence's date to the new start date (MAG-382).
        val shiftedDates = if (newStartDate != null && newEndDate != null) {
            val shift = ChronoUnit.DAYS.between(occurrenceDate(occurrenceStart), newStartDate)
            val start = occurrenceDate(masterKey).plusDays(shift)
            start to start.plusDays(ChronoUnit.DAYS.between(newStartDate, newEndDate))
        } else {
            null
        }

        val newStart = data.string("startAt")?.let(Instant::parse)
        val newEnd = data.string("endAt")?.let(Instant::parse)
        val masterStart = Instant.parse(masterKey)
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
                    key == "startDate" && shiftedDates != null -> put(key, JsonPrimitive(shiftedDates.first.toString()))
                    key == "endDate" && shiftedDates != null -> put(key, JsonPrimitive(shiftedDates.second.toString()))
                    else -> put(key, value)
                }
            }
        }
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    /** The form's reminders. Absent means "unchanged", null means "none left". */
    private fun JsonObject.reminders(fallback: EventReminders?): EventReminders? {
        val value = this["reminders"] ?: return fallback

        return if (value is JsonNull) null else JSON.decodeFromJsonElement(EventReminders.serializer(), value)
    }

    private companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}

/**
 * The key that names [event]'s occurrence for its exceptions: the one the expansion
 * gave it, else its start — the instant of a timed event, the [occurrenceKey] of the
 * date of an all-day one (MAG-382).
 */
fun occurrenceStartKey(event: ExpandedEvent): String =
    event.originalStartAt ?: event.startAt ?: occurrenceKey(requireNotNull(event.startDate) { "event ${event.id} has no start" })

/**
 * The exception that cancels one occurrence of a series. It carries the occurrence's
 * own bounds: dates for an all-day one, the key instant for a timed one.
 */
fun cancelledOccurrence(event: ExpandedEvent, masterId: String): EventCreateRequest {
    val key = occurrenceStartKey(event)
    val date = if (event.allDay && event.startDate != null) event.startDate.toString() else null
    return EventCreateRequest(
        summary = event.summary,
        startAt = if (date == null) key else null,
        endAt = if (date == null) key else null,
        startDate = date,
        endDate = if (date != null) (event.endDate ?: event.startDate).toString() else null,
        allDay = event.allDay,
        timeZone = event.timeZone,
        agenda = event.agendaIri,
        recurringEvent = "/api/events/$masterId",
        originalStartAt = key,
        status = "cancelled",
    )
}
