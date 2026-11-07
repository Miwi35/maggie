package com.maggie.app.data.repository

import com.maggie.app.data.api.HealthSyncRequest
import com.maggie.app.data.api.HealthSyncResponse
import com.maggie.app.data.api.MaggieApiService

/** The Health Connect batch (MAG-708): séances, pesées, pas et dépense active, in one `POST`. */
class HealthSyncRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun sync(request: HealthSyncRequest): Result<HealthSyncResponse> = runCatching { apiService.syncHealth(request) }
}
