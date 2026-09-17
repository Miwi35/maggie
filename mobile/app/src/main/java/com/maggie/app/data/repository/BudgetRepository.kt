package com.maggie.app.data.repository

import com.maggie.app.data.api.EnvelopeCreateRequest
import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.api.RollOverRequest
import com.maggie.app.data.model.BudgetStatus
import com.maggie.app.data.model.DailyScore
import com.maggie.app.data.model.Envelope
import com.maggie.app.data.model.RollOverResult

class BudgetRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getEnvelopes(): Result<List<Envelope>> = runCatching {
        apiService.getEnvelopes()
    }

    suspend fun createEnvelope(request: EnvelopeCreateRequest): Result<Envelope> = runCatching {
        apiService.createEnvelope(request)
    }

    suspend fun deleteEnvelope(id: String): Result<Unit> = runCatching {
        apiService.deleteEnvelope(id)
    }

    suspend fun getBudgetStatus(year: Int, month: Int): Result<BudgetStatus> = runCatching {
        apiService.getBudgetStatus(year, month)
    }

    suspend fun rollOverEnvelopes(request: RollOverRequest): Result<RollOverResult> = runCatching {
        apiService.rollOverEnvelopes(request)
    }

    suspend fun getDailyScore(year: Int, month: Int): Result<DailyScore> = runCatching {
        apiService.getDailyScore(year, month)
    }
}
