package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.model.MyShare
import com.maggie.app.data.model.MyShareRequest
import com.maggie.app.data.model.PeerDebt
import com.maggie.app.data.model.PeerDebtSummary
import com.maggie.app.util.rethrowCancellation

/** The part the owner kept of a shared spend, and the créances the rest makes (MAG-451). */
class PeerDebtRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getMyShare(transactionId: String): Result<MyShare> = runCatching {
        apiService.getMyShare(transactionId)
    }.rethrowCancellation()

    /** The part and the créance are written together, or not at all. */
    suspend fun setMyShare(transactionId: String, request: MyShareRequest): Result<MyShare> = runCatching {
        apiService.setMyShare(transactionId, request)
    }.rethrowCancellation()

    suspend fun removeMyShare(transactionId: String): Result<MyShare> = runCatching {
        apiService.removeMyShare(transactionId)
    }.rethrowCancellation()

    suspend fun getPeerDebts(): Result<List<PeerDebt>> = runCatching {
        apiService.getPeerDebts()
    }.rethrowCancellation()

    suspend fun getSummary(): Result<PeerDebtSummary> = runCatching {
        apiService.getPeerDebtSummary()
    }.rethrowCancellation()
}
