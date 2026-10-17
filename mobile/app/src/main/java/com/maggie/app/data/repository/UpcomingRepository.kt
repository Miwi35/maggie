package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.UpcomingProjection

class UpcomingRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getUpcoming(): Result<UpcomingProjection> = runCatching {
        apiService.getUpcomingProjection()
    }
}
