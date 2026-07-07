package com.maggie.app.data.repository

import com.maggie.app.data.api.MaggieApiService
import com.maggie.app.data.api.TransactionCreateRequest
import com.maggie.app.data.model.Transaction

class TransactionRepository(
    private val apiService: MaggieApiService,
) {
    suspend fun getTransactions(): Result<List<Transaction>> = runCatching {
        apiService.getTransactions()
    }

    suspend fun createTransaction(request: TransactionCreateRequest): Result<Transaction> = runCatching {
        apiService.createTransaction(request)
    }

    suspend fun deleteTransaction(id: String): Result<Unit> = runCatching {
        apiService.deleteTransaction(id)
    }
}
