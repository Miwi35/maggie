package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.HealthDay
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Santé › Journal (MAG-750): one entry per local day, read alone or over a range, its mood, fatigue and note written. Days are `YYYY-MM-DD`. */
class HealthDayRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getDay(date: String): Result<HealthDay> =
        runCatching { apiService.getHealthDay(date) }

    /** Only the days the journal holds, oldest first. */
    suspend fun getDays(from: String, to: String): Result<List<HealthDay>> =
        runCatching { apiService.getHealthDays(from, to) }

    /** [value] null empties the mood: the null is spelled out, absent would mean « unchanged ». */
    suspend fun setMood(date: String, value: Int?): Result<HealthDay> =
        runCatching { apiService.updateHealthDay(date, buildJsonObject { put("mood", value?.let(::JsonPrimitive) ?: JsonNull) }) }

    suspend fun setFatigue(date: String, value: Int?): Result<HealthDay> =
        runCatching { apiService.updateHealthDay(date, buildJsonObject { put("fatigue", value?.let(::JsonPrimitive) ?: JsonNull) }) }

    suspend fun setNote(date: String, note: String?): Result<HealthDay> =
        runCatching { apiService.updateHealthDay(date, buildJsonObject { put("note", note?.let(::JsonPrimitive) ?: JsonNull) }) }
}
