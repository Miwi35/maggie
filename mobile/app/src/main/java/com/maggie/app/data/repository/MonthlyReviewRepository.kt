package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.MonthlyReview

class MonthlyReviewRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getReview(year: Int, month: Int): Result<MonthlyReview> = runCatching {
        apiService.getMonthlyReview(year, month)
    }

    suspend fun rate(transactionId: String, verdict: String): Result<Unit> = runCatching {
        apiService.rateTransaction(transactionId, verdict)
    }
}
