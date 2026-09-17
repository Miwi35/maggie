package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.FinanceDashboard

class FinanceDashboardRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getDashboard(year: Int, month: Int): Result<FinanceDashboard> = runCatching {
        apiService.getFinanceDashboard(year, month)
    }
}
