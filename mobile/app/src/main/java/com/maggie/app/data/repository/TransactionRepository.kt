package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.api.TransactionCreateRequest
import com.maggie.app.data.model.AccountIncident
import com.maggie.app.data.model.Transaction
import com.maggie.app.data.model.TransferInfo
import com.maggie.app.data.model.TransferLeg
import com.maggie.app.data.model.TransferUpdateRequest

class TransactionRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getTransactions(accountId: String? = null): Result<List<Transaction>> = runCatching {
        apiService.getTransactions(accountId)
    }

    suspend fun getIncidents(accountId: String): Result<List<AccountIncident>> = runCatching {
        apiService.getAccountIncidents(accountId)
    }

    suspend fun createTransaction(request: TransactionCreateRequest): Result<Transaction> = runCatching {
        apiService.createTransaction(request)
    }

    suspend fun deleteTransaction(id: String): Result<Unit> = runCatching {
        apiService.deleteTransaction(id)
    }

    suspend fun getTransfer(id: String): Result<TransferInfo> = runCatching {
        apiService.getTransactionTransfer(id)
    }

    suspend fun getTransferCandidates(id: String): Result<List<TransferLeg>> = runCatching {
        apiService.getTransferCandidates(id)
    }

    /** [counterpartId] null marks a line with no counterpart, or releases it when [internal] is false. */
    suspend fun setTransfer(id: String, internal: Boolean, counterpartId: String? = null): Result<TransferInfo> = runCatching {
        apiService.setTransactionTransfer(
            id,
            TransferUpdateRequest(transferKind = if (internal) "internal" else "none", counterpartId = counterpartId),
        )
    }
}
