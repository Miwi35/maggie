package com.maggie.app.data.repository

import com.maggie.app.data.api.CushionConfigRequest
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.CushionStatus

class CushionRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getStatus(): Result<CushionStatus> = runCatching {
        apiService.getCushionStatus()
    }

    suspend fun configure(request: CushionConfigRequest): Result<CushionStatus> = runCatching {
        apiService.configureCushion(request)
    }
}
