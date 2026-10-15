package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.FinanceDashboard
import com.maggie.app.data.model.FinanceScores

class FinanceDashboardRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getDashboard(year: Int, month: Int): Result<FinanceDashboard> = runCatching {
        apiService.getFinanceDashboard(year, month)
    }

    /** [date] is `YYYY-MM-DD`; null asks for today. */
    suspend fun getScores(date: String?): Result<FinanceScores> = runCatching {
        apiService.getFinanceScores(date)
    }
}
