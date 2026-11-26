package com.maggie.app.data.repository

import com.maggie.app.data.api.EventCreateRequest
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.Proaction
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Maggie's runs. They are read from `/api/proactions` and kept out of the event cache: they belong
 * to the internal « Maggie » agenda, which the agenda screen shows through its own switch. A
 * proaction is still an event, so it is moved and deleted through `/api/events/{id}`.
 */
class ProactionRepository(
    private val apiService: MaggieApiService,
) {
    /** All of them, soonest first. */
    suspend fun getProactions(): Result<List<Proaction>> = runCatching {
        apiService.getProactions()
    }

    /**
     * Those starting in [from]..[to] (ISO-8601 instants with offset), soonest first, and the series
     * that started before [from], whose passes may fall in the range.
     */
    suspend fun getProactions(from: String, to: String): Result<List<Proaction>> = runCatching {
        val inRange = apiService.getProactions(from, to)
        val series = apiService.getProactionSeries(from)
        (inRange + series).distinctBy { it.id }
    }

    /** Files the exception that moves [occurrence] of [series] to [startAt]; the series is left as it is. */
    suspend fun moveOccurrence(series: Proaction, occurrence: String, startAt: String): Result<Unit> = runCatching {
        apiService.createEvent(occurrenceRequest(series, occurrence, startAt, "confirmed"))
        Unit
    }

    /** Files the exception that cancels [occurrence] of [series]. */
    suspend fun cancelOccurrence(series: Proaction, occurrence: String): Result<Unit> = runCatching {
        apiService.createEvent(occurrenceRequest(series, occurrence, occurrence, "cancelled"))
        Unit
    }

    private fun occurrenceRequest(series: Proaction, occurrence: String, startAt: String, status: String): EventCreateRequest {
        val end = Instant.parse(startAt).plus(Duration.ofMinutes(OCCURRENCE_MINUTES)).toString()
        return EventCreateRequest(
            summary = series.summary,
            startAt = startAt,
            endAt = if (status == "cancelled") startAt else end,
            timeZone = series.timeZone ?: "Europe/Paris",
            agenda = series.agenda,
            recurringEvent = "/api/events/${series.id}",
            originalStartAt = occurrence,
            status = status,
        )
    }

    /** Moves the run to [startAt]; the API keeps its length. */
    suspend fun move(id: String, startAt: String): Result<Unit> = runCatching {
        apiService.updateEvent(id, JsonObject(mapOf("startAt" to JsonPrimitive(startAt))))
        Unit
    }

    suspend fun delete(id: String): Result<Unit> = runCatching {
        apiService.deleteEvent(id)
    }

    private companion object {
        const val OCCURRENCE_MINUTES = 5L
    }
}
