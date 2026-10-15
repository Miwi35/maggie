package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.Proaction
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

    /** Those starting in [from]..[to] (ISO-8601 instants with offset), soonest first. */
    suspend fun getProactions(from: String, to: String): Result<List<Proaction>> = runCatching {
        apiService.getProactions(from, to)
    }

    /** Moves the run to [startAt]; the API keeps its length. */
    suspend fun move(id: String, startAt: String): Result<Unit> = runCatching {
        apiService.updateEvent(id, JsonObject(mapOf("startAt" to JsonPrimitive(startAt))))
        Unit
    }

    suspend fun delete(id: String): Result<Unit> = runCatching {
        apiService.deleteEvent(id)
    }
}
