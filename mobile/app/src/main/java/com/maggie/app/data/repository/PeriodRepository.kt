package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.Period
import kotlinx.serialization.json.JsonObject

/** The owner's periods (MAG-815): the list, its writes, and the ones meeting a range for the day and week views. */
class PeriodRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getPeriods(): Result<List<Period>> = runCatching { apiService.getPeriods() }

    suspend fun getPeriodsExpanded(from: String, to: String): Result<List<Period>> = runCatching {
        apiService.getPeriodsExpanded(from, to)
    }

    suspend fun createPeriod(data: JsonObject): Result<Period> = runCatching { apiService.createPeriod(data) }

    suspend fun updatePeriod(id: String, data: JsonObject): Result<Period> = runCatching {
        apiService.updatePeriod(id, data)
    }

    suspend fun deletePeriod(id: String): Result<Unit> = runCatching { apiService.deletePeriod(id) }
}
