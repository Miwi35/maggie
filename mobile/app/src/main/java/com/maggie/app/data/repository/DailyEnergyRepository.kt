package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.DailyEnergy

/** The consolidated daily energy of the owner (MAG-746): one row per local day from [from] to [to], `YYYY-MM-DD`. */
class DailyEnergyRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getDailyEnergy(from: String, to: String): Result<List<DailyEnergy>> =
        runCatching { apiService.getDailyEnergy(from, to) }
}
