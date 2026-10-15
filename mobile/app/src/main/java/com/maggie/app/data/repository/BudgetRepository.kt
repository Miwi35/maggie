package com.maggie.app.data.repository

import com.maggie.app.data.api.EnvelopeCreateRequest
import com.maggie.app.data.api.EnvelopeSplitRequest
import com.maggie.app.data.api.EnvelopeUpdateRequest
import com.maggie.app.data.api.OneOffEnvelopeCreateRequest
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

    suspend fun getEnvelope(id: String): Result<Envelope> = runCatching {
        apiService.getEnvelope(id)
    }

    suspend fun createOneOffEnvelope(request: OneOffEnvelopeCreateRequest): Result<Envelope> = runCatching {
        apiService.createOneOffEnvelope(request)
    }

    suspend fun updateEnvelope(id: String, request: EnvelopeUpdateRequest): Result<Envelope> = runCatching {
        apiService.updateEnvelope(id, request)
    }

    suspend fun createEnvelope(request: EnvelopeCreateRequest): Result<Envelope> = runCatching {
        apiService.createEnvelope(request)
    }

    suspend fun setAlertThreshold(id: String, percent: Int?): Result<Envelope> = runCatching {
        apiService.updateEnvelopeAlertThreshold(id, percent)
    }

    suspend fun deleteEnvelope(id: String): Result<Unit> = runCatching {
        apiService.deleteEnvelope(id)
    }

    suspend fun splitEnvelope(id: String, request: EnvelopeSplitRequest): Result<Unit> = runCatching {
        apiService.splitEnvelope(id, request)
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
