package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.HealthProfile
import kotlinx.serialization.json.JsonObject

/** The owner's health profile (MAG-707): read, and the fields changed written back. */
class HealthProfileRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getProfile(): Result<HealthProfile> = runCatching { apiService.getHealthProfile() }

    /** [changes]: only the fields to write, e.g. `{"heightCm":179}`. */
    suspend fun update(changes: JsonObject): Result<HealthProfile> = runCatching { apiService.updateHealthProfile(changes) }
}
